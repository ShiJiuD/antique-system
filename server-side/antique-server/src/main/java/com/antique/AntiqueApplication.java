package com.antique;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 古玩寄卖平台 — Spring Boot 启动类
 *
 * <p>负责启动内嵌 Tomcat、自动配置、扫描 MyBatis Mapper 接口。
 *
 * <h3>启动方式</h3>
 * <pre>{@code
 * mvn spring-boot:run
 * # 或
 * java -jar antique-server.jar
 * }</pre>
 *
 * <h3>前置条件</h3>
 * <ul>
 *   <li>MySQL 8.0+ 运行中，数据库名 antique</li>
 *   <li>Redis 运行中</li>
 *   <li>application.yml 中数据库密码配置正确</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling  // 开启定时任务（订单超时关单兜底）
@MapperScan("com.antique.mapper")  // 扫描 MyBatis Mapper 接口
@EnableFeignClients  // M5：扫描 com.antique 下的 @FeignClient 接口，生成远程调用代理
public class AntiqueApplication {

    public static void main(String[] args) {
        SpringApplication.run(AntiqueApplication.class, args);
    }
}
