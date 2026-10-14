package com.antique.controller;

import com.antique.result.Result;
import com.antique.service.EsSyncService;
import com.antique.vo.EsSyncResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ES 运维控制器 — M2 搜索
 *
 * <p>路径前缀: /api/es（受 Token 拦截器保护，需登录）
 *
 * <h3>为什么单独开一个运维接口而不是塞进业务接口</h3>
 * <p>同步是<b>运维动作</b>（初始化环境、数据修正时执行），不是用户行为；
 * 放在业务 Controller 里会让人误以为它是业务链路的一部分。
 */
@Slf4j
@RestController
@RequestMapping("/api/es")
@RequiredArgsConstructor
@Tag(name = "ES 运维模块", description = "M2 搜索：MySQL → Elasticsearch 数据同步")
public class EsAdminController {

    private final EsSyncService esSyncService;

    /**
     * 全量同步藏品到 ES（调试/验收用）
     *
     * <p>幂等，可重复调用。返回体带失败明细，便于排查。
     */
    @Operation(summary = "全量同步藏品到 ES")
    @PostMapping("/sync/full")
    public Result<EsSyncResultVO> fullSync() {
        log.info("触发 ES 全量同步");
        EsSyncResultVO result = esSyncService.fullSync();
        return Result.success(result, "同步完成");
    }
}
