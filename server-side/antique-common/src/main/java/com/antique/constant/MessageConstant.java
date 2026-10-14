package com.antique.constant;

/**
 * 消息常量 — 统一管理所有接口返回的提示信息
 *
 * <p>所有 Controller 中使用的硬编码字符串均从此处引用，
 * 方便后续统一修改或多语言扩展。
 */
public class MessageConstant {

    /** 通用操作成功 */
    public static final String OP_SUCCESS = "操作成功";

    /** 系统内部异常（兜底） */
    public static final String SYSTEM_ERROR = "系统异常";

    /** 系统繁忙（Redis 等基础设施不可用时） */
    public static final String SYSTEM_BUSY = "系统繁忙";

    // ==================== 通用参数校验 ====================

    /** 请求体格式错误（JSON 解析失败或字段类型不匹配） */
    public static final String PARAM_FORMAT_ERROR = "请求参数格式错误";

    /** 参数校验兜底提示（无具体字段错误信息时使用） */
    public static final String PARAM_ERROR = "参数错误";

    // ==================== 认证相关 ====================

    /** 手机号格式校验失败 */
    public static final String PHONE_INVALID = "手机号格式不正确";

    /** 验证码发送成功 */
    public static final String CODE_SEND_SUCCESS = "验证码发送成功";

    /** 验证码错误或已过期 */
    public static final String CODE_ERROR_OR_EXPIRED = "验证码错误或已过期";

    /** 登录成功 */
    public static final String LOGIN_SUCCESS = "登录成功";

    /** 账号或密码错误（统一提示，不区分用户不存在/密码错误，防撞库） */
    public static final String ACCOUNT_OR_PASSWORD_ERROR = "账号或密码错误";

    /** 手机号未注册（仅密码登录时：用户存在但未设置密码） */
    public static final String PHONE_NOT_REGISTERED = "手机号未注册";

    /** 账号已被禁用 */
    public static final String ACCOUNT_DISABLED = "账号已被禁用";

    /** 未登录或 Token 已过期 */
    public static final String NOT_LOGIN = "未登录，请先登录";

    // ==================== 用户操作 ====================

    /** 退出登录成功 */
    public static final String LOGOUT_SUCCESS = "退出成功";

    /** 个人信息修改成功 */
    public static final String PROFILE_UPDATE_SUCCESS = "修改成功";

    // ==================== 藏品相关 ====================

    /** 查询成功（列表/搜索/详情/收藏列表） */
    public static final String QUERY_SUCCESS = "查询成功";

    /** 藏品不存在 */
    public static final String ANTIQUE_NOT_EXIST = "藏品不存在";

    /** 藏品不存在或已下架 */
    public static final String ANTIQUE_NOT_ON_SALE = "藏品不存在或已下架";

    /** 搜索关键词不能为空 */
    public static final String SEARCH_KEYWORD_EMPTY = "搜索关键词不能为空";

    // ==================== 收藏相关 ====================

    /** 收藏成功 */
    public static final String FAVORITE_ADD_SUCCESS = "收藏成功";

    /** 取消收藏成功 */
    public static final String FAVORITE_REMOVE_SUCCESS = "取消收藏成功";

    /** 已收藏过该藏品（重复收藏） */
    public static final String FAVORITE_ALREADY = "已收藏过该藏品";

    /** 未收藏该藏品（取消收藏时） */
    public static final String FAVORITE_NOT_EXIST = "未收藏该藏品";

    // ==================== 签到相关 ====================

    /** 今日已签到（重复签到） */
    public static final String SIGN_ALREADY = "今日已签到";

    /** 签到成功（积分动态 = 2×连续天数，%d 由 Controller 用 String.format 填充） */
    public static final String SIGN_SUCCESS = "签到成功，获得%d积分";

    // ==================== 订单相关 ====================

    /** 下单成功 */
    public static final String ORDER_CREATE_SUCCESS = "下单成功";

    /** 支付成功 */
    public static final String ORDER_PAY_SUCCESS = "支付成功";

    /** 订单取消成功 */
    public static final String ORDER_CANCEL_SUCCESS = "订单已取消";

    /** 确认收货成功 */
    public static final String ORDER_RECEIVE_SUCCESS = "确认收货成功";

