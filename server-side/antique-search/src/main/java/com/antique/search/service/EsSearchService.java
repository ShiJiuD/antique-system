package com.antique.search.service;

import com.antique.vo.AntiqueCardVO;
import com.antique.vo.PageResultVO;

import java.util.List;

/**
 * ES 藏品搜索服务 — 搜索服务
 *
 * <p>这个接口原先在 {@code antique-server} 里，M5 拆分后搬到了独立的搜索服务。
 * 契约（方法签名、返回类型）完全没变 —— 换的是运行位置，不是接口。
 */
public interface EsSearchService {

    /**
     * 用 Elasticsearch 检索藏品（IK 分词 + 相关性排序 + 关键词高亮）
     *
     * @param keyword    搜索关键词（非空，调用方已校验）
     * @param categoryId 分类筛选（可选，null 表示不限）
     * @param page       页码，从 1 开始
     * @param size       每页条数
     * @return 分页结果（卡片字段 + 高亮标题）
     */
    PageResultVO<AntiqueCardVO> search(String keyword, Long categoryId, Integer page, Integer size);

    /**
     * 查询"相关藏品"（详情页的相关推荐）
     *
     * <p>规则：<b>同分类 + 在售 + 排除自己</b>，按收藏数、浏览量倒序取前 N 条。
     *
     * <p>这个方法存在的意义不只是功能本身 —— 它是主服务<b>必须通过 Feign 调用搜索服务</b>的场景：
     * 判断"哪些藏品相关"要读 ES，而主服务刻意不依赖 ES（M5 拆分的目的之一就是隔离这份依赖）。
     *
     * @param antiqueId 当前藏品 ID
     * @param size      返回条数（传 null 或 ≤0 时用默认值 4）
     * @return 相关藏品卡片列表（查不到返回空列表，不是异常）
     */
    List<AntiqueCardVO> findRelated(Long antiqueId, Integer size);
}
