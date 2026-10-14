-- ============================================
-- M3 · 订单事件总线：Outbox 本地消息表
-- ============================================
--
-- 【要解决的问题】"下单成功"和"事件发出"必须一致。两种朴素做法都不行：
--   ① 先发 MQ，再提交事务
--      事务回滚了，消息却已经出了 → 通知了一个根本不存在的订单
--   ② 先提交事务，再发 MQ
--      提交后、发消息前进程挂了 → 订单成了，但永远没人知道（静默丢失）
--
-- 【Outbox 的解法】把"要发的消息"当成业务数据，和订单写在同一个事务里：
--   同事务写入 → 要么都成要么都不成（原子性由本地事务保证）
--   再由定时任务把待发送的记录投递到 MQ
--   投递失败不要紧 —— 记录还在表里，下次扫描继续投（至少一次语义）
--   重复投递由消费端的 event_id 唯一索引挡住（幂等）
--
-- 一句话：**用数据库事务保证"业务成功 = 消息一定被记下来"，
--          用定时重投保证"记下来了 = 最终一定发出去"。**

CREATE TABLE IF NOT EXISTS `outbox` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键ID',
    `event_id`        CHAR(32)     NOT NULL                           COMMENT '事件唯一ID（消费端幂等键，32位无横线UUID）',
    `event_type`      VARCHAR(32)  NOT NULL                           COMMENT '事件类型：ORDER_CREATED / ORDER_PAID / ORDER_CLOSED / ORDER_RECEIVED',
    `routing_key`     VARCHAR(64)  NOT NULL                           COMMENT 'MQ 路由键：order.created / order.paid / order.closed / order.received',
    `payload`         JSON         NOT NULL                           COMMENT '事件内容（JSON）',
    `status`          TINYINT      NOT NULL DEFAULT 0                 COMMENT '状态：0=待发送 1=已发送',
    `retry_count`     INT          NOT NULL DEFAULT 0                 COMMENT '已重试次数',
    `next_retry_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可发送时间（投递失败后指数退避）',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),
    -- 同一个事件绝不重复入库（也顺便防止业务代码重复提交同一事件）
    UNIQUE KEY `uk_event_id` (`event_id`),
    -- 定时任务扫描用：WHERE status = 0 AND next_retry_time <= NOW() ORDER BY id LIMIT 100
    -- 联合索引让这个高频查询走索引，而不是全表扫
    KEY `idx_status_next` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='本地消息表（Outbox）：保证业务事务与消息发送的一致性';


-- ============================================
-- M3 · 给 message 表加事件幂等键
-- ============================================
--
-- 【为什么需要】MQ 保证的是"至少一次"投递（at-least-once），同一个事件
--   可能因为重投/消费失败重试而被处理多次。没有这个唯一索引，
--   一次重复投递就会给用户发两条一模一样的站内信。
--   有了它，第二次插入直接抛 DuplicateKeyException → 消费者捕获后正常 ack
--   （注意：重复消息要 ack 掉，否则会无限重投）。
--
-- 【为什么允许 NULL】不是所有消息都来自事件（比如将来的管理端群发通知、
--   系统公告）。MySQL 的唯一索引允许多个 NULL，所以存量数据和非事件消息都不受影响。

ALTER TABLE `message`
    ADD COLUMN `event_id` CHAR(32) NULL COMMENT '来源事件ID（M3 幂等键，NULL=非事件驱动）' AFTER `type`,
    ADD UNIQUE KEY `uk_event_id` (`event_id`);

-- ⚠️ ALTER TABLE 不可重复执行（第二次会报 Duplicate column name）。
--    重复部署时先查一下：SHOW COLUMNS FROM `message` LIKE 'event_id';
