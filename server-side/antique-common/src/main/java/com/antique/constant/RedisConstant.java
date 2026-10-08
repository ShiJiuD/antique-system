package com.antique.constant;

/**
 * Redis 常量 — 定义所有 Redis Key 前缀和过期时间
 *
 * <h3>Key 设计规范</h3>
 * <ul>
 *   <li>Token Key: {@code antique:token:{uuid32}} → JSON{"userId":1,"phone":"138****8000"}</li>
 *   <li>验证码 Key: {@code sms:code:{phone}} → 6位数字验证码</li>
 *   <li>签到 BitMap Key: {@code sign:{userId}:{yyyyMM}} → BitMap，位 1=当日已签到（防重快速路径）</li>
 *   <li>签到连续天数缓存 Key: {@code sign:continuous:{userId}} → JSON{"days":7,"lastSignDate":"2026-08-13"}（Cache Aside，miss 回源 MySQL 重算）</li>
 * </ul>
 *
 * <h3>TTL 设计</h3>
 * <ul>
 *   <li>Token 7 天 — 每次请求自动续期，活跃用户无需频繁登录</li>
 *   <li>验证码 5 分钟 — 防暴力破解，过期需重新发送</li>
 *   <li>签到 BitMap 3 个月 — 过期后由 user_sign_record 流水表回推，不影响签到功能</li>
 * </ul>
 */
public class RedisConstant {

    // ==================== Key 前缀 ====================

    /** Token 存储 Key 前缀，完整 Key = antique:token:{token} */
    public static final String KEY_TOKEN = "antique:token:";

    /** 短信验证码 Key 前缀，完整 Key = sms:code:{phone} */
    public static final String KEY_SMS_CODE = "sms:code:";

    /** 签到 BitMap Key 前缀，完整 Key = sign:{userId}:{yyyyMM}（按月一个 Key，offset 为当月第几天） */
    public static final String KEY_SIGN_BITMAP = "sign:";

    /** 签到连续天数缓存 Key 前缀，完整 Key = sign:continuous:{userId} */
    public static final String KEY_SIGN_CONTINUOUS = "sign:continuous:";

    /** 订单超时延迟队列 Key（Redisson RDelayedQueue，元素 = orderId，16 分钟后到期投递） */
    public static final String KEY_ORDER_DELAY_QUEUE = "delay:order-close";

    /**
     * 行为统计计数 Key 前缀（M1 行为流水）
     *
     * <p>完整 Key = {@code stats:{antiqueId}:{yyyyMMdd}:{visit|order}}，值为当日累计次数。
     * 由消费者 INCR 累加、定时任务读取并删除后落库到 daily_stats 表。
     */
    public static final String KEY_STATS_PREFIX = "stats:";

    // ==================== 过期时间（秒） ====================

    /** Token 过期时间：7 天 = 60×60×24×7 */
    public static final long TOKEN_TTL = 604800L;

    /** 验证码过期时间：5 分钟 = 60×5 */
    public static final long CODE_TTL = 300L;

    /** 签到 BitMap 过期时间：3 个月 = 90×24×60×60 */
    public static final long SIGN_TTL = 7776000L;
}
