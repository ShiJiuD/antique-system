package com.antique.config;

import com.antique.constant.MQConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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
@Configuration
public class RabbitMQConfig {

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
}
