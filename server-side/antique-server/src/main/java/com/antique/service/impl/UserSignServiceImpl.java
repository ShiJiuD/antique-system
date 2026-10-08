package com.antique.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.antique.constant.MessageConstant;
import com.antique.entity.Favorite;
import com.antique.entity.User;
import com.antique.entity.UserSignRecord;
import com.antique.exception.AuthException;
import com.antique.mapper.FavoriteMapper;
import com.antique.mapper.UserMapper;
import com.antique.mapper.UserSignRecordMapper;
import com.antique.service.UserSignService;
import com.antique.vo.SignVO;
import com.antique.vo.UserCenterVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.antique.constant.RedisConstant.KEY_SIGN_BITMAP;
import static com.antique.constant.RedisConstant.KEY_SIGN_CONTINUOUS;
import static com.antique.constant.RedisConstant.SIGN_TTL;

/**
 * 签到服务实现 — MySQL 权威 + Redis 缓存（方案 B）
 *
 * <p>继承 MyBatis-Plus 的 {@code ServiceImpl<UserSignRecordMapper, UserSignRecord>}，
 * 自动获得 save/list 等 CRUD 方法。
 *
 * <h3>架构：MySQL 权威 + Redis 仅作加速层</h3>
 * <ul>
 *   <li>权威源：user_sign_record 表，唯一索引 uk_user_date 幂等防重，可追溯可对账</li>
 *   <li>缓存：BitMap sign:{userId}:{yyyyMM} 作防重快速路径；sign:continuous:{userId} 缓存连续天数</li>
 *   <li>查询：优先读连续天数缓存，miss 回源 MySQL 重算并回写（Cache Aside）</li>
 *   <li>Redis 不可用：完全无感——查询回源 DB，签到照常写 DB，业务不受影响</li>
 * </ul>
 *
 * <h3>积分规则</h3>
 * <p>连续签到第 n 天得 2×n 分（第 1 天 2 分、第 2 天 4 分、第 3 天 6 分…），
 * 断签回到第 1 天 2 分。积分 = 2 × 连续天数，与签到同事务写入 user 表
 * （积分是资产，必须持久化，Redis 只是缓存）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSignServiceImpl extends ServiceImpl<UserSignRecordMapper, UserSignRecord> implements UserSignService {

    private final UserMapper userMapper;
    private final FavoriteMapper favoriteMapper;
    private final StringRedisTemplate redisTemplate;

    // ==================== 签到规则 ====================

    /** 连续签到第 n 天的积分 = POINTS_PER_DAY × n */
    private static final int POINTS_PER_DAY = 2;

    /** 回源重算连续天数时最多取最近多少条记录（覆盖一整年，超长连续在此截断） */
    private static final int MAX_RECALC_RECORDS = 366;

    /** BitMap Key 中的月份格式：202608 */
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    // ========================================================================
    //  接口 1：个人中心初始化
    // ========================================================================

    /**
     * 个人中心初始化
     *
     * <p>连续天数优先读 Redis 缓存，miss 时回源 MySQL 签到流水重算并回写缓存。
     *
     * <p>展示口径（连续天数只算已完成的签到，今天未签不预支）：
     * <ul>
     *   <li>最后签到 = 今天 → 已签到，连续天数 = 缓存/回源值（含今天）</li>
     *   <li>最后签到 = 昨天 → 未签到，连续天数 = 缓存/回源值（连续未断，今天签完才 +1）</li>
     *   <li>断签 → 连续天数 0，今日奖励回到第 1 天 2 分</li>
     * </ul>
     * <p>今日奖励始终按"今天签到后的连续天数"计算（2 × N），与展示口径无关。
     */
    @Override
    public UserCenterVO getCenterInfo(Long userId) {
        // ----- 步骤 1：查询用户 -----
        User user = userMapper.selectById(userId);
        if (user == null || user.getDeletedTime() != null) {
            throw new AuthException(MessageConstant.NOT_LOGIN);
        }
        if (user.getStatus() != 1) {
            throw new AuthException(MessageConstant.ACCOUNT_DISABLED);
        }

        // ----- 步骤 2：读取连续天数（优先 Redis 缓存，miss 回源 MySQL 重算并回写） -----
        ContinuousInfo continuous = readContinuousCache(userId);
        if (continuous == null) {
            // continuous拿到的是缓存值，miss 时回源 DB 重算并回写缓存
            continuous = loadContinuousFromDb(userId);
            writeContinuousCache(userId, continuous);   // 回写缓存（失败仅记日志）
        }

        // ----- 步骤 3：按展示口径组装签到信息 -----
        LocalDate today = LocalDate.now();
        boolean isSignedToday = today.equals(continuous.getLastSignDate());
        boolean signedYesterday = continuous.getLastSignDate() != null
                && today.minusDays(1).equals(continuous.getLastSignDate());

        // 展示口径：只算已完成的连续天数（今天未签不预支，签完才 +1）
        // 已签 / 昨天签过（连续未断）→ 缓存值；断签 → 0
        int continuousDays = (isSignedToday || signedYesterday)
                ? continuous.getDays()
                : 0;

        // 今日签到可获得积分 = 2 × 今天签到后的连续天数（奖励不受展示口径影响）
        int signedDaysAfterToday;
        if (isSignedToday) {
            signedDaysAfterToday = continuous.getDays();      // 已签：今天实际获得的连续天数
        } else if (signedYesterday) {
            signedDaysAfterToday = continuous.getDays() + 1;  // 昨天签过：今天签到后是第 N+1 天
        } else {
            signedDaysAfterToday = 1;                         // 断签/首次：重新从第 1 天开始，得 2 分
        }
        int todayReward = POINTS_PER_DAY * signedDaysAfterToday;

        // ----- 步骤 4：统计收藏数 -----
        Long collectionCount = favoriteMapper.selectCount(
                Wrappers.<Favorite>lambdaQuery().eq(Favorite::getUserId, userId));

        // ----- 步骤 5：组装响应（historyCount 预留，浏览记录表未实现暂返回 0） -----
        log.info("个人中心初始化完成: userId={}, isSignedToday={}, continuousDays={}", userId, isSignedToday, continuousDays);
        return UserCenterVO.builder()
                .userInfo(UserCenterVO.UserInfoVO.builder()
                        .nickname(user.getNickname())
                        .avatar(user.getAvatar())
                        .points(user.getPoints() != null ? user.getPoints() : 0)
                        .signInDays(user.getSignInDays() != null ? user.getSignInDays() : 0)
                        .build())
                .signInfo(UserCenterVO.SignInfoVO.builder()
                        .isSignedToday(isSignedToday)
                        .continuousDays(continuousDays)
                        .todayReward(todayReward)
                        .build())
                .collectionCount(collectionCount.intValue())
                .historyCount(0)
                .build();
    }

    // ========================================================================
    //  接口 2：执行签到
    // ========================================================================

    /**
     * 执行签到（Redis 快速防重 + MySQL 权威写库 + 事务外同步缓存）
     *
     * <p>完整流程：
     * <ol>
     *   <li>Redis BitMap 极速防重：今日位为 1 → "今日已签到"（Redis 异常时降级走 DB）</li>
     *   <li>事务内：回源算今天连续天数 → 插入流水（唯一索引幂等防重）→ 积分同事务写入用户表</li>
     *   <li>事务外：同步 Redis BitMap 置位 + 更新连续天数缓存（失败仅记日志）</li>
     *   <li>回查最新积分组装响应</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SignVO sign(Long userId) {
        LocalDate today = LocalDate.now();
        String bitmapKey = KEY_SIGN_BITMAP + userId + ":" + MONTH_FORMATTER.format(today);
        int dayOfMonth = today.getDayOfMonth();

        // ----- 步骤 1：Redis BitMap 极速防重（快速路径，权威仍是数据库唯一索引） -----
        try {
            Boolean signed = redisTemplate.opsForValue().getBit(bitmapKey, dayOfMonth);
            if (Boolean.TRUE.equals(signed)) {
                throw new AuthException(MessageConstant.SIGN_ALREADY);
            }
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            // Redis 不可用时降级：跳过快速检查，由数据库唯一索引兜底
            log.warn("Redis 签到防重检查失败，降级走数据库: userId={}, error={}", userId, e.getMessage());
        }

        // ----- 步骤 2：事务内回源计算连续天数 + 插入流水（唯一索引 uk_user_date 幂等防重） -----
        int continuousDays;
        int reward;
        try {
            // 2.1 查最后一条签到记录，判断昨天是否已签（权威：MySQL 流水，天然支持跨月连续）
            UserSignRecord lastRecord = getOne(Wrappers.<UserSignRecord>lambdaQuery()
                    .eq(UserSignRecord::getUserId, userId)
                    .orderByDesc(UserSignRecord::getSignDate)
                    .last("LIMIT 1"));

            // 2.2 今天签到后的连续天数：昨天签过 → 昨天的连续 + 1；否则断签重新从第 1 天开始
            if (lastRecord != null && today.minusDays(1).equals(lastRecord.getSignDate())) {
                continuousDays = recountContinuousDays(userId, lastRecord.getSignDate()) + 1;
            } else {
                continuousDays = 1;
            }
            reward = POINTS_PER_DAY * continuousDays;

            // 2.3 插入签到流水（含本次奖励积分，供对账）
            UserSignRecord record = new UserSignRecord();
            record.setUserId(userId);
            record.setSignDate(today);
            save(record);   // createTime 由 MyMetaObjectHandler 自动填充
        } catch (DuplicateKeyException e) {
            // 并发场景：两个请求同时通过 Redis 检查，唯一索引拦截了后到者
            log.warn("并发签到被唯一索引拦截: userId={}", userId);
            throw new AuthException(MessageConstant.SIGN_ALREADY);
        }

        // ----- 步骤 3：积分 = 2 × 连续天数，与签到同事务写入用户表（积分是资产，必须落库） -----
        userMapper.updateSignInfo(userId, reward, continuousDays);

        // ----- 步骤 4：同步 Redis 缓存（事务外，失败仅记日志不阻断成功返回） -----
        try {
            redisTemplate.opsForValue().setBit(bitmapKey, dayOfMonth, true);
            redisTemplate.expire(bitmapKey, SIGN_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 即使 Redis 同步失败，下次签到仍由数据库唯一索引兜底防重
            log.warn("Redis 同步签到状态失败: userId={}, error={}", userId, e.getMessage());
        }
        writeContinuousCache(userId, new ContinuousInfo(continuousDays, today));

        // ----- 步骤 5：回查最新积分，组装响应 -----
        User user = userMapper.selectById(userId);
        Integer newPoints = user != null && user.getPoints() != null ? user.getPoints() : reward;
        log.info("签到成功: userId={}, continuousDays={}, reward={}, newPoints={}",
                userId, continuousDays, reward, newPoints);
        return SignVO.builder()
                .newPoints(newPoints)
                .todayReward(reward)
                .build();
    }

    // ========================================================================
    //  私有工具方法：连续天数回源重算与缓存读写
    // ========================================================================

    /**
     * 回源 MySQL 重算连续签到信息（不信任缓存与冗余字段，逐日核对签到流水）
     *
     * <p>从最后一条签到记录往前数连续天数，按日期逐日对比天然支持跨月
     * （如 3/31 与 4/1 连续）。
     *
     * @return 无任何签到记录时返回 days=0, lastSignDate=null
     */
    private ContinuousInfo loadContinuousFromDb(Long userId) {
        UserSignRecord lastRecord = getOne(Wrappers.<UserSignRecord>lambdaQuery()
                .eq(UserSignRecord::getUserId, userId)
                .orderByDesc(UserSignRecord::getSignDate)
                .last("LIMIT 1"));
        if (lastRecord == null) {
            return new ContinuousInfo(0, null);
        }
        int days = recountContinuousDays(userId, lastRecord.getSignDate());
        return new ContinuousInfo(days, lastRecord.getSignDate());
    }

    /**
     * 从 anchorDate 往回数连续签到天数（anchorDate 当天必须已签到）
     *
     * <p>取最近 {@value MAX_RECALC_RECORDS} 条记录一次性查出，内存逐日回数，
     * 避免循环查库；超过一整年的连续签到属极端场景，在此截断。
     */
    private int recountContinuousDays(Long userId, LocalDate anchorDate) {
        Set<LocalDate> recentDates = list(Wrappers.<UserSignRecord>lambdaQuery()
                .select(UserSignRecord::getSignDate)
                .eq(UserSignRecord::getUserId, userId)
                .orderByDesc(UserSignRecord::getSignDate)
                .last("LIMIT " + MAX_RECALC_RECORDS))
                .stream()
                .map(UserSignRecord::getSignDate)
                .collect(Collectors.toSet());

        int days = 0;
        LocalDate cursor = anchorDate;
        while (recentDates.contains(cursor)) {
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    /**
     * 读取连续签到缓存（sign:continuous:{userId} → JSON{"days":7,"lastSignDate":"2026-08-13"}）
     *
     * @return 缓存命中返回信息；未命中或 Redis 异常返回 null（调用方回源重算）
     */
    private ContinuousInfo readContinuousCache(Long userId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_SIGN_CONTINUOUS + userId);
            if (json == null) {
                return null;
            }
            JSONObject obj = JSONUtil.parseObj(json);
            // 缓存写入的是 ISO 格式（LocalDate.toString），直接标准库解析
            String lastSignDateStr = obj.getStr("lastSignDate");
            LocalDate lastSignDate = lastSignDateStr != null ? LocalDate.parse(lastSignDateStr) : null;
            return new ContinuousInfo(obj.getInt("days", 0), lastSignDate);
        } catch (Exception e) {
            // Redis 不可用/数据损坏 → 回源 DB 重算，不影响业务
            log.warn("Redis 读取连续签到缓存失败，回源 DB: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入连续签到缓存（失败仅记日志，不阻断业务）
     *
     * <p>lastSignDate 为 null（无任何签到记录）时跳过写入，避免缓存无意义的数据。
     */
    private void writeContinuousCache(Long userId, ContinuousInfo info) {
        if (info.getLastSignDate() == null) {
            return;
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("days", info.getDays());
            data.put("lastSignDate", info.getLastSignDate().toString());
            redisTemplate.opsForValue().set(
                    KEY_SIGN_CONTINUOUS + userId,
                    JSONUtil.toJsonStr(data),
                    SIGN_TTL,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("Redis 写入连续签到缓存失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 连续签到信息 — 缓存（sign:continuous:{userId}）与 MySQL 回源的统一载体
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ContinuousInfo {

        /** 截至 lastSignDate 的连续签到天数 */
        private int days;

        /** 最后签到日期（无任何签到记录时为 null） */
        private LocalDate lastSignDate;
    }
}
