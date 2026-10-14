# Elasticsearch 索引定义（M2 搜索）

本目录存放 ES 索引的**结构定义**，和 `server-side/sql/` 存放建表语句是同一个定位：

> **结构定义随代码走版本管理** —— 换机器、重建环境时，照着文件执行即可，不靠记忆。

## 文件清单

| 文件 | 对应索引 | 说明 |
|---|---|---|
| `antique-index.json` | `antique` | 藏品搜索索引（中文用 IK 分词） |

## 为什么单独放 json 文件，而不是写在 Java 代码里

- 能直接被 `curl --data-binary @文件` 使用，**不需要编译、不需要启动应用**
- JSON 有语法高亮和格式校验；写成 Java 字符串常量容易漏括号、写错引号
- 是**唯一事实来源**：运维脚本、文档、将来的 CI 都引用同一个文件

## 一、创建索引

```bash
# 在虚拟机上执行（也可以用 PowerShell 的 Invoke-RestMethod 从 Windows 发）
curl -X PUT "http://localhost:9200/antique" \
     -H 'Content-Type: application/json' \
     --data-binary @antique-index.json
```

期望返回：

```json
{"acknowledged":true,"shards_acknowledged":true,"index":"antique"}
```

### ⚠️ 这一步不能跳过

**ES 默认会自动创建索引**（`action.auto_create_index` 默认为 `true`）。
如果跳过这步直接灌数据，ES 会用**动态推断**的 mapping 悄悄把索引建出来：

| 字段 | 我们设计的 | ES 自动推断的 |
|---|---|---|
| `title` | `ik_max_word`（切成词） | 默认分词器（**中文切成单个汉字**） |
| `dynasty` / `material` | text + keyword 双字段 | 只有一个 text |
| `price` | `scaled_float` 精确到分 | `float`，可能丢精度 |
| 分片 / 副本 | 1 / 0（单节点绿色） | 默认 1 / 1（单节点永远 yellow） |

**而且它不报错** —— 等你发现"搜'青花瓷'搜出一堆无关结果"时，数据已经全灌进去了。

所以应用的 `EsSyncServiceImpl.fullSync()` 里加了一道 `HEAD /antique` 前置检查：
索引不存在就直接抛异常，宁可失败也不让 ES 悄悄建出错误结构。

## 二、重建索引（改了字段设计时）

⚠️ **分片数、字段类型、分词器建好之后都无法修改**（MySQL 可以 `ALTER TABLE`，ES 不行），
只能删掉重建 + 搬数据：

```bash
# 1.（可选）先把老数据搬到临时索引，避免丢数据
curl -X PUT "http://localhost:9200/antique_backup" -H 'Content-Type: application/json' \
     --data-binary @antique-index.json
curl -X POST "http://localhost:9200/_reindex" -H 'Content-Type: application/json' -d '
{ "source": {"index": "antique"}, "dest": {"index": "antique_backup"} }'

# 2. 删除旧索引
curl -X DELETE "http://localhost:9200/antique"

# 3. 用新定义重建
curl -X PUT "http://localhost:9200/antique" -H 'Content-Type: application/json' \
     --data-binary @antique-index.json

# 4. 重新灌数据（调用应用的同步接口）
#    POST http://localhost:8080/api/es/sync/full
```

> 本项目数据量小（60 条），实际做法更简单：直接删掉重建，然后调一次全量同步接口即可。

## 三、查看当前索引的真实结构

```bash
curl -s "http://localhost:9200/antique/_mapping?pretty"     # 字段定义
curl -s "http://localhost:9200/antique/_settings?pretty"    # 分片/副本
curl -s "http://localhost:9200/_cat/indices?v"              # 所有索引概览
```

浏览器直接打开 `http://192.168.25.100:9200/antique/_mapping?pretty` 也能看（GET 请求浏览器可以发）。

## 四、验证分词效果

```bash
curl -s -X POST "http://localhost:9200/_analyze" -H 'Content-Type: application/json' \
  -d '{"analyzer":"ik_max_word","text":"青花瓷瓶"}'
```

预期切成：`青花瓷 / 青花 / 瓷瓶`（而不是 `青/花/瓷/瓶` 四个单字）。
