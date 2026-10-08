package com.antique.task;

import com.antique.constant.RedisConstant;
import com.antique.mapper.AntiqueMapper;
import com.antique.mapper.DailyStatsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 行为统计落库任务 — M1 行为流水第三段
 *
 * <h3>职责</h3>
 * <p>每 5 分钟把 Redis 里的计数（{@code stats:{antiqueId}:{yyyyMMdd}:{visit|order}}）
 * 聚合后写入 {@code daily_stats} 表，并同步回 {@code antique.view_count} 冗余字段。
 *
 * <h3>关键设计一：日期来自 Key，不来自系统时钟</h3>
 * <p>扫描时<b>不按"今天"过滤</b>，而是扫描全部计数 Key，再从 Key 的第 3 段解析出统计日期。
 * 这样做的原因是一个真实的边界 bug：
 * <pre>
 *   旧写法：scanKeys("stats:*:" + LocalDate.now() + ":*")
 *
 *   23:55  任务扫 stats:*:20260927:*  → 正常落库
 *   23:58  用户浏览 → 产生 stats:1:20260927:visit
 *   00:00  任务再跑，now 已经是 28 号 → 扫 stats:*:20260928:*
 *          → 27 号那个 Key 永远匹配不上，2 天后随 TTL 消失，这次浏览就丢了
 * </pre>
 * <p>根因是"筛选条件"认运行时的日期、"Key 命名"认创建时的日期，过了午夜两者不一致。
 * 改成从 Key 里读日期后，"现在几点"彻底退出了筛选逻辑 ——
 * 无论凌晨跑、还是停机三天后重启再跑，都会按 Key 自带的日期精确归档。
 *
 * <h3>关键设计二：聚合粒度是 (藏品, 日期) 两级</h3>
 * <p>扫描范围放宽后，一个批次里可能同时有昨天和今天的 Key。
 * 如果仍只按 {@code antiqueId} 一级分组，昨天与今天的计数会被合并，
 * 再被迫用同一个日期落库 —— 于是昨天的数据被记到今天头上。
 * 所以聚合键必须是 {@code antiqueId -> 日期 -> 计数}。
 *
 * <h3>关键设计三：取走即清零</h3>
 * <p>用 {@code getAndDelete}，同一个计数 Key 只会被处理一次。
 * 因此扫描范围放宽到"全部 Key"也不会造成重复落库；
 * 数据库侧再由唯一索引 {@code uk_antique_date} + 累加写入兜底，两侧都是幂等的。
 *
 * <h3>关键设计四：用 SCAN 不用 KEYS</h3>
 * <p>{@code KEYS} 会阻塞整个 Redis（单线程），数据量大时是生产事故级写法；
 * {@code SCAN} 是游标式增量遍历，不阻塞。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsFlushTask {

    private final StringRedisTemplate redisTemplate;
    private final DailyStatsMapper dailyStatsMapper;
    private final AntiqueMapper antiqueMapper;

    /** 计数 Key 中的日期格式（必须与 BehaviorConsumer 生成 Key 时保持一致） */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 计数 Key 的访问后缀 */
    private static final String SUFFIX_VISIT = "visit";
    private static final String SUFFIX_ORDER = "order";

    /**
     * 每 5 分钟执行一次
     *
     * <p>cron 表达式见下方注解：秒 = 0，分 = 每 5 分钟，其余字段任意；
     * 即每小时的 0、5、10…分整点触发。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void flush() {
        doFlush();
    }

    /**
     * 执行一次落库（独立成方法，便于手工触发做验收测试）
     *
     * @return 本次落库的统计行数（藏品 × 日期 的组合数）
     */
    public int doFlush() {
        // ① 扫描全部计数 Key（不按"今天"过滤 —— 详见类注释"关键设计一"）
        Set<String> keys = scanKeys(RedisConstant.KEY_STATS_PREFIX + "*");
        if (keys.isEmpty()) {
            log.debug("行为统计落库：暂无待处理计数");
            return 0;
        }

        // ② 聚合：antiqueId -> 统计日期 -> [visit 总数, order 总数]
        Map<Long, Map<LocalDate, int[]>> aggregated = new HashMap<>();
        for (String key : keys) {
            // Key 形如：stats:12:20260926:visit
            String[] parts = key.split(":");
            if (parts.length != 4) {
                continue;
            }

            Long antiqueId;
            LocalDate statDate;
            try {
                antiqueId = Long.parseLong(parts[1]);
                // 日期从 Key 自己身上读，而不是问系统时钟
                statDate = LocalDate.parse(parts[2], DAY_FORMAT);
            } catch (Exception e) {
                log.warn("行为计数 Key 格式异常，已跳过: key={}", key);
                continue;
            }

            // 取走即删除：保证同一份计数只落库一次
            String value = redisTemplate.opsForValue().getAndDelete(key);
            if (value == null) {
                continue;
            }

            int delta;
            try {
                delta = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("行为计数数值异常，已跳过: key={}, value={}", key, value);
                continue;
            }

            // 同一个 (藏品, 日期) 下，visit / order 是两个不同的 Key，在这里合并成一行。
            // 用 += 而不是 = ：当前每个 (藏品,日期,类型) 只对应一个 Key，赋值一次即可；
            // 但写成累加后，将来若把 Key 粒度改细（例如按用户分别计数
            // stats:{userId}:{antiqueId}:{date}:visit），这段聚合逻辑无需改动就能正确汇总。
            int[] slot = aggregated
                    .computeIfAbsent(antiqueId, k -> new HashMap<>())
                    .computeIfAbsent(statDate, k -> new int[2]);
            if (SUFFIX_VISIT.equals(parts[3])) {
                slot[0] += delta;
            } else if (SUFFIX_ORDER.equals(parts[3])) {
                slot[1] += delta;
            }
        }

        // ③ 按 (藏品, 日期) 落库
        int rowCount = 0;
        int visitDelta = 0;
        int orderDelta = 0;
        for (Map.Entry<Long, Map<LocalDate, int[]>> antiqueEntry : aggregated.entrySet()) {
            Long antiqueId = antiqueEntry.getKey();
            for (Map.Entry<LocalDate, int[]> dayEntry : antiqueEntry.getValue().entrySet()) {
                LocalDate statDate = dayEntry.getKey();
                int[] slot = dayEntry.getValue();

                // ① 日统计表：权威数据，供预测服务读取（唯一索引 + 累加，幂等）
                dailyStatsMapper.upsert(antiqueId, statDate, slot[0], slot[1]);

                // ② 藏品表冗余字段：仅用于详情页/列表展示。
                //    跨日补落的浏览量同样要计入总数，所以这里不判断日期
                if (slot[0] > 0) {
                    antiqueMapper.addViewCount(antiqueId, slot[0]);
                }

                rowCount++;
                visitDelta += slot[0];
                orderDelta += slot[1];
            }
        }

        log.info("行为统计落库完成: 统计行数={}, 浏览量增量={}, 下单量增量={}",
                rowCount, visitDelta, orderDelta);
        return rowCount;
    }

    /**
     * 游标式扫描匹配的 Key（不阻塞 Redis）
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }
}
