package com.antique.task;

import com.antique.entity.Antique;
import com.antique.mapper.AntiqueMapper;
import com.antique.service.EsSyncService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ES 增量兜底同步任务 — M2 搜索第五步
 *
 * <h3>为什么需要它</h3>
 * <p>藏品状态变化时业务代码已经做了实时同步，但仍有两类情况会漏：
 * <ol>
 *   <li><b>批量操作</b>：超时订单的批量回滚一次影响多件藏品，业务代码拿不到具体 ID</li>
 *   <li><b>同步失败</b>：那一刻 ES 不可用（重启中 / 挂了），实时同步失败只留一条警告日志</li>
 * </ol>
 * <p>这个任务就是兜底：每分钟扫一遍 {@code updated_time} 落在上一分钟区间内的藏品，逐条补同步。
 *
 * <p>它依赖 {@code updated_time}，所以 {@code AntiqueMapper} 里的
 * {@code lockAntique / releaseAntique / batchReleaseByUser} 三条原生 SQL 都补上了
 * {@code updated_time = NOW()} —— 原生 {@code @Update} 不经过 MyBatis-Plus 的自动填充，
 * 不补的话这三处变更这个任务永远扫不到。
 *
 * <h3>为什么按时间区间增量扫，而不是全量重灌</h3>
 * <p>103 条数据全量重灌当然也行，但数据量一大就成了"每分钟搬一次全库"。
 * 按 {@code updated_time} 只处理变化的部分；代价是需要在 {@code updated_time} 上建索引
 * （当前 103 条无所谓，生产环境必须加）。
 *
 * <h3>与实时同步的关系</h3>
 * <p>实时同步负责"快"（秒级反映到搜索），本任务负责"不漏"（最多 1 分钟补齐）。
 * 两者都是幂等的（同步用 PUT 覆盖写），重复执行不会出问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsSyncTask {

    private final AntiqueMapper antiqueMapper;
    private final EsSyncService esSyncService;

    /**
     * 上次扫描的截止时间（内存态）
     *
     * <p>放内存而不放 Redis：应用重启后从"当前时间往前 10 分钟"重新开始。
     * 多扫一段没关系（同步幂等），漏扫才是问题。
     */
    private LocalDateTime lastScanEnd = LocalDateTime.now().minusMinutes(10);

    /**
     * 每分钟执行一次，同步上一分钟内发生变化的藏品
     */
    @Scheduled(cron = "0 * * * * ?")
    public void syncChangedAntiques() {
        LocalDateTime scanStart = lastScanEnd;
        LocalDateTime scanEnd = LocalDateTime.now();
        lastScanEnd = scanEnd;

        List<Antique> changed;
        try {
            changed = antiqueMapper.selectList(Wrappers.<Antique>lambdaQuery()
                    .select(Antique::getId)
                    .gt(Antique::getUpdateTime, scanStart)
                    .le(Antique::getUpdateTime, scanEnd)
                    .orderByAsc(Antique::getId));
        } catch (Exception e) {
            // 扫描失败：把时间点退回去，下一分钟重扫同一个区间，
            // 否则这段区间内的变更会被永久跳过
            lastScanEnd = scanStart;
            log.warn("ES 增量兜底：扫描变更藏品失败，下轮重试该区间: {} ~ {}", scanStart, scanEnd, e);
            return;
        }

        if (changed.isEmpty()) {
            return;
        }

        int success = 0;
        for (Antique antique : changed) {
            // syncOne 内部已吞异常并返回 false，单条失败不影响其余藏品
            if (esSyncService.syncOne(antique.getId())) {
                success++;
            }
        }
        log.info("ES 增量兜底同步完成: 区间 {} ~ {}, 发现变更 {} 件, 成功 {} 件",
                scanStart, scanEnd, changed.size(), success);
    }
}
