package com.antique.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关访问日志过滤器 — 记录每个请求被转发到哪、耗时多少
 *
 * <h3>它是"全局过滤器"（GlobalFilter）</h3>
 * <p>和"路由过滤器"（配在某条 route 下的）不同，全局过滤器对<b>所有路由</b>生效，
 * 所以适合放横切关注点：访问日志、链路追踪、统一鉴权、限流计数。
 *
 * <h3>为什么用 then(...) 而不是在前后各写一段</h3>
 * <p>响应式的执行是"声明"而不是"顺序执行"：{@code chain.filter(exchange)} 返回的是
 * 一个 {@code Mono}，代表"后续的转发工作"。真正的转发发生在它被订阅之后。
 * 所以"请求完成后要做的统计"必须挂在 {@code .then(...)} 里 ——
 * 这行代码的意思是"转发完成后再执行"，而不是"立刻执行"。
 * 如果写成普通语句放在 chain.filter 后面，测量到的耗时会接近 0。
 *
 * <h3>getOrder() 为什么返回 -1</h3>
 * <p>数值越小越先执行。设成 -1 让它包在最外层，这样测到的是<b>整个网关处理过程</b>的耗时
 * （包含其他过滤器的开销），而不是某一段。
 */
@Slf4j
@Component
public class GatewayLogFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long cost = System.currentTimeMillis() - start;
            // 拿到响应状态码：这是"转发完成后"才能读到的值
            Integer status = exchange.getResponse().getStatusCode() == null
                    ? null : exchange.getResponse().getStatusCode().value();
            log.info("网关转发: {} {} -> {} ({}ms)", method, path, status, cost);
        }));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
