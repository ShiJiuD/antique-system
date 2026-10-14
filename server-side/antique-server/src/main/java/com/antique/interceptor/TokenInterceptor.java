package com.antique.interceptor;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.antique.constant.AuthConstant;
import com.antique.constant.MessageConstant;
import com.antique.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

import static com.antique.constant.RedisConstant.KEY_TOKEN;
import static com.antique.constant.RedisConstant.TOKEN_TTL;

/**
 * Token 认证拦截器 — Redis 随机 Token 方案
 *
 * <h3>完整认证流程</h3>
 * <ol>
 *   <li>从请求头提取 {@code Authorization: Bearer <token>}</li>
 *   <li>根据 Token 从 Redis 查询用户信息（Key: antique:token:{token}）</li>
 *   <li>若 Token 不存在或 Redis 不可达 → 返回 401 {"code":0,"msg":"未登录，请先登录"}</li>
 *   <li>解析 Redis 中的 JSON → 获取 userId 和 phone</li>
 *   <li>设置 {@code UserContext}（ThreadLocal）</li>
 *   <li>刷新 Token 在 Redis 中的 TTL 为 7 天（续期）</li>
 *   <li>请求处理完成后 → afterCompletion 清理 {@code UserContext}</li>
 * </ol>
 *
 * <h3>可选认证（公开接口）</h3>
 * <p>{@code /api/antique/**} 下的接口（藏品列表/详情/搜索）为公开访问：
 * <ul>
 *   <li>携带有效 Token → 正常鉴权并设置用户上下文（详情接口据此返回 isFavorited）</li>
 *   <li>无 Token 或 Token 已失效 → 按匿名用户放行，不阻断公开页面访问</li>
 * </ul>
 * 其余 /api/** 路径仍为强制认证，未登录一律返回 401。
 *
 * <h3>与 ciTY Art 的核心差异</h3>
 * <ul>
 *   <li>ciTY Art 使用 JWT 自包含 Token + Redis 黑名单</li>
 *   <li>本项目使用 Redis 存储 Token，Token 本身是随机 UUID 不含用户信息</li>
 *   <li>优势：可随时在 Redis 中删除 Token 使其立即失效，无需等 JWT 过期</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;

    /**
     * 可选认证路径（Ant 通配符）
     *
     * <p>匹配的路径允许匿名访问；若携带有效 Token 则照常鉴权并写入用户上下文。
     * 目前仅藏品公开接口使用（详情接口需要可选地判断 isFavorited）。
     */
    private static final String[] OPTIONAL_AUTH_PATHS = {"/api/antique/**"};

    /** Ant 路径匹配器（用于通配符匹配请求 URI） */
    private static final PathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 请求前拦截 — Token 校验、续期、用户上下文设置
     *
     * @return true=放行, false=拦截并返回 401 JSON
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 当前请求是否为可选认证路径（公开接口）
        boolean optionalAuth = isOptionalAuthPath(request.getRequestURI());

        // ====== 步骤 0（M5·C4 新增）：优先使用网关注入的身份 ======
        // 走网关的请求，token 已经由网关的 AuthGlobalFilter 校验过，
        // 并把 userId 放进了 user-info 请求头 —— 这里直接读，省掉一次 Redis 查询。
        //
        // 没有这个头说明是"直连服务"（本地调试、内部调用、WebSocket 之外的旁路），
        // 那就继续走下面的原有逻辑自己查 Redis —— 两种情况都安全，不会因为少了网关就裸奔。
        //
        // ⚠️ 前提：业务服务端口不对外暴露（否则可以在外面伪造 user-info 绕过鉴权），
        //    详见 AuthConstant 的说明。
        String gatewayUserId = request.getHeader(AuthConstant.HEADER_USER_ID);
        if (StringUtils.hasText(gatewayUserId)) {
            try {
                Long userId = Long.valueOf(gatewayUserId.trim());
                String phone = request.getHeader(AuthConstant.HEADER_USER_PHONE);
                UserContext.set(userId, StringUtils.hasText(phone) ? phone : null);
                return true;
            } catch (NumberFormatException e) {
                // 头被伪造或格式不对 → 忽略它，继续走本地校验
                // （注意：不能因为"这个头坏了"就放行，也不能直接拒绝，降级到查 Redis 最稳）
                log.warn("网关注入的 user-info 非法，改用本地 Redis 校验: value={}", gatewayUserId);
            }
        }

        // ====== 步骤 1：提取 Token ======
        String authHeader = request.getHeader(AuthConstant.HEADER_AUTH);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // 可选认证路径：无 Token 则匿名放行（UserContext 不设置，getUserId() 返回 null）
            if (optionalAuth) {
                return true;
            }
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":0,\"msg\":\"" + MessageConstant.NOT_LOGIN + "\",\"data\":null}");
            return false;
        }

        // 截取 "Bearer " 之后的 Token 字符串
        String token = authHeader.substring(7);
        String redisKey = KEY_TOKEN + token;

        // ====== 步骤 2：从 Redis 获取用户信息 ======
        String userJson;
        try {
            userJson = redisTemplate.opsForValue().get(redisKey);
        } catch (Exception e) {
            log.error("Redis 读取 Token 失败: token={}", token, e);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":0,\"msg\":\"" + MessageConstant.SYSTEM_BUSY + "\",\"data\":null}");
            return false;
        }

        // Token 不存在或已过期
        if (userJson == null) {
            // 可选认证路径：Token 已失效则按匿名用户放行，不阻断公开页面访问
            if (optionalAuth) {
                log.warn("公开接口携带失效 Token，按匿名放行: uri={}", request.getRequestURI());
                return true;
            }
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":0,\"msg\":\"" + MessageConstant.NOT_LOGIN + "\",\"data\":null}");
            return false;
        }

        // ====== 步骤 3：解析用户信息 JSON ======
        // 使用 Hutool JSONUtil 解析：getLong/getStr 自动做类型转换，且对缺失/空值容错返回 null
        JSONObject userJsonObj = JSONUtil.parseObj(userJson);
        Long userId = userJsonObj.getLong("userId");
        String phone = userJsonObj.getStr("phone");

        // ====== 步骤 4：设置用户上下文（ThreadLocal） ======
        UserContext.set(userId, phone);

        // ====== 步骤 5：Token 续期 7 天 ======
        // 活跃用户无需频繁登录，每次请求自动刷新 TTL
        try {
            redisTemplate.expire(redisKey, TOKEN_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 续期失败不阻塞业务，仅记录日志
            log.warn("Redis Token 续期失败: token={}, error={}", token, e.getMessage());
        }

        return true;
    }

    /**
     * 请求完成后清理 — 防止 ThreadLocal 内存泄漏
     *
     * <p>无论请求成功或异常，都会执行此方法清理线程上下文。
     * Tomcat 线程池会复用线程，不清理会导致下次请求读到上一个用户的数据。
     */
    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 判断当前请求路径是否为可选认证路径
     *
     * <p>使用 Ant 通配符匹配（如 {@code /api/antique/**}），
     * 匹配成功表示该接口允许匿名访问。
     *
     * @param requestUri 请求路径（不含域名和 QueryString）
     * @return true=可选认证（匿名放行），false=强制认证
     */
    private boolean isOptionalAuthPath(String requestUri) {
        for (String pattern : OPTIONAL_AUTH_PATHS) {
            if (PATH_MATCHER.match(pattern, requestUri)) {
                return true;
            }
        }
        return false;
    }
}