    /** 已通知卖家发货 */
    public static final String ORDER_URGE_SUCCESS = "已通知卖家发货";

    /** 收货信息修改成功 */
    public static final String ORDER_ADDRESS_UPDATE_SUCCESS = "修改成功";

    /** 订单不存在（或不属于当前用户） */
    public static final String ORDER_NOT_EXIST = "订单不存在";

    /** 不能购买自己发布的藏品 */
    public static final String ORDER_CANNOT_BUY_OWN = "不能购买自己发布的藏品";

    /** 该藏品已有未完成订单（一物一单防重复下单） */
    public static final String ORDER_DUPLICATE_ACTIVE = "该藏品已有未完成订单，请勿重复下单";

    /** 藏品已售出或正在交易中（并发下单时行级锁拦截） */
    public static final String ORDER_ANTIQUE_LOCKED = "藏品已售出或正在交易中";

    /** 订单已超时关闭（16 分钟未支付） */
    public static final String ORDER_TIMEOUT_CLOSED = "订单已超时关闭";

    /** 订单已取消 */
    public static final String ORDER_CANCEL_ALREADY = "订单已取消";

    /** 订单状态不允许支付 */
    public static final String ORDER_PAY_STATUS_ERROR = "订单状态不允许支付";

    /** 仅待付款订单可取消（已支付订单退款走线下客服） */
    public static final String ORDER_CANCEL_ONLY_UNPAID = "仅待付款订单可取消，已支付订单退款请咨询客服";

    /** 已发货订单不能修改地址 */
    public static final String ORDER_ADDRESS_LOCKED = "已发货订单不能修改地址";

    /** 订单状态不允许确认收货 */
    public static final String ORDER_RECEIVE_STATUS_ERROR = "订单状态不允许确认收货";

    /** 仅待发货订单可催发货 */
    public static final String ORDER_URGE_ONLY_SHIPPING = "仅待发货订单可催发货";

    /** 当前仅支持单件购买 */
    public static final String ORDER_QUANTITY_ONLY_ONE = "当前仅支持单件购买";

    // ==================== 消息相关 ====================

    /** 已读成功 */
    public static final String MESSAGE_READ_SUCCESS = "已读成功";

    /** 消息不存在（或不属于当前用户） */
    public static final String MESSAGE_NOT_EXIST = "消息不存在";

    /** 消息标题：订单消息 */
    public static final String MESSAGE_TITLE_ORDER = "订单消息";

    /** 下单成功消息内容模板（%s = 订单号，由 Service 用 String.format 填充） */
    public static final String MESSAGE_ORDER_CREATED = "您的订单 %s 已创建成功，请尽快完成支付";

    /** 支付成功消息内容模板（%s = 订单号）—— M3 事件驱动后新增 */
    public static final String MESSAGE_ORDER_PAID = "您的订单 %s 已支付成功，等待卖家发货";

    /**
     * 订单关闭消息内容模板（%s = 订单号）—— M3 新增
     *
     * <p>用户主动取消和超时未支付关单共用一个模板：
     * 对买家来说结果一样（订单关了、藏品重新上架），没必要让前端文案分叉。
     */
    public static final String MESSAGE_ORDER_CLOSED = "您的订单 %s 已关闭";

    /** 确认收货消息内容模板（%s = 订单号）—— M3 新增 */
    public static final String MESSAGE_ORDER_RECEIVED = "您的订单 %s 已确认收货，感谢您的购买";

    // ==================== AI 助手相关 ====================

    /** AI 服务不可用（Python 服务未启动/超时/异常时兜底提示） */
    public static final String AI_SERVICE_UNAVAILABLE = "AI服务暂时不可用，请稍后重试";

    // ==================== ES 搜索相关（M2）====================

    /**
     * ES 索引不存在
     *
     * <p>必须先用 server-side/es/antique-index.json 建好索引再同步；
     * 否则 ES 会自动创建一个「动态推断 mapping」的索引 —— 不报错，
     * 但中文会被切成单字、字段类型也未必正确，属于最难排查的静默错误。
     */
    public static final String ES_INDEX_NOT_EXISTS =
            "ES 索引不存在，请先执行 server-side/es/antique-index.json 创建索引（详见 server-side/es/README.md）";
}
