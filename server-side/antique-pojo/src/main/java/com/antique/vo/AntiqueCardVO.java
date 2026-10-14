package com.antique.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 藏品卡片 VO — 列表/搜索/收藏列表的卡片展示字段
 *
 * <p>仅返回首页卡片渲染所需的 6 个字段（id、title、dynasty、price、
 * coverImage、isHot），浏览量、点赞数等统计字段不在列表返回；
 * 完整信息请调用详情接口 {@code GET /api/antique/{id}}。
 *
 * <p>接口：GET /api/antique/list、GET /api/antique/search、GET /api/favorite/list
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AntiqueCardVO implements Serializable {

    /** 藏品 ID */
    private Long id;

    /** 藏品名称 */
    private String title;

    /** 年代 */
    private String dynasty;

    /** 寄卖价格（元） */
    private BigDecimal price;

    /** 封面图 URL */
    private String coverImage;

    /** 是否热门：0-否，1-是 */
    private Integer isHot;

    /**
     * 高亮后的标题（仅 ES 搜索接口返回，其他接口为 null）
     *
     * <p>命中关键词的部分会被 {@code <em>} 标签包裹，例如：
     * {@code <em>青花瓷</em>瓶 清代官窑精品}。
     * 列表 / 收藏等非搜索场景不设置此字段（保持 null，前端回退用普通 title）。
     */
    private String highlightTitle;
}
