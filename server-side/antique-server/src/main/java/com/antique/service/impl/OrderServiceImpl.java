package com.antique.service.impl;

import com.antique.constant.MessageConstant;
import com.antique.dto.OrderAddressDTO;
import com.antique.dto.OrderCreateDTO;
import com.antique.entity.Antique;
import com.antique.entity.OrderItem;
import com.antique.entity.Orders;
import com.antique.exception.AuthException;
import com.antique.mapper.AntiqueMapper;
import com.antique.mapper.OrderItemMapper;
import com.antique.mapper.OrderMapper;
import com.antique.mq.BehaviorEventPublisher;
import com.antique.service.MessageService;
import com.antique.service.OrderService;
import com.antique.util.OrderNoGenerator;
import com.antique.util.OrderVoAssembler;
import com.antique.util.PageUtil;
import com.antique.util.TimeUtil;
import com.antique.vo.OrderCreateVO;
import com.antique.vo.OrderDetailVO;
import com.antique.vo.OrderListItemVO;
import com.antique.vo.OrderPayVO;
import com.antique.vo.PageResultVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RDelayedQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单服务实现 — 买家订单全生命周期
 *
 * <p>继承 MyBatis-Plus 的 {@code ServiceImpl<OrderMapper, Orders>}，
 * 自动获得 save/selectPage 等 CRUD 方法。
 *
 * <h3>核心设计（详见技术方案文档）</h3>
 * <ul>
 *   <li>防一物多卖：创建订单时 {@code antique.status 1→2} 行级锁锁定（条件更新），
 *       取消/超时关单时带「有效订单守卫」幂等回滚</li>
 *   <li>状态流转全部使用条件更新（WHERE 带当前状态），并发下天然幂等</li>
 *   <li>超时取消三层保障：延迟消息（主）→ 定时任务（兜底）→ 惰性检查（读路径），
 *       判定统一以 DB 时间与 pay_deadline 为准</li>
 *   <li>延迟队列入队时机为事务提交后（afterCommit），失败仅记日志，正确性不依赖 Redis</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Orders> implements OrderService {

    private final AntiqueMapper antiqueMapper;
    private final OrderItemMapper orderItemMapper;
    private final RDelayedQueue<Long> orderDelayQueue;
    private final MessageService messageService;
    private final BehaviorEventPublisher behaviorEventPublisher;

    /**
     * 自注入代理：惰性检查需要调用本类事务方法 closeOrderByTimeout，
     * 通过代理调用才能让 @Transactional 生效（同类自调用不经过代理）。
     */
    @Lazy
    @Autowired
    private OrderService self;

    // ==================== 常量 ====================

    /** 支付超时时间：16 分钟（与接口文档/技术方案文档统一） */
    private static final int PAY_TIMEOUT_MINUTES = 16;

    // ========================================================================
    //  接口 1：创建订单
    // ========================================================================

    /**
     * 创建订单（锁定藏品 + 写主表/明细，16 分钟支付倒计时）
     *
     * <p>防重复下单（业务校验）+ 行级锁（物理兜底）双重保障一物一单；
     * 延迟队列入队挂在 afterCommit（事务提交后），Redis 不可用不影响下单成功，
     * 超时关单由定时任务/惰性检查兜底。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderCreateVO createOrder(Long userId, OrderCreateDTO dto) {
        // ----- 步骤 1：校验藏品在售 -----
        Antique antique = antiqueMapper.selectById(dto.getAntiqueId());
        if (antique == null || antique.getDeletedTime() != null || antique.getStatus() != 1) {
            throw new AuthException(MessageConstant.ANTIQUE_NOT_ON_SALE);
        }

        // ----- 步骤 2：不能购买自己发布的藏品 -----
        if (userId.equals(antique.getSellerId())) {
            throw new AuthException(MessageConstant.ORDER_CANNOT_BUY_OWN);
        }

        // ----- 步骤 3：数量校验（当前仅支持单件购买） -----
        int quantity = dto.getQuantity() == null ? 1 : dto.getQuantity();
        if (quantity != 1) {
            throw new AuthException(MessageConstant.ORDER_QUANTITY_ONLY_ONE);
        }

        // ----- 步骤 4：防重复下单（一物一单，业务层快速校验） -----
        if (orderMapperActiveCount(dto.getAntiqueId()) > 0) {
            throw new AuthException(MessageConstant.ORDER_DUPLICATE_ACTIVE);
        }

        // ----- 步骤 5：行级锁锁定藏品（物理兜底，防并发一物多卖） -----
        // 并发下单时仅第一个事务成功，后者影响行数 0 → 回滚返回"藏品已售出或正在交易中"
        // UPDATE antique SET status = 2 WHERE id = ? AND status = 1
        if (antiqueMapper.lockAntique(dto.getAntiqueId()) == 0) {
            throw new AuthException(MessageConstant.ORDER_ANTIQUE_LOCKED);
        }

        // ----- 步骤 6：组装订单（status=0 待付款，支付截止 = 创建 + 16 分钟） -----
        BigDecimal price = antique.getPrice();
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));

        Orders order = new Orders();
        order.setOrderNo(OrderNoGenerator.generate());
        order.setUserId(userId);
        order.setSellerId(antique.getSellerId());                    // 卖家冗余快照
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);                             // V1 无优惠，实付 = 总金额
        order.setStatus(0);
        order.setPayDeadline(LocalDateTime.now().plusMinutes(PAY_TIMEOUT_MINUTES));
        order.setReceiverName(dto.getReceiverName());
        order.setReceiverPhone(dto.getReceiverPhone());
        order.setReceiverAddress(dto.getReceiverAddress());
        order.setRemark(dto.getRemark());
        save(order);

        // ----- 步骤 7：写入订单明细（藏品快照，一单一件） -----
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setAntiqueId(antique.getId());
        item.setAntiqueTitle(antique.getTitle());
        item.setAntiqueCover(antique.getCoverImage());
        item.setDynasty(antique.getDynasty());
        item.setPrice(price);
        item.setQuantity(quantity);
        item.setTotalPrice(totalAmount);
        orderItemMapper.insert(item);

        // ----- 步骤 8：事务提交后入队延迟队列（16 分钟后触发超时关单） -----
        // 失败仅记日志：延迟消息允许丢失，由定时任务/惰性检查兜底
        final Long orderId = order.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    orderDelayQueue.offer(orderId, PAY_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("订单延迟队列入队失败，交由定时任务兜底: orderId={}", orderId, e);
                }

                // 下单行为埋点（M1 行为流水）：事务提交后才发，避免统计到最终回滚的订单
                behaviorEventPublisher.publishOrder(userId, dto.getAntiqueId());
            }
        });

        log.info("创建订单成功: orderId={}, orderNo={}, totalAmount={}, userId={}, antiqueId={}",
                order.getId(), order.getOrderNo(), totalAmount, userId, dto.getAntiqueId());

        // ----- 步骤 9：发送订单消息（写库同事务 + WebSocket 推送） -----
        // 失败不影响下单：消息已落库则推送兜底，落库失败仅记日志（练手项目可接受）
        try {
            messageService.sendOrderMessage(userId, order.getOrderNo());
        } catch (Exception e) {
            log.warn("订单消息发送失败，不影响下单: orderId={}", order.getId(), e);
        }

        return OrderCreateVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .totalAmount(totalAmount)
                .payDeadline(TimeUtil.format(order.getPayDeadline()))
                .build();
    }

    // ========================================================================
    //  接口 2：订单支付
    // ========================================================================

    /**
     * 订单支付（模拟支付，无真实支付渠道）
     *
     * <p>超时惰性检查：待付款且已过支付截止 → 先关单再提示"订单已超时关闭"；
     * 条件更新防并发重复支付（第二次点击影响行数 0，幂等返回）。
     */
    @Override
    public OrderPayVO pay(Long userId, Long orderId) {
        Orders order = getOwnedOrder(userId, orderId);

        // ----- 步骤 1：超时惰性检查 -----
        if (order.getStatus() == 0 && order.getPayDeadline().isBefore(LocalDateTime.now())) {
            self.closeOrderByTimeout(orderId);
            throw new AuthException(MessageConstant.ORDER_TIMEOUT_CLOSED);
        }
        // ----- 步骤 2：状态校验 -----
        if (order.getStatus() != 0) {
            throw new AuthException(order.getStatus() == 4
                    ? MessageConstant.ORDER_CANCEL_ALREADY
                    : MessageConstant.ORDER_PAY_STATUS_ERROR);
        }

        // ----- 步骤 3：模拟支付成功（条件更新防并发重复支付） -----
        int rows = baseMapper.payOrder(orderId);
        if (rows == 0) {
            // 并发下已被其他请求处理：重新查询返回当前状态（幂等）
            order = getOwnedOrder(userId, orderId);
            if (order.getStatus() == 1) {
                return OrderPayVO.builder()
                        .orderId(orderId)
                        .status(1)
                        .payTime(TimeUtil.format(order.getPayTime()))
                        .build();
            }
            throw new AuthException(MessageConstant.ORDER_PAY_STATUS_ERROR);
        }

        // ----- 步骤 4：移除延迟队列元素（失败仅记日志，不影响结果） -----
        try {
            orderDelayQueue.remove(orderId);
        } catch (Exception e) {
            log.warn("移除订单延迟队列入队失败，超时关单会被条件更新拦截: orderId={}", orderId, e);
        }

        log.info("订单支付成功: orderId={}, userId={}", orderId, userId);
        return OrderPayVO.builder()
                .orderId(orderId)
                .status(1)
                .payTime(TimeUtil.format(LocalDateTime.now()))
                .build();
    }

    // ========================================================================
    //  接口 3：取消订单
    // ========================================================================

    /**
     * 取消订单（仅待付款可取消，回滚藏品）
     *
     * <p>已支付订单（待发货起）的取消/退款走"退款/售后"（V1 预留，线下客服处理）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long orderId) {
        Orders order = getOwnedOrder(userId, orderId);
        if (order.getStatus() != 0) {
            throw new AuthException(MessageConstant.ORDER_CANCEL_ONLY_UNPAID);
        }

        // ----- 条件更新（status=0 防取消与支付并发互踩），影响行数 0 → 已被处理 -----
        int rows = baseMapper.update(null, Wrappers.<Orders>lambdaUpdate()
                .set(Orders::getStatus, 4)
                .set(Orders::getCancelType, 1)
                .set(Orders::getCancelTime, LocalDateTime.now())
                .eq(Orders::getId, orderId)
                .eq(Orders::getStatus, 0));
        if (rows == 0) {
            throw new AuthException(MessageConstant.ORDER_CANCEL_ONLY_UNPAID);
        }

        // ----- 回滚藏品（幂等：仅回滚锁定态且无其他有效订单的藏品） -----
        antiqueMapper.releaseAntique(getAntiqueIdByOrderId(orderId));

        // ----- 移除延迟队列元素 -----
        try {
            orderDelayQueue.remove(orderId);
        } catch (Exception e) {
            log.warn("移除订单延迟队列失败，超时关单会被条件更新拦截: orderId={}", orderId, e);
        }

        log.info("订单取消成功: orderId={}, userId={}", orderId, userId);
    }

    // ========================================================================
    //  接口 4：我的订单列表
    // ========================================================================

    /**
     * 我的订单列表（按状态筛选，查询前惰性处理超时订单）
     *
     * <p>惰性取消是读路径兜底：保证用户看到的状态即时正确；
     * 批量取消与批量回滚均为条件更新，与延迟消息/定时任务并发安全。
     */
    @Override
    public PageResultVO<OrderListItemVO> pageList(Long userId, Integer status, Integer page, Integer size) {
        page = PageUtil.normalizePage(page);
        size = PageUtil.normalizeSize(size);

        // ----- 步骤 1：退款/售后预留（V1 恒返回空列表） -----
        if (status != null && status == 5) {
            return PageResultVO.<OrderListItemVO>builder()
                    .list(new ArrayList<>())
                    .total(0L)
                    .page(page)
                    .size(size)
                    .build();
        }

        // ----- 步骤 2：惰性超时取消（查询前兜底，失败不影响列表查询） -----
        try {
            int closed = baseMapper.closeOverdueByUser(userId);
            if (closed > 0) {
                antiqueMapper.batchReleaseByUser(userId);
            }
        } catch (Exception e) {
            log.warn("惰性超时取消失败: userId={}, error={}", userId, e.getMessage());
        }

        // ----- 步骤 3：分页查询订单主表 -----
        LambdaQueryWrapper<Orders> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Orders::getUserId, userId);
        if (status != null) {
            wrapper.eq(Orders::getStatus, status);
        }
        wrapper.orderByDesc(Orders::getCreateTime);
        Page<Orders> result = page(new Page<>(page, size), wrapper);

        // ----- 步骤 4：联查明细快照（取第一条作为卡片展示） -----
        List<Orders> records = result.getRecords();
        Map<Long, OrderItem> itemMap = loadItemsByOrderIds(records.stream()
                .map(Orders::getId)
                .collect(Collectors.toList()));
        List<OrderListItemVO> voList = records.stream()
                .map(o -> OrderVoAssembler.toListItem(o, itemMap.get(o.getId())))
                .collect(Collectors.toList());

        log.info("订单列表查询完成: userId={}, status={}, 共 {} 条", userId, status, result.getTotal());
        return PageResultVO.<OrderListItemVO>builder()
                .list(voList)
                .total(result.getTotal())
                .page(page)
                .size(size)
                .build();
    }

    // ========================================================================
    //  接口 5：订单详情
    // ========================================================================

    /**
     * 订单详情（仅买家本人查看，手机号不脱敏）
     */
    @Override
    public OrderDetailVO getDetail(Long userId, Long orderId) {
        Orders order = getOwnedOrder(userId, orderId);

        // ----- 惰性超时检查（保证详情状态一致） -----
        if (order.getStatus() == 0 && order.getPayDeadline().isBefore(LocalDateTime.now())) {
            self.closeOrderByTimeout(orderId);
            order = getOwnedOrder(userId, orderId);
        }

        // ----- 明细 -----
        List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, orderId)
                .orderByAsc(OrderItem::getId));

        // ----- 卖家名称快照 -----
        String sellerName = null;
        if (!items.isEmpty()) {
            Antique antique = antiqueMapper.selectById(items.get(0).getAntiqueId());
            if (antique != null) {
                sellerName = antique.getSellerName();
            }
        }

        log.info("订单详情查询完成: orderId={}, userId={}", orderId, userId);
        return OrderVoAssembler.toDetail(order, items, sellerName);
    }

    // ========================================================================
    //  接口 6：修改收货地址
    // ========================================================================

    /**
     * 修改收货地址（仅未发货订单可修改，对应"修改地址"按钮）
     */
    @Override
    public void updateAddress(Long userId, OrderAddressDTO dto) {
        Orders order = getOwnedOrder(userId, dto.getOrderId());
        if (order.getStatus() != 0 && order.getStatus() != 1) {
            throw new AuthException(MessageConstant.ORDER_ADDRESS_LOCKED);
        }

        int rows = baseMapper.update(null, Wrappers.<Orders>lambdaUpdate()
                .set(Orders::getReceiverName, dto.getReceiverName())
                .set(Orders::getReceiverPhone, dto.getReceiverPhone())
                .set(Orders::getReceiverAddress, dto.getReceiverAddress())
                .eq(Orders::getId, dto.getOrderId())
                .in(Orders::getStatus, 0, 1));
        if (rows == 0) {
            throw new AuthException(MessageConstant.ORDER_ADDRESS_LOCKED);
        }
        log.info("订单收货地址修改成功: orderId={}, userId={}", dto.getOrderId(), userId);
    }

    // ========================================================================
    //  接口 7：确认收货
    // ========================================================================

    /**
     * 确认收货（待收货 → 已完成）
     */
    @Override
    public void receive(Long userId, Long orderId) {
        Orders order = getOwnedOrder(userId, orderId);
        if (order.getStatus() != 2) {
            throw new AuthException(MessageConstant.ORDER_RECEIVE_STATUS_ERROR);
        }

        int rows = baseMapper.update(null, Wrappers.<Orders>lambdaUpdate()
                .set(Orders::getStatus, 3)
                .set(Orders::getReceiveTime, LocalDateTime.now())
                .set(Orders::getFinishTime, LocalDateTime.now())
                .eq(Orders::getId, orderId)
                .eq(Orders::getStatus, 2));
        if (rows == 0) {
            throw new AuthException(MessageConstant.ORDER_RECEIVE_STATUS_ERROR);
        }
        log.info("订单确认收货成功: orderId={}, userId={}", orderId, userId);
    }

    // ========================================================================
    //  接口 8：催发货
    // ========================================================================

    /**
     * 催发货（模拟通知卖家，后续接入消息模块）
     */
    @Override
    public void urge(Long userId, Long orderId) {
        Orders order = getOwnedOrder(userId, orderId);
        if (order.getStatus() != 1) {
            throw new AuthException(MessageConstant.ORDER_URGE_ONLY_SHIPPING);
        }
        log.info("催发货: orderId={}, userId={}（模拟通知卖家，后续接入消息模块）", orderId, userId);
    }

    // ========================================================================
    //  超时关单（延迟消息消费者 / 定时任务兜底 / 惰性检查共用）
    // ========================================================================

    /**
     * 超时关单：待付款且已超时 → 已取消（cancelType=2），并幂等回滚藏品
     *
     * <p>三方调用方（延迟消息/定时任务/惰性检查）并发处理同一订单时，
     * 条件更新（status=0 + pay_deadline < NOW()，DB 时间权威）保证仅生效一次：
     * 已支付/已关单的订单影响行数 0，直接幂等退出。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeOrderByTimeout(Long orderId) {
        Orders order = baseMapper.selectById(orderId);
        if (order == null) {
            return;
        }

        // ----- 条件更新关单（DB 时间 NOW() 权威判定超时） -----
        int rows = baseMapper.update(null, Wrappers.<Orders>lambdaUpdate()
                .set(Orders::getStatus, 4)
                .set(Orders::getCancelType, 2)
                .set(Orders::getCancelTime, LocalDateTime.now())
                .eq(Orders::getId, orderId)
                .eq(Orders::getStatus, 0)
                .last("AND pay_deadline < NOW()"));
        if (rows == 0) {
            // 已被支付/已关单（延迟消息提前到达或并发处理），幂等退出
            return;
        }

        // ----- 幂等回滚藏品（仅回滚锁定态且无其他有效订单的藏品） -----
        antiqueMapper.releaseAntique(getAntiqueIdByOrderId(orderId));

        // ----- 移除延迟队列元素（失败仅记日志） -----
        try {
            orderDelayQueue.remove(orderId);
        } catch (Exception e) {
            log.warn("移除订单延迟队列失败: orderId={}, error={}", orderId, e.getMessage());
        }

        log.info("订单超时关单成功: orderId={}, orderNo={}", orderId, order.getOrderNo());
    }

    // ========================================================================
    //  私有工具方法
    // ========================================================================

    /**
     * 查询当前用户订单，查不到或不属于该用户一律返回"订单不存在"
     */
    private Orders getOwnedOrder(Long userId, Long orderId) {
        Orders order = baseMapper.selectById(orderId);
        if (order == null || order.getDeletedTime() != null || !userId.equals(order.getUserId())) {
            throw new AuthException(MessageConstant.ORDER_NOT_EXIST);
        }
        return order;
    }

    /**
     * 防重复下单统计（一物一单：藏品存在未完成订单即不允许再次下单）
     */
    private long orderMapperActiveCount(Long antiqueId) {
        return baseMapper.countActiveByAntiqueId(antiqueId);
    }

    /**
     * 取订单的第一条明细（当前一单一件），用于回滚藏品与卡片展示
     */
    private Long getAntiqueIdByOrderId(Long orderId) {
        OrderItem item = orderItemMapper.selectOne(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderId, orderId)
                .last("LIMIT 1"));
        return item == null ? null : item.getAntiqueId();
    }

    /**
     * 按订单 ID 批量加载明细，返回 orderId → 第一条明细的映射
     */
    private Map<Long, OrderItem> loadItemsByOrderIds(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                        .in(OrderItem::getOrderId, orderIds))
                .stream()
                .collect(Collectors.toMap(OrderItem::getOrderId, Function.identity(), (a, b) -> a));
    }

}
