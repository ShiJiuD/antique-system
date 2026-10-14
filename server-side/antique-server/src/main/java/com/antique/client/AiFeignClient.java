package com.antique.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Python AI 服务（ai-side）的 Feign 客户端 — M5 改造
 *
 * <h3>替换了什么</h3>
 * <pre>
 *   改造前（AiServiceImpl）：
 *       @PostConstruct 里手工 new SimpleClientHttpRequestFactory()
 *       factory.setConnectTimeout(5000); factory.setReadTimeout(60000);
 *       RestClient.builder().baseUrl(pythonUrl)...
 *
 *   改造后：本接口 + application.yml 里的超时配置
 *       spring.cloud.openfeign.client.config.ai-service.connectTimeout: 5000
 *       spring.cloud.openfeign.client.config.ai-service.readTimeout: 60000
 * </pre>
 * <b>超时从代码搬到配置</b>，改超时不用重新编译，将来还能挪到 Nacos 配置中心做热更新。
 *
 * <h3>★ 为什么用 url 而不是 lb://</h3>
 * <p>ai-side 是 Python FastAPI 服务，目前<b>没有注册进 Nacos</b>，
 * 所以这里必须写死地址（从配置读，不硬编码在代码里）。
 * <p>等它用 {@code nacos-sdk-python} 注册进 Nacos 之后，把 {@code url} 去掉，
 * 只留 {@code name = "ai-service"}，就会自动变成"按服务名发现 + 负载均衡"——
 * <b>调用方代码一行都不用改</b>。
 *
 * <h3>为什么返回 JsonNode 而不是定义 DTO</h3>
 * <p>Python 侧返回的是统一信封 {@code {code, data}}，data 里的结构各接口不同
 * （chat 是 {@code {answer}}、history 是 {@code {list:[{role,content}]}}）。
 * 用 JsonNode 保持与改造前一致的解析逻辑，避免为了"看起来规范"造一堆只有两个字段的 DTO。
 * 如果 Python 侧的返回结构稳定下来，再换成强类型 DTO 更好。
 */
@FeignClient(name = "ai-service", url = "${ai.python.url:http://127.0.0.1:8001}")
public interface AiFeignClient {

    /**
     * AI 问答（RAG）
     *
     * <p>Python 侧执行完整 RAG 流程（知识库检索 + 历史 + 通义千问生成），
     * 并把本轮问答写入该用户的历史文件。
     *
     * <p>注意 readTimeout 配到 60 秒 —— LLM 生成本来就慢，
     * 用统一的短超时会大面积超时失败。
     *
     * @param body {@code {"userId":1,"question":"..."}}
     * @return Python 侧原始响应 {@code {code:1, data:{answer:"..."}}}
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    JsonNode chat(@RequestBody Map<String, Object> body);

    /**
     * 查询对话历史
     *
     * @param userId 用户 ID
     * @return {@code {code:1, data:{list:[{role,content}, ...]}}}
     */
    @GetMapping("/history")
    JsonNode history(@RequestParam("userId") Long userId);
}
