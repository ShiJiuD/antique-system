package com.antique.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.antique.constant.MQConstants;
import com.antique.constant.MessageConstant;
import com.antique.entity.Message;
import com.antique.exception.AuthException;
import com.antique.mapper.MessageMapper;
import com.antique.mq.OrderEvent;
import com.antique.service.MessageService;
import com.antique.util.TimeUtil;
import com.antique.vo.MessageVO;
import com.antique.vo.PageResultVO;
import com.antique.vo.UnreadCountVO;
import com.antique.websocket.WebSocketSessionManager;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息服务实现 — 下单消息发送（写库 + 推送）与查询/已读
 *
 * <h3>下单消息双写（本模块核心逻辑）</h3>
 * <ol>
 *   <li><b>写库</b>：INSERT message（与下单同事务，下单失败消息随之回滚）——保证消息不丢</li>
 *   <li><b>推送</b>：WebSocketSessionManager.sendToUser 实时喊一声——保证在线用户秒看到</li>
 * </ol>
 * <p>推送失败/用户离线只记日志，靠落库兜底，所以推送永远不能影响下单主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {

    private final MessageMapper messageMapper;
    private final WebSocketSessionManager sessionManager;  // "在线登记簿"：推送就靠它

    /** 消息类型：订单消息（对应 message.type） */
    private static final int TYPE_ORDER = 2;

    // ==================== 事件消费（M3）====================

    /**
     * 消费订单事件：写站内信 + 推送（幂等）
     *
     * <p><b>本方法故意不加 @Transactional</b>，两个原因：
     * <ol>
     *   <li>只有一条 INSERT，本来就不需要事务；</li>
     *   <li>更重要的是：重复事件会在这里抛 {@code DuplicateKeyException}。
     *       如果外面套了事务，这个异常会把事务标记成 rollback-only，
     *       调用方即使捕获了它，提交时仍然会抛 {@code UnexpectedRollbackException} ——
     *       结果是"幂等没生效，反而把消费者搞崩"。没有事务时，它就是一次干净的插入失败。</li>
     * </ol>
     */
    @Override
    public void saveAndPush(OrderEvent event) {
        Message message = new Message()
                .setUserId(event.getUserId())
                .setType(TYPE_ORDER)
                // ★ 幂等键：同一个事件第二次插入会被数据库唯一索引 uk_event_id 挡住
                .setEventId(event.getEventId())
                .setTitle(MessageConstant.MESSAGE_TITLE_ORDER)
                .setContent(buildContent(event))
                .setIsRead(0);
        save(message);

        // 推送失败不能改变"消息已经落库"这个事实，更不能让消费者 nack 重投 ——
        // 消息已经在库里了，重投只会再撞一次唯一索引。所以这里单独兜住。
        try {
            sessionManager.sendToUser(event.getUserId(), buildNewMessagePayload(message));
        } catch (Exception e) {
            log.warn("站内信实时推送失败（消息已落库，用户下次进消息中心仍能看到）: messageId={}, userId={}",
                    message.getId(), event.getUserId(), e);
        }
        log.info("订单事件已生成站内信: eventId={}, type={}, messageId={}",
                event.getEventId(), event.getEventType(), message.getId());
    }

    /**
     * 按事件类型选消息模板，并把订单号填进去
     *
     * <p>「事件类型 → 文案」的映射集中在这一处：将来要加"已发货"事件，
     * 在 {@link MQConstants} 加类型常量、{@link MessageConstant} 加模板、这里加一个 case 就够了，
     * <b>订单代码一行都不用动</b> —— 这正是事件驱动相比"订单里直接写通知逻辑"的核心好处。
     */
    private String buildContent(OrderEvent event) {
        String template = switch (event.getEventType()) {
            case MQConstants.EVENT_ORDER_CREATED -> MessageConstant.MESSAGE_ORDER_CREATED;
            case MQConstants.EVENT_ORDER_PAID -> MessageConstant.MESSAGE_ORDER_PAID;
            case MQConstants.EVENT_ORDER_CLOSED -> MessageConstant.MESSAGE_ORDER_CLOSED;
            case MQConstants.EVENT_ORDER_RECEIVED -> MessageConstant.MESSAGE_ORDER_RECEIVED;
            default -> throw new IllegalArgumentException("未知的订单事件类型: " + event.getEventType());
        };
        return String.format(template, event.getOrderNo());
    }

    /**
     * 组装推送 JSON — 协议见《05消息接口文档.md》：
     * {"type":"newMessage","data":{id,messageType,title,content,createdTime}}
     */
    private String buildNewMessagePayload(Message message) {
        JSONObject data = JSONUtil.createObj()
                .set("id", message.getId())
                .set("messageType", message.getType())
                .set("title", message.getTitle())
                .set("content", message.getContent())
                .set("createdTime", TimeUtil.format(message.getCreateTime()));
        return JSONUtil.createObj()
                .set("type", "newMessage")
                .set("data", data)
                .toString();
    }

    // ==================== 查询 ====================

    @Override
    public PageResultVO<MessageVO> pageList(Long userId, Integer page, Integer size) {
        // 参数兜底：页码/条数没传或非法时用默认值
        int pageNum = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 10 : size;

        // 分页查询：只查自己的消息（eq user_id），最新的在前
        IPage<Message> result = page(new Page<>(pageNum, pageSize),
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getUserId, userId)
                        .orderByDesc(Message::getCreateTime));

        // 实体转 VO：只把前端需要的字段挑出来
        List<MessageVO> list = result.getRecords().stream()
                .map(this::toVO)
                .toList();
        return PageResultVO.<MessageVO>builder()
                .list(list)
                .total(result.getTotal())
                .page(pageNum)
                .size(pageSize)
                .build();
    }

    @Override
    public UnreadCountVO unreadCount(Long userId) {
        long count = messageMapper.countUnread(userId);
        return UnreadCountVO.builder().count((int) count).build();
    }

    // ==================== 已读 ====================

    @Override
    public void markRead(Long userId, Long messageId) {
        // UPDATE ... WHERE id=? AND user_id=?
        // 不带 is_read=0 条件：已读过的消息重复调用也成功（幂等无副作用，与接口文档一致）；
        // 带 user_id 条件：只能操作自己的消息，防越权
        boolean updated = update(Wrappers.<Message>lambdaUpdate()
                .set(Message::getIsRead, 1)
                .set(Message::getReadTime, LocalDateTime.now())
                .eq(Message::getId, messageId)
                .eq(Message::getUserId, userId));

        // 影响行数 0 = 消息不存在/不是自己的 → 抛业务异常，全局处理器转成 {code:0,msg:"消息不存在"}
        if (!updated) {
            throw new AuthException(MessageConstant.MESSAGE_NOT_EXIST);
        }
    }

    // ==================== 组装 ====================

    private MessageVO toVO(Message message) {
        return MessageVO.builder()
                .id(message.getId())
                .type(message.getType())
                .title(message.getTitle())
                .content(message.getContent())
                .isRead(message.getIsRead())
                .createdTime(TimeUtil.format(message.getCreateTime()))
                .build();
    }
}
