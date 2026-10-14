package com.antique.service.impl;

import com.antique.client.AiFeignClient;
import com.antique.constant.MessageConstant;
import com.antique.exception.AuthException;
import com.antique.service.AiService;
import com.antique.vo.AiMessageVO;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助手服务实现 — 通过 Feign 调用 Python AI 服务（ai-side）
 *
 * <p>链路：前端 → AiController → 本类 →（Feign）→ Python FastAPI（127.0.0.1:8001）→ 通义千问
 *
 * <h3>M5 改造：RestClient → OpenFeign</h3>
 * <pre>
 *   改造前：本类里 @PostConstruct 手工 new RestClient + 把超时写死在代码里
 *   改造后：只声明一个 {@link AiFeignClient} 接口，超时统一配在 application.yml
 *           spring.cloud.openfeign.client.config.ai-service.readTimeout: 60000
 * </pre>
 *
 * <h3>错误兜底</h3>
 * Python 服务未启动、超时、返回异常时统一抛出
 * {@link com.antique.exception.AuthException}（"AI服务暂时不可用"），
 * 由 GlobalExceptionHandler 转成 {@code Result.error(msg)} 返回前端，不暴露 500。
 *
 * <h3>为什么不用 @FeignClient(fallback = ...)</h3>
 * <p>Feign 的 {@code fallback} 属性需要引入熔断器实现（Sentinel 或 Spring Cloud CircuitBreaker），
 * 本项目暂时没引，所以降级仍然靠这里的 try-catch —— <b>效果是一样的</b>：
 * 外部服务故障不影响主流程，只是错误提示走的是业务异常而不是 fallback 类。
 * 将来引入熔断器时，把下面 catch 块里的逻辑原样搬进 fallback 类即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    /** M5：Feign 客户端（地址与超时都来自配置） */
    private final AiFeignClient aiFeignClient;

    /**
     * AI 问答：调用 Python 服务 POST /chat
     *
     * <p>Python 侧执行完整 RAG 流程（知识库检索 + 历史 + 通义千问生成），
     * 并将本轮问答保存到该用户的本地历史文件。
     */
    @Override
    public String chat(Long userId, String question) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("userId", userId);
            body.put("question", question);

            JsonNode resp = aiFeignClient.chat(body);

            // 校验响应结构：{code:1=成功, data:{answer}}
            if (resp == null || resp.get("code") == null || resp.get("code").asInt() != 1) {
                log.warn("Python AI 服务返回异常: userId={}, resp={}", userId, resp);
                throw new AuthException(MessageConstant.AI_SERVICE_UNAVAILABLE);
            }
            return resp.path("data").path("answer").asText();
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            // Python 服务未启动 / 超时 / 网络异常 / Feign 抛 FeignException 等 → 统一兜底
            log.error("调用 Python AI 服务失败: userId={}, question={}", userId, question, e);
            throw new AuthException(MessageConstant.AI_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 获取对话历史：调用 Python 服务 GET /history?userId=
     *
     * <p>Python 侧读取该用户的历史文件并返回最近 20 条（时间正序）。
     */
    @Override
    public List<AiMessageVO> history(Long userId) {
        try {
            JsonNode resp = aiFeignClient.history(userId);

            if (resp == null || resp.get("code") == null || resp.get("code").asInt() != 1) {
                log.warn("Python AI 服务历史接口异常: userId={}, resp={}", userId, resp);
                throw new AuthException(MessageConstant.AI_SERVICE_UNAVAILABLE);
            }

            // 将 Python 返回的 [{role, content}, ...] 转换为 VO 列表
            List<AiMessageVO> list = new ArrayList<>();
            JsonNode items = resp.path("data").path("list");
            for (JsonNode item : items) {
                list.add(new AiMessageVO(
                        item.path("role").asText(),
                        item.path("content").asText()));
            }
            return list;
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 Python AI 服务历史接口失败: userId={}", userId, e);
            throw new AuthException(MessageConstant.AI_SERVICE_UNAVAILABLE);
        }
    }
}
