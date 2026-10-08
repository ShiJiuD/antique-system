package com.antique.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 行为统计 + 销量预测 VO — 接口：GET /api/stats/daily
 *
 * <p>前半部分是本平台自己的统计（daily_stats 表近 N 天），
 * 后半部分是转发给 algorithm-side 预测服务得到的未来 N 天预测值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyStatsVO implements Serializable {

    /** 藏品 ID */
    private Long antiqueId;

    /** 日期序列（yyyy-MM-dd，升序，长度为 days） */
    private List<String> dates;

    /** 历史每日浏览量（与 dates 等长，无数据的日期补 0） */
    private List<Integer> historyVisit;

    /** 历史每日下单量（与 dates 等长，无数据的日期补 0） */
    private List<Integer> historyOrder;

    /** 预测的未来每日浏览量（预测服务不可用时为 null） */
    private List<Integer> predictVisit;

    /** 预测的未来每日下单量（预测服务不可用时为 null） */
    private List<Integer> predictOrder;

    /** 预测是否可用（algorithm-side 是否成功返回） */
    private Boolean predictAvailable;
}
