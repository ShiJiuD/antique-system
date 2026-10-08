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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 藏品行为日统计实体 — 映射数据库 daily_stats 表（M1 行为流水）
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>antiqueId + statDate: 统计维度（同一藏品同一天一行，由唯一索引兜底）</li>
 *   <li>visitCount / orderCount: 当日累计浏览量、下单量</li>
 * </ul>
 *
 * <h3>数据来源</h3>
 * <p>不是业务直接写入，而是定时任务从 Redis 的 {@code stats:*} 计数 Key 聚合后落库；
 * 最终供 algorithm-side 销量预测服务读取"近 7 天每日浏览/订单量"。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("daily_stats")
public class DailyStats implements Serializable {

    /** 主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 藏品 ID */
    private Long antiqueId;

    /** 统计日期 */
    private LocalDate statDate;

    /** 当日浏览量 */
    private Integer visitCount;

    /** 当日下单量 */
    private Integer orderCount;

    /** 创建时间（自动填充） */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（自动填充） */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
