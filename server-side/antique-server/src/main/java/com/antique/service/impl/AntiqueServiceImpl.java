package com.antique.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.antique.constant.MessageConstant;
import com.antique.entity.Antique;
import com.antique.entity.Favorite;
import com.antique.exception.AuthException;
import com.antique.mapper.AntiqueMapper;
import com.antique.mapper.FavoriteMapper;
import com.antique.mq.BehaviorEventPublisher;
import com.antique.service.AntiqueService;
import com.antique.util.PageUtil;
import com.antique.vo.AntiqueCardVO;
import com.antique.vo.AntiqueDetailVO;
import com.antique.vo.PageResultVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 藏品服务实现 — 藏品列表/详情/搜索核心业务逻辑
 *
 * <p>继承 MyBatis-Plus 的 {@code ServiceImpl<AntiqueMapper, Antique>}，
 * 自动获得 getById/selectPage 等 CRUD 方法。
 *
 * <h3>查询说明</h3>
 * <ul>
 *   <li>在售过滤：status = 1，且 deleted_time IS NULL（MyBatis-Plus
 *       全局逻辑删除配置自动拼接 deleted_time 条件）</li>
 *   <li>列表/搜索仅查询卡片字段，避免加载 images/description 大字段</li>
 *   <li>动态条件：参数为 null 时不拼接条件（等价于接口文档中的
 *       {@code OR ? IS NULL} 写法，但能命中 category_id 索引）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AntiqueServiceImpl extends ServiceImpl<AntiqueMapper, Antique> implements AntiqueService {

    private final AntiqueMapper antiqueMapper;
    private final FavoriteMapper favoriteMapper;
    private final BehaviorEventPublisher behaviorEventPublisher;

    // ========================================================================
    //  接口 1：获取藏品列表
    // ========================================================================

    /**
     * 分页查询藏品列表（首页推荐、分类筛选）
     * <p>仅在售（status=1）；categoryId/isHot 为空时不参与筛选。
     */
    @Override
    public PageResultVO<AntiqueCardVO> pageList(Long categoryId, Integer isHot, Integer page, Integer size) {
        // ----- 步骤 1：分页参数归一化（防止负数/超上限） -----
        page = PageUtil.normalizePage(page);
        size = PageUtil.normalizeSize(size);

        // ----- 步骤 2：构建动态查询条件 -----
        LambdaQueryWrapper<Antique> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Antique::getStatus, 1);                    // 仅在售
        if (categoryId != null) {
            wrapper.eq(Antique::getCategoryId, categoryId);   // 分类筛选（可选）
        }
        if (isHot != null) {
            wrapper.eq(Antique::getIsHot, isHot);             // 热门筛选（可选）
        }
        // 仅查询卡片展示字段，避免加载 images/description 等大字段
        wrapper.select(Antique::getId, Antique::getTitle, Antique::getDynasty,
                       Antique::getPrice, Antique::getCoverImage, Antique::getIsHot);
        wrapper.orderByDesc(Antique::getCreateTime);          // 最新发布在前

        // ----- 步骤 3：分页查询并转换 -----
        Page<Antique> result = page(new Page<>(page, size), wrapper);
        List<AntiqueCardVO> voList = result.getRecords().stream()
                .map(a -> BeanUtil.copyProperties(a, AntiqueCardVO.class))
                .collect(Collectors.toList());
        log.info("藏品列表查询完成: categoryId={}, isHot={}, 共 {} 条", categoryId, isHot, result.getTotal());

        // ----- 步骤 4：组装分页结果 -----
        return PageResultVO.<AntiqueCardVO>builder()
                .list(voList)
                .total(result.getTotal())
                .page(page)
                .size(size)
                .build();
    }

    // ========================================================================
    //  接口 2：获取藏品详情
    // ========================================================================

    /**
     * 获取藏品详情（浏览次数 +1）
     * <p>不存在或已下架（status=0）抛业务异常；已登录时判断 isFavorited。
     */
    @Override
    public AntiqueDetailVO getDetail(Long id, Long userId) {
        // ----- 步骤 1：查询藏品 -----
        Antique antique = getById(id);
        // 不存在或已下架（status=0）均视为不可见；已售（status=2）仍可浏览
        if (antique == null || antique.getStatus() == 0) {
            throw new AuthException(MessageConstant.ANTIQUE_NOT_ON_SALE);
        }

        // ----- 步骤 2：判断当前用户是否已收藏（仅登录用户） -----
        boolean isFavorited = false;
        if (userId != null) {
            LambdaQueryWrapper<Favorite> favoriteWrapper = Wrappers.lambdaQuery();
            favoriteWrapper.eq(Favorite::getUserId, userId)
                           .eq(Favorite::getAntiqueId, id);
            isFavorited = favoriteMapper.selectCount(favoriteWrapper) > 0;
        }

        // ----- 步骤 3：浏览行为埋点（M1 行为流水）-----
        // 改造前：antiqueMapper.incrementViewCount(id) —— 每次浏览都写一次数据库（热点行写）
        // 改造后：只发一条 MQ 消息，消费者累加到 Redis，定时任务每 5 分钟批量落库
        behaviorEventPublisher.publishVisit(userId, id);

        // ----- 步骤 4：组装详情 VO -----
        AntiqueDetailVO vo = BeanUtil.copyProperties(antique, AntiqueDetailVO.class);
        vo.setImages(parseImages(antique.getImages()));
        vo.setIsFavorited(isFavorited);
        log.info("藏品详情查询完成: id={}, 是否已收藏: {}", id, isFavorited);
        return vo;
    }

    // ========================================================================
    //  接口 3：搜索藏品
    // ========================================================================

    /**
     * 搜索藏品（按名称模糊匹配，返回卡片字段）
     * <p>关键词为空白直接抛业务异常；可选 categoryId 分类内搜索。
     */
    @Override
    public PageResultVO<AntiqueCardVO> search(String keyword, Long categoryId, Integer page, Integer size) {
        // ----- 步骤 1：关键词校验 -----
        // 空白关键词没有检索意义，直接返回空列表会误导用户，故抛出业务异常
        if (!StringUtils.hasText(keyword)) {
            throw new AuthException(MessageConstant.SEARCH_KEYWORD_EMPTY);
        }

        // ----- 步骤 2：分页参数归一化 -----
        page = PageUtil.normalizePage(page);
        size = PageUtil.normalizeSize(size);

        // ----- 步骤 3：构建动态查询条件 -----
        LambdaQueryWrapper<Antique> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Antique::getStatus, 1)                          // 仅在售
               .like(Antique::getTitle, keyword.trim());           // 名称模糊匹配
        if (categoryId != null) {
            wrapper.eq(Antique::getCategoryId, categoryId);        // 分类内搜索（可选）
        }
        wrapper.select(Antique::getId, Antique::getTitle, Antique::getDynasty,
                       Antique::getPrice, Antique::getCoverImage, Antique::getIsHot);
        wrapper.orderByDesc(Antique::getCreateTime);               // 最新发布在前

        // ----- 步骤 4：分页查询并转换 -----
        Page<Antique> result = page(new Page<>(page, size), wrapper);
        List<AntiqueCardVO> voList = result.getRecords().stream()
                .map(a -> BeanUtil.copyProperties(a, AntiqueCardVO.class))
                .collect(Collectors.toList());
        log.info("藏品搜索完成: keyword={}, categoryId={}, 共 {} 条", keyword, categoryId, result.getTotal());

        // ----- 步骤 5：组装分页结果 -----
        return PageResultVO.<AntiqueCardVO>builder()
                .list(voList)
                .total(result.getTotal())
                .page(page)
                .size(size)
                .build();
    }

    // ========================================================================
    //  私有工具方法
    // ========================================================================

    /**
     * 解析详情图 JSON 数组字符串 → List（失败返回空列表，不阻断展示）
     *
     * @param imagesJson 数据库原始字符串，可能为 null
     */
    private List<String> parseImages(String imagesJson) {
        if (!StringUtils.hasText(imagesJson)) {
            return new ArrayList<>();
        }
        try {
            // Hutool JSONUtil 直接按 JSON 数组字符串解析为 List<String>
            return JSONUtil.toList(imagesJson, String.class);
        } catch (Exception e) {
            log.warn("藏品详情图 JSON 解析失败, 已返回空列表: images={}", imagesJson, e);
            return new ArrayList<>();
        }
    }

}
