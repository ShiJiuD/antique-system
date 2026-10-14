package com.antique.client;

import com.antique.result.Result;
import com.antique.vo.AntiqueCardVO;
import com.antique.vo.PageResultVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 搜索服务的 Feign 客户端 — M5 服务间调用
 *
 * <h3>它是怎么找到搜索服务的</h3>
 * <pre>
 *   @FeignClient(name = "antique-search")
 *        │  ① 拿服务名去问 Nacos："antique-search 在哪？"
 *        ▼
 *   Nacos 返回实例列表 [192.168.x.x:8081]
 *        │  ② 负载均衡挑一个（现在只有一个实例）
 *        ▼
 *   把方法调用翻译成 HTTP：GET http://192.168.x.x:8081/api/search/related?antiqueId=1
 * </pre>
 * <p><b>全流程没有硬编码任何 IP 和端口</b> —— 这正是注册中心存在的意义。
 * 搜索服务扩容成 3 个实例、或者换机器换端口，主服务这边一行都不用改。
 *
 * <h3>name 必须和对方的 spring.application.name 完全一致</h3>
 * <p>对方配的是 {@code spring.application.name: antique-search}，
 * 这里写错一个字，启动时不报错，调用时才报
 * {@code Load balancer does not contain an instance for the service xxx}。
 *
 * <h3>path 的作用</h3>
 * <p>{@code path = "/api/search"} 会让本接口里所有方法的路径都拼上这个前缀，
 * 方法上只写 {@code /related} 即可 —— 避免每个方法都重复一遍前缀。
 */
@FeignClient(name = "antique-search", path = "/api/search")
public interface SearchFeignClient {

    /**
     * 搜索藏品（转调搜索服务）
     *
     * <p>注：返回值直接声明成对方接口的返回类型 {@code Result<...>}。
     * Feign 只负责发 HTTP 和反序列化，它<b>不会</b>因为对方返回 code=0 就抛异常 ——
     * 所以调用方必须自己检查 {@code code}，见 {@code AntiqueServiceImpl.search}。
     */
    @GetMapping("/antique")
    Result<PageResultVO<AntiqueCardVO>> searchAntique(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size);

    /**
     * 相关藏品推荐（详情页用）
     *
     * @param antiqueId 当前藏品 ID
     * @param size      返回条数
     */
    @GetMapping("/related")
    Result<List<AntiqueCardVO>> findRelated(
            @RequestParam("antiqueId") Long antiqueId,
            @RequestParam(value = "size", required = false) Integer size);
}
