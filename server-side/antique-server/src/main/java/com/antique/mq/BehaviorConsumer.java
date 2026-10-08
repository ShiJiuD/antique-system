package com.antique.mq;

import cn.hutool.json.JSONUtil;
import com.antique.constant.MQConstants;
import com.antique.constant.RedisConstant;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 行为事件消费者 — 把 MQ 里的行为流水累加到 Redis 计数
 *
 * <h3>资源声明方式：注解式（@QueueBinding）</h3>
 * <p>队列、交换机、绑定关系全部写在 {@link RabbitListener} 的 {@code bindings} 上，
 * 应用启动时由 {@code RabbitAdmin} 自动到 Broker 声明（已存在则跳过）。
 * 好处是<b>「在这里消费」和「在这里声明」在同一处</b>，不需要翻到配置类去对照。
 *
 * <p>死信三件套（交换机/队列/绑定）因为<b>没有消费者</b>，无法用注解声明，
 * 仍放在 {@link com.antique.config.RabbitMQConfig} 里用 {@code @Bean} 声明。
 *
 * <h3>为什么不直接写数据库</h3>
 * <p>原来的做法是"每打开一次详情页就 UPDATE antique.view_count"（热点行写）。
 * 现在改成：消息进 MQ → 消费者只做一次 Redis INCR（内存操作，毫秒级）
 * → 定时任务每 5 分钟批量落库。写库次数从"每次浏览一次"降到"每 5 分钟一次"。
 *
 * <h3>手动 ack 与死信</h3>
 * <ul>
 *   <li>处理成功 → {@code basicAck} 确认，消息从队列移除</li>
 *   <li>处理失败 → {@code basicNack(requeue=false)}，消息被投到死信队列等人工排查
 *       （{@code requeue=false} 很关键：否则失败消息会立刻重新入队，造成死循环刷日志）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorConsumer {

    private final StringRedisTemplate redisTemplate;

    /** 计数 Key 中的日期格式：20260926 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 计数 Key 的兜底过期时间：2 天（正常情况下定时任务几分钟内就会把它取走并删除） */
    private static final Duration COUNT_KEY_TTL = Duration.ofDays(2);

    /** 死信参数名（AMQP 协议标准参数，由 Broker 识别） */
    private static final String ARG_DLX_EXCHANGE = "x-dead-letter-exchange";
    private static final String ARG_DLX_ROUTING_KEY = "x-dead-letter-routing-key";

    /**
     * 消费行为事件：Redis 原子累加当日计数
     *
     * <p>注解里的三件事：
     * <ol>
     *   <li>{@code @Queue}：队列名 + 持久化 + 死信参数（nack 后消息转发的目标）</li>
     *   <li>{@code @Exchange}：交换机名 + 类型（topic）</li>
     *   <li>{@code key}：绑定的路由键。将来新增「收藏」「搜索」事件，
     *       在 {@link MQConstants} 加常量、这里补一个 key 即可</li>
     * </ol>
     *
     * @param payload     消息体（JSON 字符串）
     * @param channel     当前信道（用于手动 ack）
     * @param deliveryTag 投递标签（ack/nack 要带上它）
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = MQConstants.BEHAVIOR_QUEUE,
                    durable = "true",
                    arguments = {
                            @Argument(name = ARG_DLX_EXCHANGE, value = MQConstants.BEHAVIOR_DLX_EXCHANGE),
                            @Argument(name = ARG_DLX_ROUTING_KEY, value = MQConstants.BEHAVIOR_DLX_KEY)
                    }),
            exchange = @Exchange(name = MQConstants.BEHAVIOR_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = {MQConstants.BEHAVIOR_VISIT_KEY, MQConstants.BEHAVIOR_ORDER_KEY}
    ))
    public void onBehaviorMessage(String payload,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            BehaviorEvent event = JSONUtil.toBean(payload, BehaviorEvent.class);

            String day = LocalDate.now().format(DAY_FORMAT);
            String suffix = BehaviorEvent.TYPE_VISIT.equals(event.getType()) ? "visit" : "order";
            String key = RedisConstant.KEY_STATS_PREFIX + event.getAntiqueId() + ":" + day + ":" + suffix;

            // INCR 是原子操作：并发消费同一条计数 Key 也不会丢数
            Long current = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, COUNT_KEY_TTL);
            // 消费成功：确认这条消息，删除队列里的消息
            channel.basicAck(deliveryTag, false);
            log.debug("行为计数成功: key={}, 当前值={}", key, current);
        } catch (Exception e) {
            log.error("行为消息处理失败，转入死信: payload={}", payload, e);
            // requeue=false → 交给死信队列；若 requeue=true 会立刻重投，形成死循环
            channel.basicNack(deliveryTag, false, false);

        }
    }
}
