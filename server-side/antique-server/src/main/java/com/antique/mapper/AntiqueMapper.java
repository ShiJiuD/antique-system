package com.antique.mapper;

import com.antique.entity.Antique;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 藏品表 Mapper 接口
 * </p>
 *
 * <p>继承 BaseMapper 自动获得单表 CRUD 方法（selectById/selectPage/insert 等），
 * 计数器更新使用 @Update 原子 SQL，避免并发下先查后改造成的计数丢失。
 *
 * @author shijiu
 */
public interface AntiqueMapper extends BaseMapper<Antique> {

    /**
     * 浏览次数 +1（详情接口调用）
     *
     * <p>使用数据库原子自增而非先查后改：
     * <ul>
     *   <li>避免并发请求下读到的旧值互相覆盖</li>
     *   <li>省一次 SELECT 往返</li>
     * </ul>
     *
     * @param id 藏品 ID
     * @return 受影响行数，0 表示藏品不存在
     */
    @Update("UPDATE antique SET view_count = view_count + 1 WHERE id = #{id}")
    int incrementViewCount(Long id);

    /**
     * 浏览次数批量累加（M1 行为流水：定时任务调用）
     *
     * <p>详情页不再每次浏览都写库，改为 MQ + Redis 聚合后每 5 分钟批量回写一次；
     * 这个字段仍是"展示用的冗余计数"，日统计的权威数据在 daily_stats 表。
     *
     * @param id    藏品 ID
     * @param delta 本次累加的浏览量（来自 Redis 聚合结果）
     * @return 受影响行数
     */
    @Update("UPDATE antique SET view_count = view_count + #{delta} WHERE id = #{id}")
    int addViewCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 收藏数 +1（收藏接口调用）
     *
     * <p>like_count 为冗余字段，与 favorite 表写入保持同步。
     *
     * @param id 藏品 ID
     * @return 受影响行数，0 表示藏品不存在
     */
    @Update("UPDATE antique SET like_count = like_count + 1 WHERE id = #{id}")
    int incrementLikeCount(Long id);

    /**
     * 收藏数 -1（取消收藏接口调用）
     *
     * <p>{@code AND like_count > 0} 兜底保护：即使因数据异常导致
     * 收藏表记录与计数不一致，也不会把收藏数减成负数。
     *
     * @param id 藏品 ID
     * @return 受影响行数，0 表示藏品不存在或计数已为 0
     */
    @Update("UPDATE antique SET like_count = like_count - 1 WHERE id = #{id} AND like_count > 0")
    int decrementLikeCount(Long id);

    // ==================== 订单模块：藏品锁定与回滚（防一物多卖） ====================

    /**
     * 锁定藏品（创建订单时调用）
     *
     * <p>状态 1-在售 → 2-已售，{@code AND status = 1} 条件更新即行级锁：
     * 并发下单同一藏品时仅第一个事务成功，后者影响行数 0 直接失败，
     * 从数据库层面杜绝一物多卖。
     *
     * @param id 藏品 ID
     * @return 受影响行数，0 表示藏品已非在售状态（已被锁定或下架）
     */
    @Update("UPDATE antique SET status = 2 WHERE id = #{id} AND status = 1")
    int lockAntique(Long id);

    /**
     * 回滚藏品为在售（订单取消/超时关单时调用，幂等）
     *
     * <p>仅当藏品仍处于锁定态（status=2）且当前没有其他有效订单时才回滚：
     * <ul>
     *   <li>status=2 条件：已被新订单锁定（历史单回滚+新单并存场景）时不误伤</li>
     *   <li>NOT EXISTS 有效订单守卫：同一藏品重新上架后被再次下单，旧单关单不得解锁新单</li>
     * </ul>
     *
     * @param id 藏品 ID
     * @return 受影响行数，0 表示无需回滚（已被并发处理）
     */
    @Update("UPDATE antique SET status = 1 " +
            "WHERE id = #{id} AND status = 2 " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM order_items i INNER JOIN orders o ON o.id = i.order_id " +
            "  WHERE i.antique_id = #{id} AND o.status IN (0, 1, 2) AND o.deleted_time IS NULL)")
    int releaseAntique(Long id);

    /**
     * 惰性批量回滚（用户维度，超时批量取消后调用，幂等）
     *
     * <p>回滚该用户所有超时取消订单涉及的藏品，守卫与 {@link #releaseAntique} 一致
     * （仅回滚锁定态 + 无其他有效订单的藏品），与延迟消息/定时任务并发时安全。
     *
     * @param userId 买家用户 ID
     * @return 受影响行数
     */
    @Update("UPDATE antique SET status = 1 " +
            "WHERE status = 2 AND id IN (" +
            "  SELECT i.antique_id FROM order_items i INNER JOIN orders o ON o.id = i.order_id " +
            "  WHERE o.user_id = #{userId} AND o.status = 4 AND o.cancel_type = 2) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM order_items i INNER JOIN orders o ON o.id = i.order_id " +
            "  WHERE i.antique_id = antique.id AND o.status IN (0, 1, 2) AND o.deleted_time IS NULL)")
    int batchReleaseByUser(Long userId);
}
