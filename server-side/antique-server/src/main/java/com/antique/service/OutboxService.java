package com.antique.service;

import com.antique.mq.OrderEvent;

/**
 * Outbox 服务（M3）— 业务侧唯一的"发事件"入口
 *
 * <h3>调用方要记住的一件事</h3>
 * <p>{@link #save(OrderEvent)} <b>必须和业务操作在同一个事务里调用</b>。
 * 这是 Outbox 模式成立的前提：订单落库和事件入库是同一个事务，
 * 才谈得上"要么都成、要么都不成"。
 *
 * <p>调用时机上建议放在业务的最后一步（所有校验、扣减都做完之后），
 * 这样事务里的事务性锁持有时间最短。
 *
 * <h3>它不是"发消息"，是"记下来要发消息"</h3>
 * <p>真正投递到 MQ 的是 {@code OutboxRelayTask}（定时任务）。
 * 业务代码完全不需要关心 MQ 是否可用 —— 这一点很重要：
 * <b>MQ 挂了不会让下单失败</b>，事件会堆在 outbox 表里，等 MQ 恢复后补发。
 */
public interface OutboxService {

    /**
     * 在业务事务内记录一条待投递的订单事件
     *
     * @param event 事件对象（eventId 已由工厂方法生成）
     */
    void save(OrderEvent event);
}
