package com.antique.config;

import com.antique.constant.MQConstants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 资源声明 — M1 行为流水
 *
 * <h3>本类只声明「死信」三件套</h3>
 * <p>主交换机 {@code behavior.exchange}、主队列 {@code behavior.queue} 以及它们的绑定关系，
 * 已经用 <b>注解式</b> 写在 {@link com.antique.mq.BehaviorConsumer} 的
 * {@code @RabbitListener(bindings = @QueueBinding(...))} 上 ——
 * 「在哪里消费」和「在哪里声明」放在同一处。
 *
 * <p>而 <b>死信队列没有消费者</b>（失败消息要留在队列里等人工排查，不能自动吃掉），
 * 没有地方挂 {@code @RabbitListener} 注解，所以只能用 {@code @Bean} 声明。
 *
 * <p>两种方式都由 {@code RabbitAdmin} 在应用启动时自动到 Broker 声明（已存在则跳过），
 * 属于"环境即代码"：换机器、重建容器都不用重新点管理台。
 *
 * <h3>消息链路</h3>
 * <pre>
 *   BehaviorEventPublisher ──▶ behavior.exchange (topic)
 *                                    │ routing key: behavior.visit / behavior.order
 *                                    ▼
 *                              behavior.queue ──▶ BehaviorConsumer（手动 ack）
 *                                    │ 处理失败 basicNack(requeue=false)
 *                                    ▼
 *                              behavior.dlx.exchange ──▶ behavior.dlx.queue（人工排查）
 * </pre>
 *
 * @see com.antique.constant.MQConstants 所有交换机/队列/路由键的名字
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    /** Spring Boot 自动配置的模板（全局单例）；回调挂在这里，所有生产者都受益 */
    private final RabbitTemplate rabbitTemplate;

    // ========================================================================
    //  生产者确认回调（Publisher Confirm + Publisher Return）
    // ========================================================================

    /**
     * 挂上「消息退回」回调 —— 让"消息丢了"从静默变成日志可查
     *
     * <p>为什么要挂这个：配置里开了 {@code publisher-returns: true} 和 {@code template.mandatory: true}
     * 只是"打开了退回通道"，<b>不挂回调等于没人监听</b>，路由失败的消息依然悄无声息。
     *
     * <p>这里只处理 <b>Return</b>（消息到了交换机但没路由到任何队列：路由键写错、队列被删）。
     * <b>Confirm</b>（消息是否到达交换机）走的是"每条消息一个 Future"的形式，
     * 写在 {@link com.antique.mq.BehaviorEventPublisher} 里 —— 因为 Future 能直接拿到
     * 该条消息的业务上下文，比全局回调里靠 CorrelationData 认人清楚得多。
     * Return 没有 per-message 形式，只能是全局的。
     *
     * <p>两者都<b>只记日志、不做重发</b>：行为统计丢几条业务上可接受，
     * 重发会引入重复计数与重试死循环的风险，不划算。关键是把"悄悄丢了"变成"知道丢了"。
     */
    @PostConstruct
    public void initPublisherCallbacks() {
        // 消息到达交换机但没进任何队列（需 publisher-returns + template.mandatory 同时开启）
        // 注：这个回调是全局的，M1 行为消息和 M3 订单事件共用同一个 RabbitTemplate，
        //     所以日志里不写死"行为消息"，改为打印 exchange + routingKey 自行区分。
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "消息路由失败（消息没进任何队列，可能丢了）: exchange={}, routingKey={}, replyCode={}, replyText={}, message={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyCode(), returned.getReplyText(), returned.getMessage()));
    }

    // ========================================================================
    //  死信交换机 + 队列 + 绑定（无消费者，只能用 Bean 声明）
    // ========================================================================

    /**
     * 死信交换机：direct 类型（死信只需要一个固定路由键）
     */
    @Bean
    public DirectExchange behaviorDlxExchange() {
        return new DirectExchange(MQConstants.BEHAVIOR_DLX_EXCHANGE, true, false);
    }

    /**
     * 死信队列：持久化，不带 TTL（失败消息一直留着，等人工处理）
     */
    @Bean
    public Queue behaviorDlxQueue() {
        return QueueBuilder.durable(MQConstants.BEHAVIOR_DLX_QUEUE).build();
    }

    /**
     * 死信绑定：把死信队列绑到死信交换机上
     */
    @Bean
    public Binding behaviorDlxBinding(@Qualifier("behaviorDlxQueue") Queue behaviorDlxQueue,
                                      @Qualifier("behaviorDlxExchange") DirectExchange behaviorDlxExchange) {
        return BindingBuilder.bind(behaviorDlxQueue).to(behaviorDlxExchange).with(MQConstants.BEHAVIOR_DLX_KEY);
    }

    // ========================================================================
    //  订单事件总线（M3）死信通道
    // ========================================================================

    /**
     * 订单事件死信交换机
     *
     * <p>同样无消费者 —— 订单事件处理失败重试耗尽后，消息留在这里等人工处理。
     * 与 M1 的口径一致：**死信队列不是垃圾桶，是"需要人看一眼"的入口**。
     */
    @Bean
    public DirectExchange orderEventDlxExchange() {
        return new DirectExchange(MQConstants.ORDER_EVENT_DLX_EXCHANGE, true, false);
    }

    /** 订单事件死信队列：持久化，不带 TTL（留存到人工处理为止） */
    @Bean
    public Queue orderEventDlxQueue() {
        return QueueBuilder.durable(MQConstants.ORDER_EVENT_DLX_QUEUE).build();
    }

    /** 订单事件死信绑定 */
    @Bean
    public Binding orderEventDlxBinding(@Qualifier("orderEventDlxQueue") Queue orderEventDlxQueue,
                                        @Qualifier("orderEventDlxExchange") DirectExchange orderEventDlxExchange) {
        return BindingBuilder.bind(orderEventDlxQueue).to(orderEventDlxExchange).with(MQConstants.ORDER_EVENT_DLX_KEY);
    }
}
