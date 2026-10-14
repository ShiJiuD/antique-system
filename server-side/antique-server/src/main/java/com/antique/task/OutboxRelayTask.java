package com.antique.task;

import com.antique.constant.MQConstants;
import com.antique.entity.Outbox;
import com.antique.mapper.OutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 投递任务（M3）— 把"记下来要发的事件"真正投递到 MQ
 *
 * <h3>它在整条链路里的位置</h3>
 * <pre>
 *   业务事务（下单/支付/取消/收货）
 *        └─ INSERT outbox（status=0）      ← 和业务同事务，原子
 *                 ↓
 *   ★ 本任务：每 5 秒扫一次待发送记录
 *        ├─ 投递成功（Broker ack）→ markSent（status=1）
 *        └─ 投递失败 → backoff（retry_count+1，指数退避后再试）
 *                 ↓
 *   order.event.exchange ──routing──▶ order.event.queue ──▶ OrderEventConsumer
 * </pre>
 *
 * <h3>★ 与方案文档骨架的一处改进：等 Broker 回执再标记已发送</h3>
 * <p>文档里的写法是：
 * <pre>
 *   rabbitTemplate.convertAndSend(...);      // 只是"交给客户端"，还没到 Broker
 *   outboxMapper.markSent(id);               // 立刻标记已发送
 * </pre>
 * <p>问题在于 {@code convertAndSend} 是<b>异步</b>的：它把消息交给客户端的 channel 就返回了，
 * Broker 的确认（ack/nack）稍后才到。如果 Broker 因为交换机不存在等原因 <b>nack</b> 了这条消息，
 * 记录已经被标成"已发送"→ 这条事件永远不会重发，<b>静默丢失</b>。
 *
 * <p>所以这里改成：<b>用 CorrelationData 等 Broker 的确认，只有 ack 才标记已发送</b>，
 * nack / 超时 / 异常一律走退避重试。这与 M1 的
 * {@link com.antique.mq.BehaviorEventPublisher} 用的是同一套回执机制
 * （配置里的 {@code publisher-confirm-type: correlated} 就是它生效的前提）。
 *
 * <h3>为什么敢在这里"阻塞等待"</h3>
 * <p>本方法是 {@code @Scheduled} 的定时任务，跑在自己的调度线程上，<b>不占用任何用户请求线程</b>。
 * 等 3 秒最多让"下一轮扫描"晚 3 秒，不影响接口响应。
 * （反过来说：<b>这种阻塞写法绝不能出现在 Controller/Service 的请求路径上</b>。）
 *
 * <h3>多实例部署时的已知限制</h3>
 * <p>两个实例同时跑这个任务时，可能同时扫到同一条记录 → <b>重复投递</b>。
 * 这是有意接受的：MQ 本来提供的就是"至少一次"语义，
 * 幂等由消费端的 {@code message.event_id} 唯一索引兜住（见 OrderEventConsumer）。
 * 要彻底避免重复，可以改成"先原子占位再发送"（{@code UPDATE ... SET status=2 WHERE status=0}），
 * 或用分布式锁 —— 单实例部署下没必要引入这个复杂度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayTask {

    private final OutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    /** 单批投递上限：避免积压很多时一次性全部加载进内存、也避免单轮跑太久 */
    private static final int BATCH_SIZE = 100;

    /** 退避上限（秒）：失败太多次就不必再等更久，5 分钟一次足够 */
    private static final int MAX_BACKOFF_SECONDS = 300;

    /** 等待 Broker 回执的超时（秒） */
    private static final long CONFIRM_TIMEOUT_SECONDS = 3;

    /**
     * 每 5 秒扫描一次待发送事件
     *
     * <p>{@code fixedDelay} 而不是 {@code fixedRate}：fixedDelay 是"上一轮<b>执行完</b>再等 5 秒"，
     * 保证不会因为某一轮变慢而堆积并发执行。对投递任务来说，稳比快重要。
     */
    @Scheduled(fixedDelay = 5000)
    public void relay() {
        List<Outbox> pending;
        try {
            pending = outboxMapper.selectPending(BATCH_SIZE);
        } catch (Exception e) {
            // 数据库暂时不可用：本轮跳过，下轮再来（不要因为一次查询失败就中断任务）
            log.error("扫描 Outbox 待发送事件失败，本轮跳过", e);
            return;
        }

        if (pending.isEmpty()) {
            return;   // 绝大多数轮次都是空的，直接返回，避免刷日志
        }

        int sent = 0;
        int failed = 0;
        for (Outbox outbox : pending) {
            if (deliver(outbox)) {
                sent++;
            } else {
                failed++;
            }
        }
        log.info("Outbox 投递完成: 本批 {} 条, 成功 {}, 失败 {}, 剩余待发 {}",
                pending.size(), sent, failed, outboxMapper.countPending());
    }

    /**
     * 投递单条事件
     *
     * @return true=Broker 已确认（记录标记为已发送）；false=失败（已安排退避重试）
     */
    private boolean deliver(Outbox outbox) {
        // CorrelationData 是这条消息的"回执单"：Broker 处理完会往里写 ack/nack
        CorrelationData correlationData = new CorrelationData(outbox.getEventId());
        try {
            rabbitTemplate.convertAndSend(MQConstants.ORDER_EVENT_EXCHANGE, outbox.getRoutingKey(),
                    // payload 是入库时序列化好的 JSON 字符串，原样发出去即可
                    outbox.getPayload(),
                    message -> {
                        // 把 eventId 写进消息属性：在 RabbitMQ 管理台里能按它搜到这条消息
                        message.getMessageProperties().setMessageId(outbox.getEventId());
                        return message;
                    },
                    correlationData);

            CorrelationData.Confirm confirm =
                    correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (confirm != null && confirm.isAck()) {
                outboxMapper.markSent(outbox.getId());
                log.info("Outbox 投递成功: eventId={}, type={}, orderNo 见 payload",
                        outbox.getEventId(), outbox.getEventType());
                return true;
            }
            log.warn("Outbox 投递被 Broker 拒绝(nack): eventId={}, reason={}",
                    outbox.getEventId(), confirm == null ? "无回执" : confirm.getReason());
        } catch (Exception e) {
            // 连接不上、超时、序列化异常等，统一走退避重试
            log.warn("Outbox 投递异常: eventId={}, type={}", outbox.getEventId(), outbox.getEventType(), e);
        }

        // ===== 没成功：指数退避，等下一轮再试 =====
        int delaySeconds = backoffSeconds(outbox.getRetryCount());
        try {
            outboxMapper.backoff(outbox.getId(), delaySeconds);
            log.warn("Outbox 已安排重试: eventId={}, {} 秒后重试, 已失败 {} 次",
                    outbox.getEventId(), delaySeconds,
                    (outbox.getRetryCount() == null ? 0 : outbox.getRetryCount()) + 1);
        } catch (Exception e) {
            log.error("Outbox 退避更新失败（这条事件会立刻被重新扫到）: eventId={}", outbox.getEventId(), e);
        }
        return false;
    }

    /**
     * 指数退避：第 n 次失败后等 2^n 秒，封顶 5 分钟
     *
     * <p>为什么要有退避：MQ 挂掉时如果每 5 秒全量重试，会持续冲击已经出问题的 Broker；
     * 退避让重试频率随失败次数下降，给下游恢复的时间。
     */
    private int backoffSeconds(Integer retryCount) {
        int n = retryCount == null ? 0 : retryCount;
        // 2, 4, 8, 16, ... 最多移到 1024，再和 MAX 取小
        int seconds = 1 << Math.min(n + 1, 10);
        return Math.min(seconds, MAX_BACKOFF_SECONDS);
    }
}
