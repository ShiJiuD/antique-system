package com.antique.constant;

/**
 * 认证相关的常量 — 服务间约定的"身份传递"契约
 *
 * <h3>为什么需要这个类</h3>
 * <p>M5 改造后，鉴权从各服务上收到了网关（{@code antique-gateway} 的
 * {@code AuthGlobalFilter}）。网关校验 token 成功后，把用户身份写进请求头传给下游服务，
 * 下游服务的拦截器再读出来放进 ThreadLocal。
 *
 * <p>这一对请求头名是<b>两个服务之间的契约</b>：名字必须完全一致，写错一个字
 * 就会静默退化成"每次都走 Redis 校验"（能跑，但白白多查一次缓存）。
 * 所以定义成常量，避免两边各写一份字面量。
 *
 * <h3>⚠️ 安全前提（重要）</h3>
 * <p>{@link #HEADER_USER_ID} 是<b>内部信任头</b>：网关发什么，下游就信什么。
 * 这意味着**任何能直接访问业务服务端口的人都能伪造它来绕过鉴权**。
 * 生产环境必须满足下面至少一条：
 * <ol>
 *   <li><b>业务服务端口不对外暴露</b>，外部流量只能从网关进来（最常见做法）</li>
 *   <li>网关用共享密钥对该头做 HMAC 签名，下游验签后才信任</li>
 * </ol>
 *
 * <h3>网关侧为什么没有引用这个类</h3>
 * <p>网关的依赖刻意保持"零业务依赖"（连 antique-common 都不引，避免 servlet 那套被带进来），
 * 所以它在自己那边定义了同名常量 {@code AuthProperties.HEADER_USER_ID}。
 * 改动这里时，记得同步改网关那一份。
 */
public class AuthConstant {

    /** 客户端携带 token 的请求头（格式：Authorization: Bearer xxx） */
    public static final String HEADER_AUTH = "Authorization";

    /** Bearer 前缀（注意带一个空格） */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 网关注入的用户 ID —— 下游服务据此识别当前用户 */
    public static final String HEADER_USER_ID = "user-info";

    /** 网关注入的手机号 —— 仅用于展示/日志，不作为鉴权依据 */
    public static final String HEADER_USER_PHONE = "user-phone";
}
