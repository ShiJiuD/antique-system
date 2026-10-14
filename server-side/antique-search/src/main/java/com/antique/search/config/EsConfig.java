package com.antique.search.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Elasticsearch 客户端配置 — 搜索服务
 *
 * <h3>为什么不用 spring-boot-starter-data-elasticsearch</h3>
 * <p>Spring Boot 3.2 自带的 spring-data-elasticsearch 5.2 内部使用
 * Elasticsearch Java Client 8.x，面向 ES 8.x 服务端；而虚拟机上的是 ES <b>7.12.1</b>
 * （REST API 兼容模式要到 7.16 才引入），直接用会踩版本兼容问题。
 *
 * <p>同理也没用 {@code RestHighLevelClient}：它在 ES 7.15 已被标记废弃、8.0 彻底移除，
 * 且官方支持矩阵里没有「7.12 客户端 + Java 21」这个组合。
 *
 * <h3>搜索服务只需要这一个 Bean</h3>
 * <p>主服务里同时有数据源、Redis、RabbitMQ、Redisson 等一大堆自动配置；
 * 搜索服务的全部外部依赖就是这一个 HTTP 客户端 —— 这也是它能拆出来独立跑的原因。
 */
@Configuration
public class EsConfig {

    /** ES 地址（application.yml 的 es.url） */
    @Value("${es.url:http://127.0.0.1:9200}")
    private String esUrl;

    /**
     * ES 专用 HTTP 客户端
     *
     * <p>注：本服务只有一个 RestClient Bean，所以注入时按类型即可；
     * 将来若出现多个，需要用 {@code @Qualifier("esRestClient")} 限定。
     */
    @Bean
    public RestClient esRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        return RestClient.builder()
                .baseUrl(esUrl)
                .requestFactory(factory)
                .build();
    }
}
