# antique project — 工作区说明

> 古玩寄卖平台（antique-consignment-seckill-system）。
> 后端 Spring Boot 3.2 + MyBatis-Plus + MySQL + Redis + RabbitMQ；前端 Vue 3；
> 另有 `ai-side`（FastAPI + RAG 问答）与 `algorithm-side`（销量预测）两个 Python 服务。

---

## 1. 运行环境（2026-09 起）

### 云端共享中间件

阿里云 ECS（2核2G，公网 `101.132.106.91`），Docker Compose 部署在 `/opt/middleware`：

| 组件 | 说明 |
|---|---|
| MySQL 8.0 | 库：`antique` / `city_art` / `mini_app`；账号 `shijiu`（密码见 application.yml） |
| Redis 7 | `maxmemory 96mb`；database 0=antique、1=ciTY Art、2=小程序 |
| RabbitMQ 3.13 | 管理台 15672；MQ 用户 `shijiu` |
| Nacos 2.3 | **默认停用**（`profiles: ["nacos"]`，按需 `--profile nacos up -d`） |

### 本地通过 SSH 隧道连接（服务器只开放 22 端口）

**推荐方式：`cloud-deploy/tunnels.ps1`** —— 一条命令建立全部隧道，且断线自动重连：

```powershell
pwsh -File "cloud-deploy\tunnels.ps1"           # 建立隧道（保持窗口开着，Ctrl+C 结束）
pwsh -File "cloud-deploy\tunnels.ps1" -Check    # 只检查隧道是否通
```

备用方式：MobaXterm 的 MobaSSHTunnel 手工建。项目配置一律写 `127.0.0.1` + 映射端口：

| 服务 | 本地地址（项目里写这个） | 云端真实端口 |
|---|---|---|
| MySQL | `127.0.0.1:13306` | 3306 |
| Redis | `127.0.0.1:16379` | 6379 |
| RabbitMQ（AMQP，应用连这个） | `127.0.0.1:25672` | 5672 |
| RabbitMQ 管理台（浏览器） | `127.0.0.1:15673` | 15672 |

> ⚠️ **改配置时端口必须一起改**，不只是改 host。
> 这样做是因为开发机所在网络的公网 IP 会变，白名单方式会反复失效；隧道是出站连接，不受影响。

> ⚠️ **隧道没跑时的典型报错**：Redis 报 `Connection refused ... 127.0.0.1:16379`，
> MySQL 报 `HikariPool-1 - Exception during pool initialization`。
> 排查第一步永远是：`pwsh -File cloud-deploy/tunnels.ps1 -Check`

### 留在本地的部分

- `ai-side`（8001）、`algorithm-side`（8002）在本地跑，Java 侧用 `127.0.0.1` 调它们
- Nacos / ES 计划放 VMware 虚拟机（**NAT 模式 + 静态 IP**，不要桥接），不走公网
- ES 2G 云机放不下，坚持留本地

---

## 2. 当前进行中的改造

**M1 · MQ 行为流水**：浏览/下单埋点 → RabbitMQ → Redis 聚合计数 → 定时落库 `daily_stats` → 喂给预测服务。

- 已完成：`spring-boot-starter-amqp` 依赖、`spring.rabbitmq` 配置（指向 `127.0.0.1:25672`）
- 下一步：建 `daily_stats` 表 → 声明交换机/队列 → 写事件发布者与消费者 → 定时落库 → 预测闭环

后续计划：M2 ES 搜索、M3 订单事件总线 + Outbox、M4 ciTY 延迟关单、M5 Nacos + Gateway + Feign。

---

## 3. 相关文档

| 文档 | 位置 |
|---|---|
| 云端中间件部署与排错 | `cloud-deploy/README.md` |
| Compose 编排 | `cloud-deploy/docker-compose.yml` |
| 技术升级方案（M0~M5 全量） | 桌面《项目技术升级改进方案.md》 |
| 项目成果概览 | `PROJECT_OVERVIEW.md` |

---

## 4. 协作约定

- **输出默认用纯 Markdown（表格 / 列表 / 代码块），不要使用 dsh-ui 围栏** —— 该围栏在本项目中反复渲染失败，详见 `~/.dsh/AGENTS.md`
- 在 2G 云服务器上**不要执行 `mvn package`**（构建 JVM 会吃 500M+），本地打包好再上传
- 本地 Java 服务必须限堆：`-Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC`
- 改数据库结构时，同步维护 `server-side/sql/` 下的建表语句
- 写 Javadoc 时**注释里不能出现连续的星号加斜杠**（会提前终止块注释，导致编译失败）
