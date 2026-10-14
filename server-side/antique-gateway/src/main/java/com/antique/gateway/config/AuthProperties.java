package com.antique.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置 — M5 · C4
 *
 * <p>对应 application.yml 的 {@code antique.auth.*}
 */
@Data
@Component
@ConfigurationProperties(prefix = "antique.auth")
public class AuthProperties {

    /** 请求头名：客户端带 token 用（格式 Authorization: Bearer xxx） */
    public static final String HEADER_AUTH = "Authorization";

    /** Bearer 前缀（注意后面有个空格） */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * 请求头名：网关校验通过后，把用户身份注入到这个头，传给下游服务
     *
     * <p>下游服务的拦截器读它就能拿到用户，不用再查一次 Redis。
     *
     * <p>⚠️ <b>安全前提</b>：这个头是"内部信任头"，凡是能直接访问下游服务端口的人
     * 都能伪造它绕过鉴权。所以生产环境必须保证：
     * <ol>
     *   <li>业务服务端口<b>不对外暴露</b>（只能通过网关访问）</li>
     *   <li>更严格的做法：网关用共享密钥对这个头做 HMAC 签名，服务侧验签</li>
     * </ol>
     */
    public static final String HEADER_USER_ID = "user-info";

    /** 请求头名：网关注入的手机号（仅用于日志/展示，不做鉴权依据） */
    public static final String HEADER_USER_PHONE = "user-phone";

    /** Ant 路径匹配器（支持 /api/auth/** 这种通配） */
    private static final PathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 免强制登录的路径（Ant 通配符）
     *
     * <p>注意这些是"<b>可选认证</b>"而不是"完全不看 token"：
     * 带了有效 token 网关照样解析身份并注入请求头，只是没带/失效时不返回 401。
     */
    private List<String> excludePaths = new ArrayList<>();

    /** Token 在 Redis 里的 Key 前缀（必须与主服务 RedisConstant.KEY_TOKEN 一致） */
    private String tokenKeyPrefix = "antique:token:";

    /** Token 续期时长（秒），与主服务 TOKEN_TTL 保持一致：7 天 */
    private long tokenTtl = 604800L;

    /**
     * 判断路径是否免强制登录
     *
     * @param path 请求路径（不含域名和 query）
     */
    public boolean isExcluded(String path) {
        for (String pattern : excludePaths) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
