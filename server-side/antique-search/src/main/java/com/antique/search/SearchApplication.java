package com.antique.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 搜索服务启动类 — M5 从单体中拆出的第一个服务
 *
 * <h3>它和主服务（antique-server）的关系</h3>
 * <pre>
 *   ┌──────────────────┐   Feign / Gateway    ┌──────────────────┐
 *   │  antique-server  │◀────────────────────▶│  antique-search  │
 *   │ 藏品 / 订单 / MQ  │                      │  ES 检索          │
 *   └──────────────────┘                      └──────────────────┘
 *          │                                          │
 *      MySQL/Redis                               Elasticsearch
 *      RabbitMQ                                   （虚拟机）
 *          └──────────────┬───────────────────────────┘
 *                         ▼
 *                   ┌──────────┐
 *                   │  Nacos   │  注册中心
 *                   └──────────┘
 * </pre>
 *
 * <h3>为什么这里不写 @MapperScan</h3>
 * <p>搜索服务不碰数据库 —— 它只从 ES 读数据。没有 Mapper 要扫描，
 * 自然也不需要数据源配置，所以启动比主服务快得多、也轻得多。
 *
 * <h3>⚠️ 为什么必须 exclude 掉 DataSourceAutoConfiguration</h3>
 * <p>这是个不写就<b>启动直接失败</b>的坑：
 * <ul>
 *   <li>本模块依赖 {@code antique-pojo} 复用卡片 VO，而 pojo 里带了
 *       {@code mybatis-plus-spring-boot3-starter}（实体类要用它的注解）</li>
 *   <li>MP starter 会传递引入 {@code spring-boot-starter-jdbc}</li>
 *   <li>Spring Boot 一看 classpath 上有 JDBC，就<b>认定你要连数据库</b>，
 *       于是尝试创建 DataSource —— 可我们根本没配 url，于是报：
 *       {@code Failed to configure a DataSource: 'url' attribute is not specified}</li>
 * </ul>
 * <p>排除掉这个自动配置就解决了。MyBatis-Plus 自己的自动配置带
 * {@code @ConditionalOnSingleCandidate(DataSource.class)}，没有 DataSource Bean 时会自动退让，
 * 所以只排这一个即可。
 *
 * <h3>@EnableDiscoveryClient 说明</h3>
 * <p>Spring Cloud 2023 起，只要 classpath 上有注册中心 starter，这个注解已经不是必需的。
 * 这里显式写出来是为了让意图清楚：<b>这个服务要注册到 Nacos</b>。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableDiscoveryClient
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
