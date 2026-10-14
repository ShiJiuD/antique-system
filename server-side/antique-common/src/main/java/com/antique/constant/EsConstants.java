package com.antique.constant;

/**
 * Elasticsearch 常量 — M2 搜索
 *
 * <h3>为什么单独放一个类</h3>
 * <p>索引名会被「同步服务（写入）」和「搜索服务（查询）」共用，写在一处可以避免两边不一致 ——
 * 那类错误不会编译报错，只会表现为「数据同步进去了但搜不到」，排查很费时间。
 */
public class EsConstants {

    /** 藏品索引名（与 ES 里的索引、以及 server-side/es/antique-index.json 一致） */
    public static final String ANTIQUE_INDEX = "antique";

    /** 同步/查询时的批量大小 */
    public static final int BATCH_SIZE = 500;

    /** ES 批量写入接口 */
    public static final String BULK_API = "/_bulk";

    /** ES 单文档接口前缀（完整路径 = /{索引}/_doc/{id}） */
    public static final String DOC_API = "/_doc/";

    /** _bulk 请求的 Content-Type（NDJSON，不是普通 JSON） */
    public static final String NDJSON_CONTENT_TYPE = "application/x-ndjson";
}
