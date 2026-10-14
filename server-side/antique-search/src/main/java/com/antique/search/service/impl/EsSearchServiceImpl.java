package com.antique.search.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.antique.constant.EsConstants;
import com.antique.search.service.EsSearchService;
import com.antique.util.PageUtil;
import com.antique.vo.AntiqueCardVO;
import com.antique.vo.PageResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ES 藏品搜索实现 — 搜索服务
 *
 * <h3>查询结构（对应 ES 的 bool 查询）</h3>
 * <pre>
 *   bool
 *    ├─ must   : multi_match（title^3 + subCategory^2 + dynasty^2 + material^2 + description）
 *    │            —— 参与相关性打分
 *    └─ filter : status=1（在售）+ categoryId（可选）—— 不打分，可被缓存
 *   highlight  : 只高亮 title
 *   sort       : 先按相关性 _score，再按发布时间
 * </pre>
 *
 * <h3>为什么用 must + filter 分开写</h3>
 * <ul>
 *   <li>{@code must} 里的条件<b>参与相关性打分</b>（分数越高排越前）</li>
 *   <li>{@code filter} 里的条件<b>不打分</b>，只做"是 / 否"筛选。
 *       因为不需要算分，ES 可以对 filter 结果做缓存（filter cache），性能明显更好</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsSearchServiceImpl implements EsSearchService {

    /** 本服务唯一的 RestClient Bean（见 EsConfig#esRestClient） */
    private final RestClient esRestClient;

    @Override
    public PageResultVO<AntiqueCardVO> search(String keyword, Long categoryId, Integer page, Integer size) {
        int pageNo = PageUtil.normalizePage(page);
        int pageSize = PageUtil.normalizeSize(size);

        String queryJson = buildQuery(keyword, categoryId, pageNo, pageSize);

        String response = esRestClient.post()
                .uri("/" + EsConstants.ANTIQUE_INDEX + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                // 中文关键词必须走 UTF-8 字节，避免被按其他字符集编码
                .body(queryJson.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(String.class);

        PageResultVO<AntiqueCardVO> result = parseResponse(response, pageNo, pageSize);
        log.info("ES 搜索完成: keyword={}, categoryId={}, 共 {} 条", keyword, categoryId, result.getTotal());
        return result;
    }

    /**
     * 组装 ES 查询 DSL
     */
    private String buildQuery(String keyword, Long categoryId, int page, int size) {
        // ① 多字段检索 + 字段权重
        //    权重设计逻辑：越接近"用户想搜的东西"，权重越高
        //      title^3       —— 标题命中永远最相关
        //      subCategory^2 —— 子类别（青花瓷 / 粉彩 / 斗彩），用户常直接搜这个
        //      dynasty^2     —— 年代（清代 / 民国），"我想找清代的"
        //      material^2    —— 材质（瓷器 / 和田玉），"我想找玉器"
        //      description   —— 描述兜底，权重 1
        //    ⚠️ 这几个字段在 mapping 里都配了 IK 分词器，不放进 fields 就等于索引白建：
        //       实测不加之前，搜「瓷器」命中 0 条（而 material=瓷器 的文档有 22 条）
        JSONObject multiMatch = JSONUtil.createObj()
                .set("query", keyword)
                .set("fields", new String[]{"title^3", "subCategory^2", "dynasty^2", "material^2", "description"})
                .set("type", "best_fields");

        JSONArray mustArray = JSONUtil.createArray();
        mustArray.add(JSONUtil.createObj().set("multi_match", multiMatch));

        // ② 过滤条件：不打分、可缓存
        JSONArray filterArray = JSONUtil.createArray();
        filterArray.add(JSONUtil.createObj().set("term", JSONUtil.createObj().set("status", 1)));
        if (categoryId != null) {
            filterArray.add(JSONUtil.createObj().set("term", JSONUtil.createObj().set("categoryId", categoryId)));
        }

        JSONObject boolQuery = JSONUtil.createObj()
                .set("must", mustArray)
                .set("filter", filterArray);

        // ③ 高亮：只高亮标题（描述太长，全高亮会刷屏）
        JSONObject highlight = JSONUtil.createObj()
                .set("pre_tags", new String[]{"<em>"})
                .set("post_tags", new String[]{"</em>"})
                .set("fields", JSONUtil.createObj().set("title", JSONUtil.createObj()));

        // ④ 排序：先按相关性得分，同分再按发布时间倒序
        JSONArray sortArray = JSONUtil.createArray();
        sortArray.add(JSONUtil.createObj().set("_score", JSONUtil.createObj().set("order", "desc")));
        sortArray.add(JSONUtil.createObj().set("createTime", JSONUtil.createObj().set("order", "desc")));

        return JSONUtil.createObj()
                .set("from", (page - 1) * size)     // 深分页起点
                .set("size", size)
                .set("query", JSONUtil.createObj().set("bool", boolQuery))
                .set("highlight", highlight)
                .set("sort", sortArray)
                .toString();
    }

    @Override
    public List<AntiqueCardVO> findRelated(Long antiqueId, Integer size) {
        if (antiqueId == null) {
            return new ArrayList<>();
        }
        int limit = (size == null || size <= 0) ? 4 : Math.min(size, 20);

        // ① 先按 id 把这件文档取出来，拿到它的分类
        //    注意：主服务不会把分类传过来 —— 那样等于把 ES 的数据结构泄漏给了调用方，
        //    "什么算相关"应该由搜索服务自己定义。
        JSONObject source = getDocumentSource(antiqueId);
        if (source == null) {
            return new ArrayList<>();
        }
        Integer categoryId = source.getInt("categoryId");
        if (categoryId == null) {
            return new ArrayList<>();
        }

        // ② 再查"同分类 + 在售 + 排除自己"
        JSONArray filterArray = JSONUtil.createArray();
        filterArray.add(JSONUtil.createObj().set("term", JSONUtil.createObj().set("status", 1)));
        filterArray.add(JSONUtil.createObj().set("term", JSONUtil.createObj().set("categoryId", categoryId)));

        // 排除自己：must_not 放在 filter 里（不参与打分，也不需要缓存，表达最直白）
        JSONArray mustNotArray = JSONUtil.createArray();
        mustNotArray.add(JSONUtil.createObj().set("term", JSONUtil.createObj().set("id", antiqueId)));
        filterArray.add(JSONUtil.createObj().set("bool", JSONUtil.createObj().set("must_not", mustNotArray)));

        // 相关推荐没有搜索关键词，所以按"热度"排：收藏数优先，其次浏览量
        JSONArray sortArray = JSONUtil.createArray();
        sortArray.add(JSONUtil.createObj().set("likeCount", JSONUtil.createObj().set("order", "desc")));
        sortArray.add(JSONUtil.createObj().set("viewCount", JSONUtil.createObj().set("order", "desc")));

        String body = JSONUtil.createObj()
                .set("size", limit)
                .set("query", JSONUtil.createObj().set("bool", JSONUtil.createObj().set("filter", filterArray)))
                .set("sort", sortArray)
                .toString();

        String response = esRestClient.post()
                .uri("/" + EsConstants.ANTIQUE_INDEX + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(String.class);

        JSONObject root = JSONUtil.parseObj(response);
        List<AntiqueCardVO> related = parseHitList(root.getJSONObject("hits"));
        log.info("相关推荐查询完成: antiqueId={}, categoryId={}, 共 {} 条", antiqueId, categoryId, related.size());
        return related;
    }

    /**
     * 按 id 取单个文档的 {@code _source}
     *
     * <p>取不到（文档尚未同步、已被删除）返回 null，不抛异常 ——
     * 相关推荐是"锦上添花"，拿不到就返回空列表，不该让详情页报错。
     */
    private JSONObject getDocumentSource(Long antiqueId) {
        try {
            String response = esRestClient.get()
                    .uri("/" + EsConstants.ANTIQUE_INDEX + EsConstants.DOC_API + antiqueId)
                    .retrieve()
                    .body(String.class);
            return JSONUtil.parseObj(response).getJSONObject("_source");
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("ES 里没有这个文档（可能尚未同步）: antiqueId={}", antiqueId);
            return null;
        }
    }

    /**
     * 解析 ES 响应 → 分页 VO
     */
    private PageResultVO<AntiqueCardVO> parseResponse(String response, int page, int size) {
        JSONObject root = JSONUtil.parseObj(response);
        JSONObject hits = root.getJSONObject("hits");
        if (hits == null) {
            log.warn("ES 响应结构异常，找不到 hits: {}", response);
            return PageResultVO.<AntiqueCardVO>builder()
                    .list(new ArrayList<>()).total(0L).page(page).size(size).build();
        }

        // ES 7.x 的 total 是对象：{"value": 12, "relation": "eq"}
        JSONObject totalObj = hits.getJSONObject("total");
        long total = totalObj == null ? 0L : totalObj.getLong("value", 0L);

        return PageResultVO.<AntiqueCardVO>builder()
                .list(parseHitList(hits))
                .total(total)
                .page(page)
                .size(size)
                .build();
    }

    /**
     * 把响应里的 {@code hits.hits} 解析成卡片列表
     *
     * <p>搜索和"相关推荐"共用这段解析逻辑，所以抽出来 —— 避免两处各写一份、
     * 将来改字段时只改了一处。
     */
    private List<AntiqueCardVO> parseHitList(JSONObject hits) {
        List<AntiqueCardVO> list = new ArrayList<>();
        if (hits == null) {
            return list;
        }
        JSONArray hitArray = hits.getJSONArray("hits");
        if (hitArray == null) {
            return list;
        }

        for (int i = 0; i < hitArray.size(); i++) {
            JSONObject hit = hitArray.getJSONObject(i);
            JSONObject source = hit.getJSONObject("_source");
            if (source == null) {
                continue;
            }

            AntiqueCardVO vo = AntiqueCardVO.builder()
                    .id(source.getLong("id"))
                    .title(source.getStr("title"))
                    .dynasty(source.getStr("dynasty"))
                    .price(source.getBigDecimal("price"))
                    .coverImage(source.getStr("coverImage"))
                    .isHot(source.getInt("isHot"))
                    .build();

            // 高亮片段：只有标题命中了关键词才会返回，没命中就是 null（前端回退用普通 title）
            JSONObject highlight = hit.getJSONObject("highlight");
            if (highlight != null) {
                JSONArray titleFragments = highlight.getJSONArray("title");
                if (titleFragments != null && !titleFragments.isEmpty()) {
                    vo.setHighlightTitle(titleFragments.getStr(0));
                }
            }
            list.add(vo);
        }
        return list;
    }
}
