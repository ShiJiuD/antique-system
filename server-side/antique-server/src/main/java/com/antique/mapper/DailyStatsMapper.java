package com.antique.mapper;

import com.antique.entity.DailyStats;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 藏品行为日统计 Mapper（M1 行为流水）
 *
 * @author shijiu
 */
public interface DailyStatsMapper extends BaseMapper<DailyStats> {

    /**
     * 累加写入当日统计（幂等）
     *
     * <p>同一藏品同一天会反复落库（定时任务每 5 分钟跑一次），所以必须"累加"而不是覆盖：
     * {@code ON DUPLICATE KEY UPDATE} 命中唯一索引 {@code uk_antique_date} 时执行加法，
     * 未命中时插入新行 —— 这一条 SQL 同时覆盖"当天第一次"和"当天后续"两种情况，
     * 并发执行也不会重复计数。
     *
     * <p>注：{@code VALUES(col)} 写法在 MySQL 8.0.20+ 标记为 deprecated（仍可用），
     * 新语法为 {@code ... VALUES (...) AS new ON DUPLICATE KEY UPDATE col = col + new.col}。
     *
     * @param antiqueId  藏品 ID
     * @param statDate   统计日期
     * @param visitCount 本次要累加的浏览量
     * @param orderCount 本次要累加的下单量
     * @return 受影响行数（插入为 1，更新为 2，MySQL 语义如此）
     */
    @Insert("INSERT INTO daily_stats (antique_id, stat_date, visit_count, order_count) " +
            "VALUES (#{antiqueId}, #{statDate}, #{visitCount}, #{orderCount}) " +
            "ON DUPLICATE KEY UPDATE " +
            "  visit_count = visit_count + VALUES(visit_count), " +
            "  order_count = order_count + VALUES(order_count)")
    int upsert(@Param("antiqueId") Long antiqueId,
               @Param("statDate") LocalDate statDate,
               @Param("visitCount") int visitCount,
               @Param("orderCount") int orderCount);

    /**
     * 查询某藏品从 startDate（含）起的每日统计，按日期升序
     *
     * <p>预测服务需要"近 7 天逐日"数组，所以这里按日期排序返回，由上层补零对齐。
     *
     * @param antiqueId 藏品 ID
     * @param startDate 起始日期（含）
     * @return 逐日统计列表
     */
    @Select("SELECT stat_date, visit_count, order_count FROM daily_stats " +
            "WHERE antique_id = #{antiqueId} AND stat_date >= #{startDate} " +
            "ORDER BY stat_date ASC")
    List<DailyStats> selectFrom(@Param("antiqueId") Long antiqueId,
                                @Param("startDate") LocalDate startDate);
}
