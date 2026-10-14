package com.antique.gateway.filter;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.antique.gateway.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 网关统一鉴权过滤器 — M5 · C4
 *
 * <h3>它替代了什么</h3>
 * <p>M5 之前，每个服务各自用 {@code TokenInterceptor}（Servlet 拦截器）校验 token。
 * 加了网关之后，鉴权收到这一层统一做：
 * <pre>
 *   浏览器 ──Bearer token──▶ 网关
 *                              ├─ 白名单？直接放行
 *                              ├─ 查 Redis 校验 token
 *                              ├─ 失败 → 401（不往下转发）
 *                              └─ 成功 → 注入 user-info 请求头 → 转发给下游
 *                                        ↓
 *                              下游服务：从请求头读用户身份（不再查 Redis）
 * </pre>
 *
 * <h3>为什么"拦截"就是不调 chain.filter</h3>
 * <p>业务服务里的 {@code HandlerInterceptor} 靠 {@code preHandle} 返回 false 来拦截；
 * 网关的 {@code GlobalFilter} 没有返回值，<b>不调用 {@code chain.filter(exchange)}
 * 就等于链路到此为止</b>，自己写响应返回即可。
 *
 * <h3>为什么必须用 ReactiveStringRedisTemplate</h3>
 * <p>网关跑在 Netty 的<b>事件循环线程</b>上（数量约等于 CPU 核数）。
 * 如果用阻塞式的 {@code StringRedisTemplate}，一次 Redis 查询就会占住一个事件循环线程 ——
 * 几个慢请求就能让整个网关停止响应（而业务服务是"一请求一线程"，阻塞几个不影响其他）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        // true = 必须登录；false = 可选认证（公开接口，有 token 也照样解析身份）
        boolean required = !authProperties.isExcluded(path);

        // ===== 步骤 1：提取 token =====
        String authHeader = exchange.getRequest().getHeaders().getFirst(AuthProperties.HEADER_AUTH);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(AuthProperties.BEARER_PREFIX)) {
            return required ? unauthorized(exchange, "未登录，请先登录") : chain.filter(exchange);
        }
        String token = authHeader.substring(AuthProperties.BEARER_PREFIX.length());
        String redisKey = authProperties.getTokenKeyPrefix() + token;

        // ===== 步骤 2：查 Redis 校验 =====
        return redisTemplate.opsForValue()
                .get(redisKey)
                // ★ 关键：ReactiveRedisTemplate 在 key 不存在时返回"空序列"，
                //   而下面 flatMap 成功分支里的 chain.filter() 同样也是空序列。
                //   如果直接写 .switchIfEmpty(...) 会分不清"token 无效"和"转发成功"，
                //   所以先用 defaultIfEmpty 把"空"转成一个可判断的值。
                .defaultIfEmpty("")
                .flatMap(userJson -> {
                    if (userJson.isEmpty()) {
                        log.warn("token 无效或已过期: path={}, token={}...", path, safePrefix(token));
                        return required ? unauthorized(exchange, "未登录，请先登录") : chain.filter(exchange);
                    }

                    // ===== 步骤 3：解析身份 =====
                    JSONObject user = JSONUtil.parseObj(userJson);
                    Long userId = user.getLong("userId");
                    String phone = user.getStr("phone");
                    if (userId == null) {
                        log.warn("token 对应的用户信息异常: path={}, json={}", path, userJson);
                        return required ? unauthorized(exchange, "登录信息异常，请重新登录") : chain.filter(exchange);
                    }

                    // ===== 步骤 4：把身份注入请求头，传给下游 =====
                    // ServerHttpRequest 是不可变的，必须 mutate 出一个新的 exchange
                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r
                                    .header(AuthProperties.HEADER_USER_ID, String.valueOf(userId))
                                    .header(AuthProperties.HEADER_USER_PHONE, phone == null ? "" : phone))
                            .build();

                    // ===== 步骤 5：续期（与主服务原来的逻辑一致：活跃用户不频繁掉线）=====
                    // 续期失败不影响放行：宁可少续一次 TTL，也不要让用户请求失败
                    Mono<Boolean> renew = redisTemplate
                            .expire(redisKey, Duration.ofSeconds(authProperties.getTokenTtl()))
                            .onErrorReturn(false);

                    log.info("鉴权通过: userId={}, path={}", userId, path);
                    return renew.then(chain.filter(mutated));
                });
    }

    /**
     * 返回 401 并结束链路（不调用 chain.filter）
     *
     * <p>响应体格式与业务服务保持一致（{@code {"code":0,"msg":"...","data":null}}），
     * 前端不用为首个"网关错误"再写一套解析逻辑。
     *
     * <p>同时设置 HTTP 状态码 401 —— 这样前端可以用标准的错误拦截器统一跳登录页，
     * 监控系统也能按状态码统计"未授权请求量"。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"code\":0,\"msg\":\"" + msg + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** 日志里只打 token 前 8 位，避免完整 token 落到日志文件 */
    private String safePrefix(String token) {
        return token.length() <= 8 ? token : token.substring(0, 8);
    }

    /**
     * 执行顺序
     *
     * <p>{@code 0} 的位置很讲究：
     * <ul>
     *   <li>比日志过滤器（-1）晚 → 被拒绝的请求也会被日志记录</li>
     *   <li>比负载均衡（10150）早 → 被拒绝的请求<b>不会去查 Nacos 拿实例</b>，省一次远程调用</li>
     * </ul>
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
