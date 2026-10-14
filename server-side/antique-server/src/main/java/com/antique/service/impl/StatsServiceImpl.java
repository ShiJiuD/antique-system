package com.antique.service.impl;

import com.antique.client.AlgorithmFeignClient;
import com.antique.entity.DailyStats;
import com.antique.mapper.DailyStatsMapper;
import com.antique.service.StatsService;
import com.antique.vo.DailyStatsVO;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 行为统计服务实现 — 打通「统计表 → 预测服务」的最后一环
 *
 * <h3>链路</h3>
 * <pre>
 *   daily_stats 表（近 7 天逐日浏览/订单）
 *        │  组装成两条等长数组
 *        ▼
 *   algorithm-side  POST /predict   （独立 Python 服务，127.0.0.1:8002）
 *        │  返回未来 7 天逐日预测
 *        ▼
 *   DailyStatsVO 一起返回给前端/运营
 * </pre>
 *
 * <h3>M5 改造：RestClient → OpenFeign</h3>
 * <p>超时从本类的 {@code @PostConstruct} 搬到 application.yml：
 * <pre>
 *   spring.cloud.openfeign.client.config.algorithm-service.readTimeout: 3000
 * </pre>
 *
 * <h3>降级策略</h3>
 * <p>预测服务没启动、超时或返回异常时，只记警告并返回 {@code predictAvailable:false} ——
 * 统计查询本身照常可用。与 ai-side 的处理方式一致：<b>外部服务故障不影响主流程</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl implements StatsService {

    private final DailyStatsMapper dailyStatsMapper;

    /** M5：Feign 客户端（本地预测服务，3 秒超时） */
    private final AlgorithmFeignClient algorithmFeignClient;

    @Override
    public DailyStatsVO getDailyWithPrediction(Long antiqueId, int days) {
        int n = Math.min(Math.max(days, 1), 30);
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(n - 1L);

        // ===== ① 查历史统计，并补齐缺失日期（没数据的日期补 0，保证数组等长）=====
        List<DailyStats> rows = dailyStatsMapper.selectFrom(antiqueId, start);
        Map<LocalDate, DailyStats> rowMap = rows.stream()
                .collect(Collectors.toMap(DailyStats::getStatDate, Function.identity(), (a, b) -> a));

        List<String> dates = new ArrayList<>(n);
        List<Integer> historyVisit = new ArrayList<>(n);
        List<Integer> historyOrder = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate date = start.plusDays(i);
            DailyStats row = rowMap.get(date);
            dates.add(date.toString());
            historyVisit.add(row == null || row.getVisitCount() == null ? 0 : row.getVisitCount());
            historyOrder.add(row == null || row.getOrderCount() == null ? 0 : row.getOrderCount());
        }

        // ===== ② 调预测服务（失败降级）=====
        List<Integer> predictVisit = null;
        List<Integer> predictOrder = null;
        boolean predictAvailable = false;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("antiqueId", antiqueId);
            body.put("historyVisit", historyVisit);
            body.put("historyOrder", historyOrder);

            JsonNode resp = algorithmFeignClient.predict(body);

            if (resp != null && resp.path("code").asInt() == 1) {
                predictVisit = toIntList(resp.path("data").path("predict_visit"));
                predictOrder = toIntList(resp.path("data").path("predict_order"));
                predictAvailable = true;
            } else {
                log.warn("预测服务返回异常: antiqueId={}, resp={}", antiqueId, resp);
            }
        } catch (Exception e) {
            // 预测服务未启动 / 超时 / Feign 异常 → 降级，只返回历史统计
            log.warn("调用预测服务失败（已降级，仅返回历史统计）: antiqueId={}, error={}", antiqueId, e.getMessage());
        }

        log.info("行为统计查询完成: antiqueId={}, days={}, 预测可用={}", antiqueId, n, predictAvailable);
        return DailyStatsVO.builder()
                .antiqueId(antiqueId)
                .dates(dates)
                .historyVisit(historyVisit)
                .historyOrder(historyOrder)
                .predictVisit(predictVisit)
                .predictOrder(predictOrder)
                .predictAvailable(predictAvailable)
                .build();
    }

    /**
     * JSON 数组 → List&lt;Integer&gt;
     */
    private List<Integer> toIntList(JsonNode arrayNode) {
        List<Integer> list = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode node : arrayNode) {
                list.add(node.asInt());
            }
        }
        return list;
    }
}
