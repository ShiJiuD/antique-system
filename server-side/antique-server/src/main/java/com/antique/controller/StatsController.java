package com.antique.controller;

import com.antique.constant.MessageConstant;
import com.antique.result.Result;
import com.antique.service.StatsService;
import com.antique.task.StatsFlushTask;
import com.antique.vo.DailyStatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行为统计控制器 — M1 行为流水
 *
 * <p>路径前缀: /api/stats（受 Token 拦截器保护，需登录）
 *
 * <h3>接口列表</h3>
 * <table>
 *   <tr><th>接口</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>日统计 + 预测</td><td>GET /api/stats/daily</td><td>近 N 天统计，转发 algorithm-side 出未来 N 天</td></tr>
 *   <tr><td>手工触发落库</td><td>POST /api/stats/flush</td><td>验收调试用：不用等 5 分钟定时任务</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
@Tag(name = "行为统计模块", description = "M1 行为流水：日统计查询与销量预测")
public class StatsController {

    private final StatsService statsService;
    private final StatsFlushTask statsFlushTask;

    /**
     * 接口 1：查询藏品行为统计 + 未来预测
     *
     * @param antiqueId 藏品 ID
     * @param days      天数（默认 7，最大 30）
     */
    @Operation(summary = "查询藏品日统计与销量预测")
    @GetMapping("/daily")
    public Result<DailyStatsVO> daily(@RequestParam Long antiqueId,
                                      @RequestParam(defaultValue = "7") Integer days) {
        log.info("行为统计查询请求: antiqueId={}, days={}", antiqueId, days);
        DailyStatsVO data = statsService.getDailyWithPrediction(antiqueId, days);
        return Result.success(data, MessageConstant.QUERY_SUCCESS);
    }

    /**
     * 接口 2：手工触发统计落库（验收/调试用）
     *
     * <p>正常由 {@code StatsFlushTask} 每 5 分钟自动执行；这个接口让你在验收时
     * 立刻把 Redis 计数刷进 daily_stats，不用干等定时任务。
     *
     * @return 本次落库的统计行数（藏品 × 日期 的组合数）
     */
    @Operation(summary = "手工触发行为统计落库（调试用）")
    @PostMapping("/flush")
    public Result<Integer> flush() {
        int count = statsFlushTask.doFlush();
        log.info("手工触发行为统计落库, 落库统计行数={}", count);
        return Result.success(count);
    }
}
