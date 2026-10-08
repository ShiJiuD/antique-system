package com.antique.service;

import com.antique.vo.DailyStatsVO;

/**
 * 行为统计服务 — M1 行为流水
 *
 * @author shijiu
 */
public interface StatsService {

    /**
     * 查询藏品近 N 天的行为统计，并附带未来 N 天预测
     *
     * <p>预测来自 algorithm-side（独立 Python 服务）；服务不可用时降级为
     * 只返回历史统计（predictAvailable=false），不影响主业务。
     *
     * @param antiqueId 藏品 ID
     * @param days      天数（1~30，默认 7）
     * @return 历史统计 + 预测结果
     */
    DailyStatsVO getDailyWithPrediction(Long antiqueId, int days);
}
