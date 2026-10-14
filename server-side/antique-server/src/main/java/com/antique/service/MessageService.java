package com.antique.service;

import com.antique.entity.Message;
import com.antique.mq.OrderEvent;
import com.antique.vo.MessageVO;
import com.antique.vo.PageResultVO;
import com.antique.vo.UnreadCountVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 消息服务接口 — Controller 只依赖这个接口，实现细节在 impl 里
 *
 * <p>继承 IService<Message> 自动获得 save/page/update 等方法
 * （实现类再继承 ServiceImpl 就齐活了，这是 MyBatis-Plus 的标准组合）。
 */
public interface MessageService extends IService<Message> {

    /**
     * 消费订单事件：写站内信 + WebSocket 推送（M3 新增）
     *
     * <p>带 eventId 落库，靠数据库唯一索引实现幂等，
     * 所以重复投递同一个事件不会产生两条站内信。
     *
     * <p>M3 之前这里还有一个 {@code sendOrderMessage(userId, orderNo)}：
     * 由下单事务同步调用。现在订单只写 outbox 事件，通知改由本方法生成，
     * 那个方法已经删除（它的调用方也一并删掉了）。
     *
     * @param event 订单事件（含 eventId / 事件类型 / 订单号 / 买家 ID）
     */
    void saveAndPush(OrderEvent event);

    /** 分页查询当前用户消息（创建时间倒序） */
    PageResultVO<MessageVO> pageList(Long userId, Integer page, Integer size);

    /** 未读消息数（前端角标） */
    UnreadCountVO unreadCount(Long userId);

    /** 标记已读（仅本人消息，幂等） */
    void markRead(Long userId, Long messageId);
}
