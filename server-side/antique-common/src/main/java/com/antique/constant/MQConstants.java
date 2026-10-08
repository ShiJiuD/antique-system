package com.antique.constant;

/**
 * RabbitMQ 常量 — 交换机 / 队列 / 路由键 统一命名
 *
 * <h3>为什么集中在一个类里</h3>
 * <p>交换机名、队列名、路由键既被<b>生产者</b>（BehaviorEventPublisher）使用，
 * 也被<b>消费者</b>（BehaviorConsumer 的 {@code @QueueBinding} 注解）使用。
 * 写在一个地方可以避免两边字符串不一致 —— 那类错误不会编译报错，
 * 只会表现为"消息发出去了但没人消费"，排查起来很费时间。
 *
 * <h3>命名规范</h3>
 * <ul>
 *   <li>交换机：{@code {业务}.exchange}</li>
 *   <li>队列：{@code {业务}.queue}</li>
 *   <li>路由键：{@code {业务}.{动作}}，topic 交换机下用点号分层</li>
 *   <li>死信相关统一带 {@code dlx} 前缀后缀：{@code {业务}.dlx.exchange} / {@code {业务}.dlx.queue}</li>
 * </ul>
 *
 * <h3>关于延迟交换机（delayed）</h3>
 * <p>本项目<b>没有</b>使用 {@code @Exchange(delayed = "true")}：它声明的是
 * {@code x-delayed-message} 类型交换机，要求 Broker 启用
 * {@code rabbitmq_delayed_message_exchange} 插件，否则应用启动即报
 * {@code no exchange type 'x-delayed-message'}。
 * 且"行为流水"本身就要求即时统计，没有延迟语义 ——
 * 订单超时场景由 Redisson 的 RDelayedQueue + 定时任务兜底完成。
 */
public class MQConstants {

    // ==================== 行为流水（M1）====================

    /** 行为交换机（topic 类型） */
    public static final String BEHAVIOR_EXCHANGE = "behavior.exchange";

    /** 行为队列（消费者监听它） */
    public static final String BEHAVIOR_QUEUE = "behavior.queue";

    /** 路由键：浏览行为 */
    public static final String BEHAVIOR_VISIT_KEY = "behavior.visit";

    /** 路由键：下单行为 */
    public static final String BEHAVIOR_ORDER_KEY = "behavior.order";

    // ==================== 行为流水的死信通道 ====================

    /** 死信交换机（direct 类型） */
    public static final String BEHAVIOR_DLX_EXCHANGE = "behavior.dlx.exchange";

    /** 死信队列（消费失败的消息留在这里等人工排查） */
    public static final String BEHAVIOR_DLX_QUEUE = "behavior.dlx.queue";

    /** 死信路由键 */
    public static final String BEHAVIOR_DLX_KEY = "behavior.dlx";
}
