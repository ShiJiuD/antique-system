package com.antique.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * ES 同步结果 VO — 接口 POST /api/es/sync/full 的返回
 *
 * <p>为什么不只返回一个成功条数：ES 的批量写入是「部分成功」语义 ——
 * HTTP 200 不代表每条都写成功，必须看响应体里的 errors 字段。
 * 所以把失败明细一起带出来，否则数据悄无声息少了都不知道。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EsSyncResultVO implements Serializable {

    /** 从 MySQL 读到的藏品总数 */
    private Integer total;

    /** 成功写入 ES 的条数 */
    private Integer success;

    /** 失败的条数 */
    private Integer failed;

    /** 失败的藏品 ID（便于针对性排查） */
    private List<Long> failedIds;

    /** ES 处理耗时（毫秒，取自响应体的 took） */
    private Long took;
}
