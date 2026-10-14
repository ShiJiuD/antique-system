package com.antique.mapper;

import com.antique.entity.Outbox;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Outbox 本地消息表 Mapper（M3）
 *
 * <h3>为什么这几个方法用注解写 SQL 而不是 MyBatis-Plus 的 Wrapper</h3>
 * <p>它们都是"基础设施操作"（扫表、改状态、退避），条件里带数据库函数
 * （{@code NOW()}、{@code DATE_ADD}），用注解 SQL 更直观，
 * 也让"定时任务到底查了什么"一眼可见。
 *
 * <h3>⚠️ 时间比较一律用数据库的 NOW()</h3>
 * <p>不要用 Java 传进来的 {@code LocalDateTime.now()}：
 * 应用服务器和数据库的时钟可能有偏差，而扫描条件
 * {@code next_retry_time <= NOW()} 的语义是"按数据库的时间看该发了"，
 * 用数据库时间才不会出现"应用以为还没到、数据库早该发了"这类问题。
 * （M2 里 {@code PayTimeoutTask} 的条件更新用的也是 DB 时间，口径一致。）
 */
@Mapper
public interface OutboxMapper extends BaseMapper<Outbox> {

    /**
     * 取一批待投递的事件
     *
     * <p>{@code status = 0}（待发送）且 {@code next_retry_time <= NOW()}（到了可发时间）。
     * 按 id 升序 = 先产生的事件先发，保证同一订单的事件顺序大致正确
     * （created 早于 paid）。
     *
     * <p>用 {@code idx_status_next} 联合索引，不走全表扫。
     *
     * @param limit 单批上限，避免一次性把大量积压全部加载进内存
     */
    @Select("SELECT * FROM outbox "
            + "WHERE status = 0 AND next_retry_time <= NOW() "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<Outbox> selectPending(@Param("limit") int limit);

    /**
     * 标记为已发送
     *
     * <p>不删记录：留着可以审计"这个事件是什么时候发出去的"，
     * 排查问题时比删掉有用得多。数据量大时再考虑归档。
     */
    @Update("UPDATE outbox SET status = 1 WHERE id = #{id}")
    int markSent(@Param("id") Long id);

    /**
     * 投递失败后的指数退避
     *
     * <p>退避秒数由 Java 侧算好传进来（{@code 2^retryCount}，封顶 5 分钟），
     * 避免在 SQL 里写幂运算 —— 那种表达式难读、也不好在单元测试里验证。
     *
     * <p>只改这两个字段，<b>不改 status</b>：这条事件仍然是"待发送"，
     * 只是要到 {@code next_retry_time} 之后才会被再次扫到。
     */
    @Update("UPDATE outbox SET retry_count = retry_count + 1, "
            + "next_retry_time = DATE_ADD(NOW(), INTERVAL #{delaySeconds} SECOND) "
            + "WHERE id = #{id}")
    int backoff(@Param("id") Long id, @Param("delaySeconds") int delaySeconds);

    /**
     * 统计待发送数量（运维/监控用：积压持续增长说明 MQ 或消费端有问题）
     */
    @Select("SELECT COUNT(*) FROM outbox WHERE status = 0")
    long countPending();
}
