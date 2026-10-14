package com.antique.mq;

import cn.hutool.json.JSONUtil;
import com.antique.constant.MQConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 行为事件发布者 — 把浏览/下单行为投递到 MQ
 *
 * <h3>设计要点：埋点绝不能影响主流程</h3>
 * <p>统计是"锦上添花"，浏览和下单才是主业务。所以这里所有异常都被吞掉只记日志 ——
 * MQ 挂了、消息发不出去，用户照样能逛能买，最多是这一条行为没被统计到。
 *
 * <h3>为什么发送 JSON 字符串而不是对象</h3>
 * <p>省掉 {@code Jackson2JsonMessageConverter} 的配置与类型头处理，
 * 且在管理台里能直接看到明文消息，排查直观。
 *
 * @see com.antique.constant.MQConstants 交换机名与路由键常量
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 浏览行为埋点
     *
     * @param userId    用户 ID（游客可为 null）
     * @param antiqueId 藏品 ID
     */
    public void publishVisit(Long userId, Long antiqueId) {
        publish(MQConstants.BEHAVIOR_VISIT_KEY,
                new BehaviorEvent(BehaviorEvent.TYPE_VISIT, userId, antiqueId, System.currentTimeMillis()));
    }

    /**
     * 下单行为埋点
     *
     * @param userId    用户 ID
     * @param antiqueId 藏品 ID
     */
    public void publishOrder(Long userId, Long antiqueId) {
        publish(MQConstants.BEHAVIOR_ORDER_KEY,
                new BehaviorEvent(BehaviorEvent.TYPE_ORDER, userId, antiqueId, System.currentTimeMillis()));
    }

    /**
     * 统一发送入口：失败只记警告，不影响调用方
     *
     * @param routingKey 路由键（决定消息进哪个队列）
     * @param event      行为事件
     */
    private void publish(String routingKey, BehaviorEvent event) {
        // CorrelationData 是本条消息的"回执单"：
        // Broker 确认后，它内部的 Future 会完成，onSuccess 里拿到 Confirm 结果。
        // 用「每条消息一个 Future」而不是全局 setConfirmCallback，
        // 好处是回调里能直接读到该条消息的业务上下文（antiqueId / type），不用另外认人。
        // 参数 (confirm, ex)：两个参数一定有一个是 null
        // ex != null：Future 任务执行抛出异常（极少触发）
        // ex == null：拿到回执对象 confirm，再判断 isAck()
        CorrelationData correlationData = new CorrelationData();
        correlationData.getFuture().whenComplete((confirm, ex) -> {
            if (ex != null) {
                // Future 本身异常（框架层问题，正常不会走到）
                log.error("行为消息确认异常: antiqueId={}, type={}",
                        event.getAntiqueId(), event.getType(), ex);
                return;
            }
            if (confirm != null && !confirm.isAck()) {
                // nack：消息没到达交换机（交换机名写错、Broker 异常等）
                log.error("行为消息未到达交换机（消息丢失）: antiqueId={}, type={}, reason={}",
                        event.getAntiqueId(), event.getType(), confirm.getReason());
            }
            // ack 是正常情况，保持安静：埋点是高频路径，成功时打日志会把日志淹掉
        });

        try {
            rabbitTemplate.convertAndSend(MQConstants.BEHAVIOR_EXCHANGE, routingKey,
                    JSONUtil.toJsonStr(event), correlationData);
        } catch (Exception e) {
            log.warn("行为埋点发送失败（已忽略，不影响主流程）: routingKey={}, antiqueId={}",
                    routingKey, event.getAntiqueId(), e);
        }
    }
}
