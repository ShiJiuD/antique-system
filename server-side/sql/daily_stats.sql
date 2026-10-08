-- ============================================
-- 古玩寄卖平台 — 藏品行为日统计表（M1 行为流水）
-- ============================================
-- 用途：
--   1) 替代原来每次打开详情页就 UPDATE antique.view_count 的热点行写
--   2) 给 algorithm-side 销量预测服务提供真实数据源（近 7 天每日浏览/下单量）
--
-- 链路：浏览/下单埋点 → RabbitMQ → Redis 聚合计数 → 定时任务落库到本表
-- ============================================

CREATE TABLE IF NOT EXISTS `daily_stats` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `antique_id`  BIGINT UNSIGNED NOT NULL              COMMENT '藏品ID（关联 antique.id）',
    `stat_date`   DATE            NOT NULL              COMMENT '统计日期',
    `visit_count` INT             NOT NULL DEFAULT 0    COMMENT '当日浏览量',
    `order_count` INT             NOT NULL DEFAULT 0    COMMENT '当日下单量',
    `create_time` DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),

    -- 唯一索引一举两得：
    --   ① 幂等兜底：同一藏品同一天只能有一行。定时任务用
    --      INSERT ... ON DUPLICATE KEY UPDATE 累加，重复/并发执行都不会重复计数。
    --   ② 供查询使用：预测服务查「某藏品近 7 天」= WHERE antique_id=? AND stat_date BETWEEN ? AND ?，
    --      唯一索引本身就是完整的 B+ 树，这类范围查询直接走它。
    --
    --   ⚠️ 不要再建同字段的普通索引（如 KEY idx_antique_date(antique_id, stat_date)）：
    --      纯冗余 —— 索引体积翻倍、每次写入多维护一棵树、而且查询根本用不到它。
    UNIQUE KEY `uk_antique_date` (`antique_id`, `stat_date`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='藏品行为日统计（浏览量/下单量，预测服务数据源）';
