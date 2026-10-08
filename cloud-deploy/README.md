# 云端共享中间件部署指南（阿里云 2核2G）

> **定位**：一台服务器上跑一套中间件，**所有项目共用**——antique 古玩、ciTY Art、恋爱小程序，以及以后的新项目。
> **覆盖**：MySQL 8 / Redis 7 / RabbitMQ 3.13（Nacos 2.3 按需启动；ES 留在本地）。
> **实测环境**：阿里云 ECS，Alibaba Cloud Linux 3.2104（RHEL 8 系），1.8G 内存 / 40G 磁盘。
> 整理日期：2026-09（含实际部署中踩到的两个坑及解法）

---

## 目录

1. [设计约定（为什么这样命名与分层）](#1-设计约定)
2. [内存预算](#2-内存预算)
3. [从零部署：6 步](#3-从零部署6-步)
4. [账号 / 端口 / 验证 一览](#4-账号--端口--验证-一览)
5. [按需启停（profiles）](#5-按需启停profiles)
6. [新项目接入清单](#6-新项目接入清单)
7. [ES 留本地](#7-es-留本地)
8. [常见问题（含实测踩坑）](#8-常见问题含实测踩坑)

---

## 1. 设计约定

**核心原则：中间件实例是通用的，永远只有一套；项目隔离靠逻辑单位。**

| 层面 | 值 | 说明 |
|---|---|---|
| 容器名 | `mysql` / `redis` / `rabbitmq` / `nacos` | 只描述「它是什么」，**不带任何项目名** |
| 部署目录 | `/opt/middleware` | 通用 |
| 登录账号 | `shijiu` / `060824` | 所有项目共用 |
| 项目隔离 | MySQL 库 / Redis database 索引 / RabbitMQ vhost / Nacos namespace | 按项目分 |

> 新项目接入**不需要动 compose、不需要重装中间件**，只需在该中间件里"开一个新的逻辑单位"（建库/建 vhost 等）——见第 6 节。

---

## 2. 内存预算

实测数据（2 核 1.8G 机器）：

| 组件 | 实测/估算 | 是否常驻 |
|---|---|---|
| Linux 系统 + Docker | ~350M | 常驻 |
| MySQL（buffer pool 256M） | 375M | ✅ 常驻 |
| RabbitMQ（mem_limit 512M） | 125M | ✅ 常驻 |
| Redis（maxmemory 96M） | 13M | ✅ 常驻 |
| **三个主力小计** | **~860M** | — |
| Nacos（约需 500M） | ~500M | ⏸ **按需**（`--profile nacos`） |
| ES（至少 1G） | — | 🏠 **留本地** |
| 一个 Java 服务（`-Xmx256m`） | ~350M | 按需 |
| 两个 Java 服务 + Vue(Nginx) | ~715M | 三个中间件 + 两个服务 ≈ 1.58G，紧但可行 |

**结论**：三个主力常驻绰绰有余（~860M / 1.83G）；Nacos 属于"重量级"，按需启停；ES 放不下。

---

## 3. 从零部署：6 步

### 第 0 步：建 2G Swap（必做）

```bash
fallocate -l 2G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' | tee -a /etc/fstab
free -h          # 确认 Swap 那行是 2.0Gi
```

### 第 1 步：装 Docker + Compose（Alibaba Cloud Linux 3 / RHEL 8 系）

```bash
dnf install -y dnf-plugins-core
dnf config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
sed -i 's/\$releasever/8/g' /etc/yum.repos.d/docker-ce.repo    # Alinux3 版本号是 3，源目录只有 7/8/9
dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
systemctl enable --now docker

docker version            # Client + Server 两段都有
docker compose version    # 必须是 v2.x（v1 不支持本文件的写法）
```

### 第 2 步：配置镜像加速器（⚠️ 国内必做）

Docker Hub（`registry-1.docker.io`）在国内**基本连不上**，直接 `up -d` 会报
`request canceled while waiting for connection (Client.Timeout exceeded)`。

```bash
mkdir -p /etc/docker
cat > /etc/docker/daemon.json << 'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1panel.live"
  ]
}
EOF

systemctl daemon-reload
systemctl restart docker
docker info | grep -A3 "Registry Mirrors"     # 确认生效
```

> 如果配了加速器仍拉不动，可在 compose 里把镜像名显式改写成镜像站前缀，例如
> `image: m.daocloud.io/docker.io/library/mysql:8.0`。
> 最后的保底方案：本地 Docker Desktop 拉好 → `docker save` → 上传 → `docker load`。

### 第 3 步：放置 compose 文件与 .env

```bash
mkdir -p /opt/middleware && cd /opt/middleware

cat > .env << 'EOF'
MYSQL_ROOT_PASSWORD=060824
REDIS_PASSWORD=060824
RABBITMQ_USER=shijiu
RABBITMQ_PASSWORD=060824
EOF
```

`docker-compose.yml` 见本目录同名文件（通用容器名 + Nacos 按需）。

### 第 4 步：启动

```bash
cd /opt/middleware
docker compose up -d                    # 起 mysql + redis + rabbitmq（Nacos 默认不起）
docker compose ps                       # 期望 3 个 Up (healthy)
```

### 第 5 步：建库 + 建账号

```bash
docker exec -i mysql mysql -uroot -p060824 << 'SQL_EOF'
CREATE DATABASE IF NOT EXISTS antique   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS city_art  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mini_app  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'shijiu'@'%' IDENTIFIED BY '060824';
GRANT ALL PRIVILEGES ON antique.*  TO 'shijiu'@'%';
GRANT ALL PRIVILEGES ON city_art.* TO 'shijiu'@'%';
GRANT ALL PRIVILEGES ON mini_app.* TO 'shijiu'@'%';

FLUSH PRIVILEGES;
SHOW DATABASES;
SQL_EOF
```

> **为什么 root 不能从外部连**：官方 mysql:8.0 镜像默认只创建 `root@localhost`（仅容器内可用），
> 所以外部一律用 `shijiu@'%'` 这个账号。

### 第 6 步：安全组放行 + 本地验证

阿里云控制台 → 实例 → **安全组/防火墙** → 入方向，添加规则。**来源只填你自己的公网 IP**
（`curl ifconfig.me` 查到），不要用 `0.0.0.0/0`。

| 端口 | 服务 | 必要性 |
|---|---|---|
| 3306 | MySQL | ✅ 必须（本地项目连接） |
| 6379 | Redis | ✅ 必须 |
| 5672 | RabbitMQ AMQP | ✅ 接 MQ 时 |
| 15672 | RabbitMQ 管理台 | ✅ 调试用 |
| 8848 | Nacos | ⬜ 用微服务时 |
| 22 | SSH | ✅ 已有 |

---

## 4. 账号 / 端口 / 验证 一览

| 服务 | 地址 | 用户名 | 密码 |
|---|---|---|---|
| MySQL | `<公网IP>:3306`，库 antique / city_art / mini_app | `shijiu` | `060824` |
| MySQL（容器内管理） | — | `root` | `060824` |
| Redis | `<公网IP>:6379` | 不填（或 `default`） | `060824` |
| RabbitMQ | `<公网IP>:5672`（程序）/ `:15672`（管理台） | `shijiu` | `060824` |
| Nacos | `<公网IP>:8848/nacos` | 无需登录 | — |

```bash
# 验证命令
docker compose ps
docker exec -it mysql mysql -uroot -p060824 -e "SELECT VERSION();"   # 8.0.x
docker exec -it redis redis-cli -a 060824 ping                       # PONG
docker stats --no-stream                                             # 看各容器内存
free -h                                                              # 看内存 + Swap
```

---

## 5. 按需启停（profiles）

Nacos 打了 `profiles: ["nacos"]` 标记，**默认不启动**（它约需 500M，是 2G 机器上最重的一个）。

```bash
cd /opt/middleware

docker compose up -d                     # 日常：只起三个主力
docker compose --profile nacos up -d     # 需要微服务时：额外起 nacos
docker compose stop nacos                # 用完停掉，立即释放约 500M
docker compose ps                        # 看当前跑了哪些
```

**以后加重型中间件（Kafka、RocketMQ 等）也照这个模式**：加 service 块 + 打 profiles 标记，
它们平时"躺着"，只有显式指定 profile 才占内存。

---

## 6. 新项目接入清单

新项目（例如 `newproj`）要接入这套共享中间件，**不用改 compose、不用重启任何服务**：

```bash
# ① MySQL：建库 + 授权（5 秒）
docker exec -i mysql mysql -uroot -p060824 << 'SQL_EOF'
CREATE DATABASE IF NOT EXISTS newproj DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON newproj.* TO 'shijiu'@'%';
FLUSH PRIVILEGES;
SQL_EOF

# ② Redis：换个 database 索引（0~15）做隔离
#    在项目配置里写 spring.data.redis.database: 3
#    约定：antique=0、ciTY=1、小程序=2、以后依次递增

# ③ RabbitMQ：直接用 shijiu 账号；需要隔离时建 vhost
docker exec -it rabbitmq rabbitmqctl add_vhost newproj
docker exec -it rabbitmq rabbitmqctl set_permissions -p newproj shijiu ".*" ".*" ".*"

# ④ Nacos：控制台里建一个 namespace（用微服务时）
```

项目侧配置模板：

```yaml
spring:
  datasource:
    url: jdbc:mysql://<公网IP>:3306/newproj?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: shijiu
    password: 060824
  data:
    redis:
      host: <公网IP>
      password: 060824
      database: 3
  rabbitmq:
    host: <公网IP>
    port: 5672
    username: shijiu
    password: 060824
    listener:
      simple:
        acknowledge-mode: manual      # 手动 ack（默认是 auto，需显式改）
```

---

## 7. ES 留本地

2G 机器**放不下 ES**（至少需 1G，且数据量再小内存也不会降——JVM 堆启动即锁定），所以：

```bash
# 本地 Docker Desktop 拉起来（只做搜索功能时启动）
docker run -d --name es-antique -p 9200:9200 -e discovery.type=single-node \
  -e ES_JAVA_OPTS="-Xms512m -Xmx512m" docker.elastic.co/elasticsearch/elasticsearch:7.17.2
```

Java 连 `localhost:9200`，代码无需感知云端。

---

## 8. 常见问题（含实测踩坑）

### 8.1 拉镜像超时（已解决）

**现象**：`docker compose up -d` 报
`Get "https://registry-1.docker.io/v2/": request canceled while waiting for connection`。

**原因**：国内访问 Docker Hub 受限。

**解法**：第 2 步配置镜像加速器（`https://docker.m.daocloud.io`），配完 `systemctl restart docker` 再 `up -d`。

---

### 8.2 Nacos 启动卡住 + 系统负载飙到 15（已解决）

**现象**：
- `docker compose ps` 里 nacos 长时间 `health: starting`；
- `docker stats` 显示 nacos 内存 `398.6MiB / 400MiB = 99.66%`（顶到限额）；
- `uptime` 显示 `load average: 15.05`（2 核机器正常应 < 2）；
- nacos 容器 BLOCK I/O 高达 21.4GB（疯狂读盘）。
- `free -h` 出现 Swap 被占用。

**原因**：Nacos 的 JVM 堆 256M + 元空间 + 线程栈 + 内嵌 Derby ≈ **实际需要 500M**，
而我最初把容器限额设成了 **400M**，导致它被卡在内存上限、反复换页/读盘。

**解法（两种）**：

1. **按需启停**（推荐，已采用）：Nacos 只在做微服务时启动，平时 `profiles` 不激活——
   现在 compose 里已把它标成 `profiles: ["nacos"]`，并把 `mem_limit` 从 400M 提到 **768M**；
2. 若确认是 healthcheck 里 `curl` 不存在导致的"假不健康"，把 healthcheck 改成
   `test: ["CMD-SHELL", "curl -fs http://localhost:8848/nacos/ >/dev/null || exit 1"]`
   或直接去掉 healthcheck（不影响使用）。

**验证恢复**：停掉 Nacos 后，内存从 1.1Gi 降到 822Mi，load 从 15.05 降到 4.10 并持续回落。

---

### 8.3 其他

| 现象 | 处理 |
|---|---|
| `no configuration file provided` | 没在 `/opt/middleware` 目录下执行 `docker compose`，先 `cd` 过去 |
| 本地连不上云 MySQL | 先 `curl ifconfig.me` 看当前 IP，回安全组更新来源 IP |
| 报 `Host '...' is not allowed to connect` | 用了 `root` 从外部连——改用 `shijiu` 账号 |
| 报 `Access denied for user 'shijiu'` | 密码错，或没执行第 5 步的建账号 SQL |
| 想重置某个中间件 | `docker compose rm -sf <服务名> && docker compose up -d <服务名>` |
| 磁盘/内存吃紧 | `docker compose stop nacos`；重型中间件全部走 profiles 按需 |
| 在 2G 机器上编译 Java | ❌ 不要在服务器上 `mvn package`（构建 JVM 会吃 500M+），**本地打包好 jar 再上传** |
| Java 服务内存爆掉 | 必须显式限堆：`-Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC`（默认堆是物理内存的 1/4 ≈ 450M，两个就爆） |
