package com.antique.service.impl;

import cn.hutool.json.JSONUtil;
import com.antique.entity.Outbox;
import com.antique.mapper.OutboxMapper;
import com.antique.mq.OrderEvent;
import com.antique.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 服务实现（M3）
 *
 * <p>本类只做一件事：把事件序列化成 JSON 写进 outbox 表。
 * 投递由 {@link com.antique.task.OutboxRelayTask} 负责。
 *
 * @see com.antique.service.OutboxService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxMapper outboxMapper;

    /**
     * 写入待投递事件
     *
     * <p>{@code @Transactional(propagation = REQUIRED)}（默认值）的含义：
     * <b>调用方已经有事务就加入它，没有就自己开一个</b>。
     * 业务侧（OrderServiceImpl）本身是 {@code @Transactional}，所以这里会自动加入
     * 同一个事务 —— 事件和订单一起提交、一起回滚。
     *
     * <p>反过来也要说清楚：<b>如果调用方没有事务，这个注解救不了原子性</b> ——
     * 事件会立刻提交，而调用方后面的失败无法把它回滚。
     * 所以"必须在事务内调用"这条约束是调用方的责任，见
     * {@link com.antique.service.OutboxService} 的说明。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(OrderEvent event) {
        Outbox outbox = new Outbox()
                .setEventId(event.getEventId())
                .setEventType(event.getEventType())
                // 路由键由事件类型推导，两者不会对不上（见 OrderEvent#routingKey）
                .setRoutingKey(event.routingKey())
                // 序列化成 JSON 存库：投递时原样发给 MQ，不需要再反序列化一遍
                .setPayload(JSONUtil.toJsonStr(event))
                .setStatus(Outbox.STATUS_PENDING)
                .setRetryCount(0);
        // nextRetryTime 不设值 → 走数据库的 DEFAULT CURRENT_TIMESTAMP，
        // 也就是说"刚写入的事件立刻就有资格被投递"
        outboxMapper.insert(outbox);

        log.info("Outbox 事件已入库（待投递）: eventId={}, type={}, orderNo={}",
                event.getEventId(), event.getEventType(), event.getOrderNo());
    }
}
