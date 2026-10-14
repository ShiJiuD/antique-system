package com.antique.mq;

import cn.hutool.json.JSONUtil;
import com.antique.constant.MQConstants;
import com.antique.service.MessageService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 订单事件消费者（M3）— 事件总线的"消息中心"这一端
 *
 * <h3>它替代了什么</h3>
 * <pre>
 *   改造前：OrderServiceImpl.createOrder 事务内直接调
 *           messageService.sendOrderMessage(userId, orderNo)
 *           → 通知逻辑耦合在订单事务里；失败只打日志（静默丢失）
 *
 *   改造后：订单只写 outbox 事件；站内信由本消费者根据事件生成
 *           → 订单不认识"站内信"这个东西，将来加短信/邮件/数据仓库
 *             只需新增一个消费者绑定同一个交换机，订单代码零改动
 * </pre>
 *
 * <h3>幂等（M3 的关键）</h3>
 * <p>MQ 提供的是"至少一次"投递，同一个事件可能被消费多次。幂等靠
 * <b>{@code message.event_id} 唯一索引</b>实现：
 * <ul>
 *   <li>第一次消费 → 插入成功 → 生成站内信</li>
 *   <li>重复消费 → 插入抛 {@link DuplicateKeyException} → 捕获后<b>正常 ack</b></li>
 * </ul>
 * 注意"重复消息必须 ack"这一点：如果不 ack 而是 nack，这条重复消息会被无限重投，
 * 每次都撞一次唯一索引 —— 死循环。这是幂等实现里最容易踩的坑。
 *
 * <h3>消息体为什么接 String 而不是 OrderEvent 对象</h3>
 * <p>项目里没有配置 {@code Jackson2JsonMessageConverter}，Spring AMQP 用的是默认的
 * {@code SimpleMessageConverter}，消息体是纯 JSON 字符串。
 * 这与 M1 的 {@link BehaviorConsumer} 完全一致：收 String，再用 Hutool 转对象。
 * 好处是不依赖"类型头"，在管理台里也能直接看到明文。
 *
 * <h3>失败处理</h3>
 * <p>{@code basicNack(requeue=false)} → 消息进入 {@code order.event.dlx.queue} 等人工处理。
 * <p><b>关于"重试 3 次"</b>：方案文档附录 B 里写了"最多重试 3 次后进死信"，
 * 但文档给的代码骨架（附录 C5）是"失败直接进死信"，两者不一致。
 * 这里按<b>代码骨架</b>实现（与 M1 的机制完全一致，风险最低）。
 * 若要做"本进程内重试 3 次再进死信"，标准做法是给本监听器单独配一个容器工厂，
 * 挂上 {@code RetryInterceptorBuilder.stateless().maxAttempts(3).recoverer(new RejectAndDontRequeueRecoverer())}，
 * 并把 ack 模式改成 AUTO（让异常抛出去）—— 属于可选增强，不影响本类的正确性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final MessageService messageService;

    /** 死信参数名（AMQP 协议标准参数，由 Broker 识别） */
    private static final String ARG_DLX_EXCHANGE = "x-dead-letter-exchange";
    private static final String ARG_DLX_ROUTING_KEY = "x-dead-letter-routing-key";

    /**
     * 消费订单事件：生成站内信（写库 + WebSocket 推送）
     *
     * <p>注解里的三件事：
     * <ol>
     *   <li>{@code @Queue}：队列名 + 持久化 + 死信参数（nack 后转发的目标）</li>
     *   <li>{@code @Exchange}：交换机名 + 类型（topic，一个事件可以有多个消费方）</li>
     *   <li>{@code key}：绑定的四个路由键。将来加新事件类型（如 order.shipped），
     *       在 {@link MQConstants} 加常量、这里补一个 key 即可</li>
     * </ol>
     *
     * @param payload     消息体（JSON 字符串）
     * @param channel     当前信道（手动 ack 用）
     * @param deliveryTag 投递标签
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = MQConstants.ORDER_EVENT_QUEUE,
                    durable = "true",
                    arguments = {
                            @Argument(name = ARG_DLX_EXCHANGE, value = MQConstants.ORDER_EVENT_DLX_EXCHANGE),
                            @Argument(name = ARG_DLX_ROUTING_KEY, value = MQConstants.ORDER_EVENT_DLX_KEY)
                    }),
            exchange = @Exchange(name = MQConstants.ORDER_EVENT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = {
                    MQConstants.ORDER_CREATED_KEY,
                    MQConstants.ORDER_PAID_KEY,
                    MQConstants.ORDER_CLOSED_KEY,
                    MQConstants.ORDER_RECEIVED_KEY
            }
    ))
    public void onOrderEvent(String payload,
                             Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        // 先解析出来，便于异常日志里能带上 eventId
        OrderEvent event = null;
        try {
            event = JSONUtil.toBean(payload, OrderEvent.class);

            messageService.saveAndPush(event);

            channel.basicAck(deliveryTag, false);
            log.info("订单事件处理成功: eventId={}, type={}, orderNo={}",
                    event.getEventId(), event.getEventType(), event.getOrderNo());
        } catch (DuplicateKeyException dup) {
            // ★ 幂等命中：同一个事件被重复投递，唯一索引 uk_event_id 挡住了插入。
            //   必须 ack —— 否则这条重复消息会被无限重投，每次再撞一次唯一索引。
            log.info("重复订单事件已忽略（幂等命中）: eventId={}, type={}",
                    event == null ? "解析失败" : event.getEventId(),
                    event == null ? "-" : event.getEventType());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("订单事件处理失败，转入死信: payload={}", payload, e);
            // requeue=false → 交给死信队列；requeue=true 会立刻重投形成死循环
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
