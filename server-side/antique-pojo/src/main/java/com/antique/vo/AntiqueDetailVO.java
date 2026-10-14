package com.antique.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 藏品详情 VO — 藏品详情页完整信息
 *
 * <p>接口：GET /api/antique/{id}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AntiqueDetailVO implements Serializable {

    /** 藏品 ID */
    private Long id;

    /** 藏品名称 */
    private String title;

    /** 分类：1-瓷器，2-字画，3-玉器，4-铜器，5-杂项 */
    private Integer categoryId;

    /** 子类别（如"青花瓷"） */
    private String subCategory;

    /** 年代 */
    private String dynasty;

    /** 材质 */
    private String material;

    /** 寄卖价格（元） */
    private BigDecimal price;

    /** 封面图 URL */
    private String coverImage;

    /** 详情图 URL 列表 */
    private List<String> images;

    /** 藏品描述 */
    private String description;

    /** 卖家用户 ID */
    private Long sellerId;

    /** 卖家/店铺名称 */
    private String sellerName;

    /** 浏览次数 */
    private Integer viewCount;

    /** 收藏次数 */
    private Integer likeCount;

    /** 是否热门：0-否，1-是 */
    private Integer isHot;

    /** 状态：0-下架，1-在售，2-已售 */
    private Integer status;

    /** 创建时间（格式化：yyyy-MM-dd HH:mm:ss） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** 当前用户是否已收藏（未登录时恒为 false） */
    private Boolean isFavorited;

    /**
     * 相关藏品推荐（M5：由独立的搜索服务提供，主服务通过 Feign 获取）
     *
     * <p>取不到时是空列表 —— 相关推荐是附加信息，搜索服务挂了不该让详情页打不开。
     * 其他接口不返回这个字段（列表、收藏列表里它是 null）。
     */
    private List<AntiqueCardVO> relatedList;
}
