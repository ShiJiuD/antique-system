package com.antique.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 行为事件 — M1 行为流水在 MQ 里传输的消息体
 *
 * <p>以 JSON 字符串形式投递（发送端用 Hutool 序列化），
 * 这样在 RabbitMQ 管理台里能直接看到明文内容，便于学习与排查。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorEvent implements Serializable {

    /** 事件类型：浏览 */
    public static final String TYPE_VISIT = "VISIT";

    /** 事件类型：下单 */
    public static final String TYPE_ORDER = "ORDER";

    /** 事件类型（VISIT / ORDER） */
    private String type;

    /** 用户 ID（游客浏览时为 null） */
    private Long userId;

    /** 藏品 ID */
    private Long antiqueId;

    /** 事件发生时间戳（毫秒） */
    private Long timestamp;
}
