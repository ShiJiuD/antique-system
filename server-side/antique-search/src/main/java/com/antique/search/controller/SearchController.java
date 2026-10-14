package com.antique.search.controller;

import com.antique.constant.MessageConstant;
import com.antique.exception.AuthException;
import com.antique.result.Result;
import com.antique.search.service.EsSearchService;
import com.antique.vo.AntiqueCardVO;
import com.antique.vo.PageResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搜索服务对外接口 — M5
 *
 * <h3>路径为什么是 /api/search/antique 而不是 /api/antique/search</h3>
 * <p>拆成独立服务后，<b>每个服务的接口路径应该能一眼看出归属</b>：
 * <ul>
 *   <li>{@code /api/search/**} → 搜索服务（本类）</li>
 *   <li>{@code /api/antique/**}、{@code /api/order/**} → 主服务</li>
 * </ul>
 * 这样 C3 阶段加网关时，路由规则就是一句"按路径前缀分发"，不需要为某个具体接口写例外。
 *
 * <h3>为什么这里没有 Token 拦截器</h3>
 * <p>搜索是<b>公开接口</b>（游客也能搜），主服务里的 {@code TokenInterceptor}
 * 对它本来就是"可选认证"（有 token 就解析、没有就放行）。
 * 拆出来之后统一鉴权会交给网关（C3）处理，这个服务只专注检索。
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "搜索服务", description = "M5 独立搜索服务：Elasticsearch 藏品检索")
public class SearchController {

    private final EsSearchService esSearchService;

    /**
     * 搜索藏品（IK 分词 + 相关性排序 + 关键词高亮）
     *
     * @param keyword    关键词（必填）
     * @param categoryId 分类筛选（可选）
     * @param page       页码，从 1 开始
     * @param size       每页条数
     */
    @Operation(summary = "搜索藏品")
    @GetMapping("/antique")
    public Result<PageResultVO<AntiqueCardVO>> searchAntique(
            @RequestParam String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {

        log.info("搜索请求: keyword={}, categoryId={}, page={}, size={}", keyword, categoryId, page, size);

        // 关键词校验放在最外层：无论后面走哪条路径都要校验
        if (!StringUtils.hasText(keyword)) {
            throw new AuthException(MessageConstant.SEARCH_KEYWORD_EMPTY);
        }

        PageResultVO<AntiqueCardVO> data = esSearchService.search(keyword.trim(), categoryId, page, size);
        return Result.success(data, MessageConstant.QUERY_SUCCESS);
    }

    /**
     * 相关藏品推荐（详情页用）
     *
     * <p><b>这个接口是给"服务之间调用"用的，不是给前端直连的。</b>
     * 主服务处理 {@code GET /api/antique/{id}} 时，会用 Feign 调它拿到"相关藏品"，
     * 再和自己的详情数据一起返回给前端。
     *
     * <p>为什么不让主服务自己查 ES：拆出搜索服务的目的之一就是隔离 ES 依赖。
     * 如果主服务也去连 ES，那这次拆分就白做了。
     *
     * @param antiqueId 当前藏品 ID
     * @param size      返回条数（默认 4）
     */
    @Operation(summary = "相关藏品推荐（服务间调用）")
    @GetMapping("/related")
    public Result<List<AntiqueCardVO>> findRelated(
            @RequestParam Long antiqueId,
            @RequestParam(required = false) Integer size) {

        log.info("相关推荐请求: antiqueId={}, size={}", antiqueId, size);
        List<AntiqueCardVO> data = esSearchService.findRelated(antiqueId, size);
        return Result.success(data, MessageConstant.QUERY_SUCCESS);
    }
}
