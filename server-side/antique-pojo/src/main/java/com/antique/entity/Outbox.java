package com.antique.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Outbox 本地消息表实体 — 映射数据库 outbox 表（M3）
 *
 * <h3>它存在的意义</h3>
 * <p>把"要发的事件"当成业务数据落库，<b>和订单业务在同一个事务里</b>。
 * 这样"订单写成功"和"事件被记下来"是原子的 —— 要么都成，要么都不成。
 * 之后由定时任务把待发送的记录投递到 MQ（见 OutboxRelayTask）。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code payload}：事件体的 JSON 字符串。数据库列类型是 JSON，
 *       MyBatis 直接当 String 读写即可（投递时原样发给 MQ，不需要反序列化再序列化）</li>
 *   <li>{@code status}：0=待发送，1=已发送。投递成功后置 1（不删记录，留作审计）</li>
 *   <li>{@code nextRetryTime}：投递失败后指数退避的下次可发时间；
 *       定时任务扫的是 {@code status=0 AND next_retry_time <= NOW()}</li>
 * </ul>
 *
 * <p>注意：createTime / updateTime 交给数据库的 DEFAULT CURRENT_TIMESTAMP 维护
 * （不像 Message 那样用 MyBatis-Plus 自动填充），因为本表是"基础设施表"，
 * 写入路径只有 OutboxService 一处，靠数据库默认值更简单也更可靠。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("outbox")
public class Outbox implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 事件唯一 ID（消费端幂等键），唯一索引 uk_event_id */
    private String eventId;

    /** 事件类型：ORDER_CREATED / ORDER_PAID / ORDER_CLOSED / ORDER_RECEIVED */
    private String eventType;

    /** MQ 路由键：order.created / order.paid / order.closed / order.received */
    private String routingKey;

    /** 事件内容（JSON 字符串） */
    private String payload;

    /** 状态：0=待发送 1=已发送 */
    private Integer status;

    /** 已重试次数（投递失败时 +1，用于指数退避与告警） */
    private Integer retryCount;

    /** 下次可发送时间 */
    private LocalDateTime nextRetryTime;

    /** 创建时间（数据库默认值维护） */
    private LocalDateTime createTime;

    /** 更新时间（数据库默认值维护） */
    private LocalDateTime updateTime;

    /** 状态常量：待发送 */
    public static final int STATUS_PENDING = 0;

    /** 状态常量：已发送 */
    public static final int STATUS_SENT = 1;
}
