package com.antique.mq;

import cn.hutool.core.util.IdUtil;
import com.antique.constant.MQConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单事件 — M3 订单事件总线在 MQ 里传输的消息体
 *
 * <h3>与 M1 的 BehaviorEvent 保持同一套做法</h3>
 * <p>同样以 <b>JSON 字符串</b>形式投递（发送端用 Hutool 序列化），
 * 而不是直接发 Java 对象 —— 因为项目里没有配置
 * {@code Jackson2JsonMessageConverter}，默认的 {@code SimpleMessageConverter}
 * 只认字符串和可序列化对象。发明文 JSON 的好处：
 * <ul>
 *   <li>在 RabbitMQ 管理台里能直接看到内容，排查直观</li>
 *   <li>消费端不受"类型头"影响，将来别的语言写的消费方也能直接读</li>
 * </ul>
 *
 * <h3>eventId 为什么放在消息体里，而不是只放在 MQ 消息属性上</h3>
 * <p>投递时确实也会把它写进 {@code messageId} 属性（方便在管理台按 ID 查消息），
 * 但<b>消费端做幂等用的是消息体里的这个字段</b> —— 这样消息是"自描述"的：
 * 换 RabbitMQ、换中间件、或者被别的系统转发，幂等键都不会丢。
 *
 * <h3>factory 方法统一了"类型 + 路由键"</h3>
 * <p>两者必须一一对应（{@code ORDER_CREATED} ↔ {@code order.created}），
 * 让调用方在同一个地方把它们一起定下来，就不会出现"库里写 ORDER_CREATED、
 * 却发到了 order.paid 路由键"这种极难排查的错误。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent implements Serializable {

    /** 事件唯一 ID（32 位无横线 UUID）—— 消费端据此幂等 */
    private String eventId;

    /** 事件类型：ORDER_CREATED / ORDER_PAID / ORDER_CLOSED / ORDER_RECEIVED */
    private String eventType;

    /** 事件发生时间戳（毫秒）—— 用 long 而不是 LocalDateTime，跨进程传递没有时区与格式歧义 */
    private Long timestamp;

    /** 订单 ID */
    private Long orderId;

    /** 订单号（站内信内容里要显示给用户看） */
    private String orderNo;

    /** 买家用户 ID —— 消息中心据此知道这条站内信该发给谁 */
    private Long userId;

    /** 订单金额（可为 null；部分事件类型用不到） */
    private BigDecimal amount;

    // ==================== 工厂方法 ====================

    /** 订单创建 */
    public static OrderEvent created(Long orderId, String orderNo, Long userId, BigDecimal amount) {
        return build(MQConstants.EVENT_ORDER_CREATED, orderId, orderNo, userId, amount);
    }

    /** 订单支付成功 */
    public static OrderEvent paid(Long orderId, String orderNo, Long userId, BigDecimal amount) {
        return build(MQConstants.EVENT_ORDER_PAID, orderId, orderNo, userId, amount);
    }

    /** 订单关闭（用户取消 / 超时未支付，语义相同） */
    public static OrderEvent closed(Long orderId, String orderNo, Long userId, BigDecimal amount) {
        return build(MQConstants.EVENT_ORDER_CLOSED, orderId, orderNo, userId, amount);
    }

    /** 订单收货 */
    public static OrderEvent received(Long orderId, String orderNo, Long userId, BigDecimal amount) {
        return build(MQConstants.EVENT_ORDER_RECEIVED, orderId, orderNo, userId, amount);
    }

    /**
     * 按事件类型取对应的 MQ 路由键
     *
     * <p>放在事件对象上而不是调用方各处 switch：路由键是"这个事件该往哪发"的属性，
     * 属于事件自己的一部分。
     */
    public String routingKey() {
        if (MQConstants.EVENT_ORDER_CREATED.equals(eventType)) {
            return MQConstants.ORDER_CREATED_KEY;
        }
        if (MQConstants.EVENT_ORDER_PAID.equals(eventType)) {
            return MQConstants.ORDER_PAID_KEY;
        }
        if (MQConstants.EVENT_ORDER_CLOSED.equals(eventType)) {
            return MQConstants.ORDER_CLOSED_KEY;
        }
        if (MQConstants.EVENT_ORDER_RECEIVED.equals(eventType)) {
            return MQConstants.ORDER_RECEIVED_KEY;
        }
        throw new IllegalArgumentException("未知的订单事件类型: " + eventType);
    }

    private static OrderEvent build(String eventType, Long orderId, String orderNo,
                                    Long userId, BigDecimal amount) {
        return OrderEvent.builder()
                // 事件 ID 在这里生成，保证"一个事件一个 ID，且只生成一次"
                .eventId(IdUtil.fastSimpleUUID())
                .eventType(eventType)
                .timestamp(System.currentTimeMillis())
                .orderId(orderId)
                .orderNo(orderNo)
                .userId(userId)
                .amount(amount)
                .build();
    }
}
