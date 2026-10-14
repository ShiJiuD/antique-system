package com.antique.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息表实体 — 映射数据库 message 表
 *
 * <p>实体类 = 数据库表的"Java 形状"：一个属性对应一列。
 * MyBatis-Plus 靠注解知道：类对应哪张表、哪个字段是主键、哪些字段自动填时间。
 * 字段名自动驼峰转下划线（userId ↔ user_id），不用逐个写 @TableField。
 *
 * <p>type: 1-系统通知，2-订单消息，3-AI助手（预留）
 * <p>deletedTime 非 NULL 即已删除：MyBatis-Plus 全局配置了逻辑删除，
 * 查询时自动加 deleted_time IS NULL，业务代码无感知。
 */
// Lombok 注解：自动生成 getter/setter；@Accessors(chain=true) 支持链式 set（message.setTitle("x").setContent("y")）
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
// 告诉 MyBatis-Plus：这个类 ↔ 数据库 message 表
@TableName("message")
public class Message implements Serializable {

    /** 主键，数据库自增（插入后 MyBatis-Plus 自动回填到 id 属性） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 接收者用户 ID（这条消息发给谁） */
    private Long userId;

    /** 消息类型：1-系统通知，2-订单消息，3-AI助手（预留） */
    private Integer type;

    /**
     * 来源事件 ID（M3 幂等键）
     *
     * <p>数据库上有唯一索引 {@code uk_event_id}。MQ 是"至少一次"投递，
     * 同一个订单事件可能被消费多次；靠这个字段，第二次插入会抛
     * {@code DuplicateKeyException}，消费者捕获后正常 ack，用户不会收到两条一样的站内信。
     *
     * <p>为 NULL 表示这条消息不是事件驱动的（如系统公告），
     * MySQL 唯一索引允许多个 NULL，所以不受影响。
     */
    private String eventId;

    /** 标题（前端分组展示，如"订单消息"） */
    private String title;

    /** 消息内容（"您的订单 xxx 已创建成功，请尽快完成支付"） */
    private String content;

    /** 是否已读：0-未读，1-已读（前端未读角标按它统计） */
    private Integer isRead;

    /** 已读时间 */
    private LocalDateTime readTime;

    // fill = 插入时自动填当前时间：由 MyMetaObjectHandler 统一处理，代码里不用手动 set
    @TableField(value = "created_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    // fill = 插入和更新时都自动填
    @TableField(value = "updated_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除时间，NULL=未删除（MyBatis-Plus 自动过滤） */
    private LocalDateTime deletedTime;
}
