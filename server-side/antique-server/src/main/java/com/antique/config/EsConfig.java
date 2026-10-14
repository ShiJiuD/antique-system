package com.antique.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Elasticsearch 客户端配置 — M2 搜索
 *
 * <h3>为什么不用 spring-boot-starter-data-elasticsearch</h3>
 * <p>Spring Boot 3.2 自带的 spring-data-elasticsearch 5.2 内部使用
 * Elasticsearch Java Client 8.x，面向 ES 8.x 服务端；而虚拟机上是 ES <b>7.12.1</b>
 * （REST API 兼容模式要到 7.16 才引入），直接用会踩版本兼容问题。
 *
 * <p>同理也没用 {@code RestHighLevelClient}（课程里常见的那种写法）：它在 ES 7.15 已被标记废弃、
 * 8.0 彻底移除，且官方支持矩阵里没有「7.12 客户端 + Java 21」这个组合。
 *
 * <p>所以改用 Spring 自带的 {@link RestClient} 直接调 ES 的 REST API：
 * <ul>
 *   <li>零新增依赖（spring-web 已有）</li>
 *   <li>与 ES 版本无关</li>
 *   <li>查询 DSL 完全透明，便于理解倒排索引与相关性打分</li>
 * </ul>
 */
@Configuration
public class EsConfig {

    /** ES 地址（application.yml 的 es.url） */
    @Value("${es.url:http://127.0.0.1:9200}")
    private String esUrl;

    /**
     * ES 专用 HTTP 客户端
     *
     * <p>注：整个项目只有这一个 RestClient Bean，所以注入时按类型即可；
     * 将来若出现多个 RestClient，需要用 {@code @Qualifier("esRestClient")} 限定
     * （注意 Lombok 的 {@code @RequiredArgsConstructor} 默认不会把字段上的 @Qualifier
     * 复制到构造参数上，那时要手写构造函数）。
     */
    @Bean
    public RestClient esRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        // 全量同步是一次性批量请求，读超时给宽一点
        factory.setReadTimeout(30000);
        return RestClient.builder()
                .baseUrl(esUrl)
                .requestFactory(factory)
                .build();
    }
}
