package com.antique.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.antique.constant.EsConstants;
import com.antique.constant.MessageConstant;
import com.antique.entity.Antique;
import com.antique.exception.AuthException;
import com.antique.mapper.AntiqueMapper;
import com.antique.service.EsSyncService;
import com.antique.vo.EsSyncResultVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ES 数据同步实现 — M2 第二步：全量灌数据
 *
 * <h3>数据流向</h3>
 * <pre>
 *   MySQL.antique（权威数据源）
 *        │  按 id 升序分页读取
 *        ▼
 *   组装 NDJSON ──▶ POST /_bulk ──▶ ES.antique 索引
 * </pre>
 *
 * <h3>为什么走 _bulk 而不是一条条 PUT</h3>
 * <p>{@code _bulk} 把 N 条写入合并成 <b>1 次 HTTP 请求</b>：60 条数据从 60 次往返降到 1 次，
 * 网络开销和 ES 的协调开销都大幅下降；数据量上千时差距更明显。
 *
 * <h3>NDJSON 格式</h3>
 * <p>{@code _bulk} 的请求体不是普通 JSON，而是"每行一个 JSON"的 NDJSON，
 * 且每条文档占两行：<b>动作行 + 文档行</b>：
 * <pre>
 *   {"index":{"_index":"antique","_id":"1"}}
 *   {"id":1,"title":"青花瓷瓶","price":12000.00,...}
 * </pre>
 * 用 {@code index} 动作（而不是 {@code create}）是有意的：{@code index} 的语义是
 * "存在就覆盖、不存在就插入"，所以全量同步天然幂等，重复执行不会报错。
 *
 * <h3>为什么用 byte[] 发送而不是 String</h3>
 * <p>StringHttpMessageConverter 的默认字符集不是我们能完全控制的，
 * 中文藏品名一旦被按非 UTF-8 编码，ES 里就会存进乱码（而且不报错）。
 * 直接传 {@code byte[]} 并显式指定 UTF-8 最稳。
 *
 * <h3>为什么必须检查响应体的 errors</h3>
 * <p>{@code _bulk} 是<b>部分成功</b>语义：哪怕有文档写失败，HTTP 状态码依然是 200。
 * 只判断"请求没抛异常"会把失败当成成功，所以逐个检查 items 里每个动作的 status。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsSyncServiceImpl implements EsSyncService {

    private final AntiqueMapper antiqueMapper;

    /** 项目里唯一的 RestClient Bean（见 EsConfig#esRestClient） */
    private final RestClient esRestClient;

    /** 时间格式必须与索引 mapping 里声明的 format 一致 */
    private static final DateTimeFormatter ES_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public EsSyncResultVO fullSync() {
        // ----- 步骤 0：前置检查，索引必须已按 json 定义创建 -----
        assertIndexExists();

        int total = 0;
        int success = 0;
        int failed = 0;
        long took = 0;
        List<Long> failedIds = new ArrayList<>();

        long current = 1;
        while (true) {
            // 必须指定排序：不排序时分页可能漏数据或重复数据
            Page<Antique> pageParam = new Page<>(current, EsConstants.BATCH_SIZE);
            LambdaQueryWrapper<Antique> wrapper = Wrappers.<Antique>lambdaQuery()
                    .orderByAsc(Antique::getId);
            Page<Antique> pageResult = antiqueMapper.selectPage(pageParam, wrapper);

            List<Antique> records = pageResult.getRecords();
            if (records.isEmpty()) {
                break;
            }

            total += records.size();
            BulkResult bulk = bulkIndex(records);
            success += bulk.success;
            failed += bulk.failed;
            took += bulk.took;
            failedIds.addAll(bulk.failedIds);

            log.info("ES 全量同步进度: 第 {}/{} 页, 本批 {} 条, 成功 {} 条",
                    current, pageResult.getPages(), records.size(), bulk.success);

            if (current >= pageResult.getPages()) {
                break;
            }
            current++;
        }

        log.info("ES 全量同步完成: 总数={}, 成功={}, 失败={}, ES耗时={}ms", total, success, failed, took);
        return EsSyncResultVO.builder()
                .total(total)
                .success(success)
                .failed(failed)
                .failedIds(failedIds)
                .took(took)
                .build();
    }

    @Override
    public boolean syncOne(Long antiqueId) {
        if (antiqueId == null) {
            return false;
        }
        try {
            Antique antique = antiqueMapper.selectById(antiqueId);
            if (antique == null) {
                // 已被逻辑删除（MyBatis-Plus 自动过滤查不到）→ 从索引里移除，
                // 否则它会一直留在搜索结果里，变成一个点进去 404 的幽灵条目
                deleteDocument(antiqueId);
                return true;
            }
            indexDocument(antique);
            log.info("ES 单条同步成功: antiqueId={}, status={}", antiqueId, antique.getStatus());
            return true;
        } catch (Exception e) {
            log.error("ES 单条同步失败: antiqueId={}", antiqueId, e);
            return false;
        }
    }

    /**
     * 写入 / 覆盖单个文档
     *
     * <p>用 PUT {@code /{索引}/_doc/{id}}：文档已存在则整体覆盖，不存在则新建 ——
     * 天然幂等，重复同步同一件藏品不会产生重复文档。
     */
    private void indexDocument(Antique antique) {
        byte[] body = buildDocument(antique).getBytes(StandardCharsets.UTF_8);
        esRestClient.put()
                .uri("/" + EsConstants.ANTIQUE_INDEX + EsConstants.DOC_API + antique.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 从索引里删除文档（藏品被逻辑删除时调用）
     */
    private void deleteDocument(Long antiqueId) {
        try {
            esRestClient.delete()
                    .uri("/" + EsConstants.ANTIQUE_INDEX + EsConstants.DOC_API + antiqueId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("ES 文档已删除（藏品已逻辑删除）: antiqueId={}", antiqueId);
        } catch (HttpClientErrorException.NotFound e) {
            // 文档本来就不存在 —— 删除的目的已经达成，不算失败
            log.debug("ES 文档本就不存在，无需删除: antiqueId={}", antiqueId);
        }
    }

    /**
     * 前置检查：索引是否存在
     *
     * <p>ES 默认会自动创建索引（{@code action.auto_create_index=true}）。
     * 如果这里不检查，ES 会用「动态推断」的 mapping 把索引悄悄建出来 ——
     * 中文被切成单字、字段类型未必正确，<b>而且完全不报错</b>，
     * 属于最难排查的一类静默错误。宁可这里明确失败。
     */
    private void assertIndexExists() {
        Boolean exists = esRestClient.head()
                .uri("/" + EsConstants.ANTIQUE_INDEX)
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
        if (!Boolean.TRUE.equals(exists)) {
            // 注：项目里没有专门的 BusinessException，AuthException 是现有的通用业务异常
            //（搜索关键词为空也是抛它），这里沿用同一套，由 GlobalExceptionHandler 统一转成 Result
            throw new AuthException(MessageConstant.ES_INDEX_NOT_EXISTS);
        }
    }

    /**
     * 把一批藏品通过 _bulk 写入 ES
     */
    private BulkResult bulkIndex(List<Antique> records) {
        // ① 组装 NDJSON：每条两行（动作行 + 文档行）
        StringBuilder ndjson = new StringBuilder();
        for (Antique antique : records) {
            ndjson.append("{\"index\":{\"_index\":\"").append(EsConstants.ANTIQUE_INDEX)
                    .append("\",\"_id\":\"").append(antique.getId()).append("\"}}\n");
            ndjson.append(buildDocument(antique)).append('\n');
        }

        // ② 显式用 UTF-8 字节发送（见类注释"为什么用 byte[]"）
        byte[] body = ndjson.toString().getBytes(StandardCharsets.UTF_8);

        String response = esRestClient.post()
                .uri(EsConstants.BULK_API)
                .contentType(MediaType.parseMediaType(EsConstants.NDJSON_CONTENT_TYPE))
                .body(body)
                .retrieve()
                .body(String.class);

        return parseBulkResponse(response, records);
    }

    /**
     * 藏品实体 → ES 文档 JSON（字段名与索引 mapping 一一对应）
     */
    private String buildDocument(Antique antique) {
        JSONObject doc = JSONUtil.createObj();
        doc.set("id", antique.getId());
        doc.set("title", antique.getTitle());
        doc.set("description", antique.getDescription());
        doc.set("categoryId", antique.getCategoryId());
        doc.set("subCategory", antique.getSubCategory());
        doc.set("dynasty", antique.getDynasty());
        doc.set("material", antique.getMaterial());
        doc.set("sellerId", antique.getSellerId());
        doc.set("sellerName", antique.getSellerName());
        doc.set("price", antique.getPrice());
        doc.set("coverImage", antique.getCoverImage());
        doc.set("viewCount", antique.getViewCount());
        doc.set("likeCount", antique.getLikeCount());
        doc.set("isHot", antique.getIsHot());
        doc.set("status", antique.getStatus());
        doc.set("createTime", formatTime(antique.getCreateTime()));
        doc.set("updateTime", formatTime(antique.getUpdateTime()));
        // Hutool 默认忽略 null 值，字段缺失对 ES 是安全的（该字段不可检索而已）
        return JSONUtil.toJsonStr(doc);
    }

    /**
     * 解析 _bulk 响应：逐条判断 status，不能只看 HTTP 200
     */
    private BulkResult parseBulkResponse(String response, List<Antique> records) {
        BulkResult result = new BulkResult();

        if (StrUtil.isBlank(response)) {
            markAllFailed(result, records, "ES 返回空响应");
            return result;
        }

        JSONObject root = JSONUtil.parseObj(response);
        result.took = root.getLong("took", 0L);

        JSONArray items = root.getJSONArray("items");
        if (items == null) {
            markAllFailed(result, records, "响应里没有 items 字段");
            return result;
        }

        for (int i = 0; i < items.size(); i++) {
            JSONObject action = items.getJSONObject(i);
            if (action == null) {
                continue;
            }
            JSONObject detail = action.getJSONObject("index");
            if (detail == null) {
                continue;
            }
            int status = detail.getInt("status", 0);
            if (status >= 200 && status < 300) {
                result.success++;
            } else {
                result.failed++;
                result.failedIds.add(detail.getLong("_id"));
                log.error("ES 写入失败: _id={}, status={}, error={}",
                        detail.getStr("_id"), status, detail.get("error"));
            }
        }
        return result;
    }

    private void markAllFailed(BulkResult result, List<Antique> records, String reason) {
        log.error("ES 批量写入异常: {}，本批 {} 条全部记为失败", reason, records.size());
        result.failed = records.size();
        for (Antique antique : records) {
            result.failedIds.add(antique.getId());
        }
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(ES_TIME_FORMAT);
    }

    /**
     * 单批同步结果（仅本类内部使用）
     */
    private static class BulkResult {
        private int success;
        private int failed;
        private long took;
        private final List<Long> failedIds = new ArrayList<>();
    }
}
