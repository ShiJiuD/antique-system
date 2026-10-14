package com.antique.service;

import com.antique.vo.EsSyncResultVO;

/**
 * ES 数据同步服务 — M2 搜索
 *
 * @author shijiu
 */
public interface EsSyncService {

    /**
     * 全量同步：把 MySQL 的藏品灌进 ES 的 antique 索引
     *
     * <p>可重复执行（幂等）：文档 _id 用藏品主键，重复同步等于覆盖更新，
     * 所以验收时可以放心多调几次。
     *
     * @return 同步结果（总数 / 成功数 / 失败明细）
     */
    EsSyncResultVO fullSync();

    /**
     * 单条同步：把某件藏品的当前状态写进 ES（增量同步用）
     *
     * <p>调用场景：藏品状态变化时（下单锁定 → 已售、取消 / 超时回滚 → 在售），
     * 必须把变化同步到索引，否则搜索结果里"仅在售"的过滤条件会失真 ——
     * 用户会搜到一件其实已经卖掉的藏品。
     *
     * <p>若藏品已被逻辑删除，则把对应文档从索引中移除。
     *
     * @param antiqueId 藏品 ID
     * @return true = 同步成功；false = 失败（调用方可以忽略，定时兜底任务会补上）
     */
    boolean syncOne(Long antiqueId);
}
