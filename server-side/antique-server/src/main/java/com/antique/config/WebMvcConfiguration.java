package com.antique.config;

import com.antique.interceptor.TokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 — CORS 跨域 + Token 拦截器注册
 *
 * <h3>拦截范围</h3>
 * <p>拦截所有 {@code /api/**} 路径，但排除 {@code /api/auth/**}。
 * 其余路径分两类（由 TokenInterceptor 内部判定）：
 * <ul>
 *   <li>强制认证：如 {@code /api/favorite/**}，未登录一律返回 401</li>
 *   <li>可选认证（公开）：{@code /api/antique/**}，无 Token 匿名放行，
 *       有有效 Token 则照常写入用户上下文（详情接口据此返回 isFavorited）</li>
 * </ul>
 *
 * <h3>CORS 配置</h3>
 * <p>开发环境开放全部跨域请求。生产环境应改为限制具体域名。
 *
 * <h3>与 ciTY Art 的差异</h3>
 * <p>ciTY Art 有 user 和 admin 两个拦截路径组，本项目目前只有用户端，
 * 后续添加管理端时可参考 ciTY Art 模式扩展。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final TokenInterceptor tokenInterceptor;

    /**
     * 注册 Token 认证拦截器
     *
     * <p>拦截 /api/**（除 /api/auth/**、/api/ws），在请求到达 Controller 之前
     * 完成 Token 校验、续期、用户上下文设置。
     * <p>/api/ws（WebSocket 握手）不走本拦截器：浏览器无法自定义请求头，
     * 由 WebSocket 握手拦截器通过 query 参数 token 自行校验。
     */
    /**
     * CORS 跨域 —— M5 起<b>已移除</b>，统一挪到网关
     *
     * <h3>为什么删掉</h3>
     * <p>改造前这里 {@code allowedOriginPatterns("*")} 全开跨域：
     * <ul>
     *   <li>每加一个服务都要各配一份，容易配得不一致（主服务、搜索服务、网关都要配）</li>
     *   <li>开发期"全开"没问题，但生产环境要收紧时，分散在多处很难逐一改对</li>
     * </ul>
     *
     * <p>现在所有请求都从网关进入，跨域只配在
     * {@code antique-gateway} 的 {@code spring.cloud.gateway.globalcors}。
     *
     * <p>⚠️ 副作用：<b>浏览器直连 {@code localhost:8080} 会被同源策略拦</b>。
     * 本地调试要么走网关 {@code localhost:9000}，要么用 Postman/curl（不受同源策略限制）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/api/**")               // 拦截所有 API 请求
                .excludePathPatterns(
                        "/api/auth/**",                   // 登录/验证码接口无需认证
                        WebSocketConfig.WS_ENDPOINT       // WebSocket 握手自行鉴权
                );
    }
}
