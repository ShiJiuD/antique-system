package com.antique.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 销量预测服务（algorithm-side）的 Feign 客户端 — M5 改造
 *
 * <h3>链路</h3>
 * <pre>
 *   GET /api/stats/daily
 *        → StatsServiceImpl 查 daily_stats 表
 *        → 本客户端 Feign 调 Python POST /predict
 *        → 返回未来 N 天预测
 * </pre>
 *
 * <h3>超时为什么只有 3 秒</h3>
 * <p>ai-side 是 LLM 生成（60 秒），而 algorithm-side 只是本地跑一个预测模型，
 * 毫秒级就该返回。两者超时差 20 倍，所以<b>必须按服务分别配</b>——
 * 这正是 Feign 配置 {@code client.config.{服务名}} 的用处。
 * （改造前这个值写死在 StatsServiceImpl 的 @PostConstruct 里。）
 *
 * <h3>url 的说明</h3>
 * <p>同 {@link AiFeignClient}：algorithm-side 还没注册进 Nacos，所以写 url；
 * 注册之后去掉 url 即可变成服务发现调用。
 */
@FeignClient(name = "algorithm-service", url = "${algorithm.python.url:http://127.0.0.1:8002}")
public interface AlgorithmFeignClient {

    /**
     * 销量预测
     *
     * @param body {@code {"antiqueId":1,"historyVisit":[...],"historyOrder":[...]}}
     * @return {@code {code:1, data:{predict_visit:[...], predict_order:[...]}}}
     */
    @PostMapping(value = "/predict", consumes = MediaType.APPLICATION_JSON_VALUE)
    JsonNode predict(@RequestBody Map<String, Object> body);
}
