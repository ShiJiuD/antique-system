package com.antique.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关启动类 — M5 统一入口
 *
 * <h3>网关在整体架构里的位置</h3>
 * <pre>
 *   前端 / 小程序
 *        │  只认一个地址：http://localhost:9000
 *        ▼
 *   ┌──────────────┐
 *   │   Gateway    │  ← 按路径前缀分发，前端不需要知道有几个服务
 *   └──────┬───────┘
 *          │
 *   ┌──────┴───────────────┐
 *   │ /api/search/**       │ /api/**（其余）
 *   ▼                      ▼
 * antique-search       antique-server
 * (8081, ES 检索)      (8080, 藏品/订单/MQ)
 * </pre>
 *
 * <h3>网关带来的三个变化</h3>
 * <ol>
 *   <li><b>前端只连一个地址</b>：加服务、拆服务、换端口，前端都不用改</li>
 *   <li><b>跨域在一处解决</b>：不用每个服务各配一份 CORS</li>
 *   <li><b>横切关注点集中</b>：鉴权、限流、日志、灰度，将来都加在这一层</li>
 * </ol>
 *
 * <h3>它是响应式的，和业务服务不是一类应用</h3>
 * <p>业务服务是 Spring MVC（一个请求一个线程，Tomcat 线程池）；
 * 网关是 WebFlux + Netty（事件循环，少量线程扛住大量连接）。
 * 网关的工作只是"转发"，不查库、不算业务，所以用响应式最划算。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
