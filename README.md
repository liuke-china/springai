# Spring AI 实战项目（Java 后端转 AI 应用岗作品集）

> 技术栈：Spring Boot 3.5 + Java 21 + Spring AI 1.1.4 + PostgreSQL / PGVector（向量扩展）
> 大模型：**Chat = MiniMax M3**；**Embedding = MiniMax embo-01（1536 维，复用同一个 MINIMAX_API_KEY）**
> 定位：每个能力都跑通真实业务，非玩具 demo。覆盖 RAG / Agent / 多 Agent / 安全护栏 / 可观测性。

---

## 项目结构总览

| 包（package） | 职责 | 核心类 |
|---|---|---|
| `rag` | RAG（检索增强生成）企业级流水线 | `EnterpriseRagPipeline`、`QueryAugmenter`、`Bm25Retriever` |
| `agent` | Agent（智能体，ReAct 循环） | `NaturalLanguageQueryAgent`、`NaturalLanguageQueryController`、`AgentAction`、`ToolObservation`、`NaturalLanguageQueryResult` |
| `safe` | 安全层（基于 OWASP Top 10 for LLM 落地） | `SecurityAdvisor`、`PromptInjectionDetector`、`SafeSqlValidator` 等 13 个类 |
| `memory` | 双轨记忆（JDBC 精确窗口 + PGVector 长期） | `ImsAskController`、`VectorMemoryAdvisor` |
| `multiagent` | 多 Agent 协作（规划-执行-评审闭环） | `Orchestrator`、`Planner`、`Executor`、`Critic`、`MultiAgentController` |
| `smalldemo` | 基础 demo（对话 / 工具 / 结构化输出 / TextToSql / RAG 控制器 / 企业模板） | `BasicAiController`、`TextToSqlController`、`RagController`、`EnterpriseTemplatesController` |

## 主要接口（endpoint）一览

| 接口 | 类型 | 说明 |
|---|---|---|
| `/ai/agent/query` | Agent（智能体） | 自然语言查数据，走 ReAct 循环 + 工具（需 IMS，见下方可选说明） |
| `/ai/ims/ask` | RAG（检索增强生成） + 安全 | IMS 问答 bot（向量检索 + 安全护栏，需 IMS） |
| `/ai/rag/query` | RAG（检索增强生成） | 文档知识库问答 |
| `/ai/sql/query` | 单工具 TextToSQL | 文本转 SQL（需 IMS 只读库） |
| `/ai/multi/query` | 多 Agent 协作 | Planner 拆解 → Executor 复用单 Agent 工具链 → Critic 评审，闭环至多轮 |
| `/ai/enterprise/*` | 企业模板（7 个 GET） | SQL 生成 / 代码解释 / 数据分析 / 客服 / 内容审核 / 文本分类 / JSON 抽取 |
| `/ai/safe` | 纯对话 demo | 仅演示 try-catch 包裹基础调用，不连库 |

---

## 快速开始

### 1. 前置依赖

| 依赖 | 版本要求 | 说明 |
|---|---|---|
| JDK | 21+ | 项目用 Java 21 语法 |
| Maven | 3.9+ | 构建与运行 |
| PostgreSQL | 14+ | 向量库 / 聊天记忆 / 审计表 |
| PGVector 扩展 | 0.5+ | 向量检索能力 |
| MiniMax API Key | — | Chat 与 Embedding 共用，**唯一必填密钥** |

> 没装 PG？用 Docker 一条命令起（数据落在 `./pgdata`）：
> ```bash
> docker run -d --name pg -p 5432:5432 -e POSTGRES_PASSWORD=123456 -v "$PWD/pgdata":/var/lib/postgresql/data pgvector/pgvector:pg17
> ```
> 本机已装 PG 的跳过这步。扩展在建表时由应用自动 `CREATE EXTENSION` 启用。

### 2. 准备向量扩展（仅需一次）

应用启动时会自动初始化 `chat_memory` / `chat_document` / `vector_store` 等表；但 PGVector 扩展需手动启用一次（有 `CREATE EXTENSION IF NOT EXISTS` 权限的账号执行）：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### 3. 配置环境变量

项目**不含任何明文密钥**。复制示例文件并填入你的 Key：

```bash
cp .env.example .env
# 编辑 .env，至少填 MINIMAX_API_KEY=你的Key
```

| 环境变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `MINIMAX_API_KEY` | ✅ 必填 | 无 | Chat + Embedding 共用，没有应用起不来 |
| `SILICONFLOW_API_KEY` | 可选 | 空（降级内置重排） | RAG 的 Cross-Encoder 重排（bge-reranker-v2-m3），不填也能跑 |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 可选 | 空（走"模拟发送"） | 邮件工具演示，不填也能跑 |
| `SPRING_DATASOURCE_URL` | 可选 | `jdbc:postgresql://localhost:5432/postgres` | 写入数据源 |
| `SPRING_DATASOURCE_USERNAME` | 可选 | `postgres` | — |
| `SPRING_DATASOURCE_PASSWORD` | 可选 | `123456` | 本地开发默认，按需改 |
| `READONLY_DB_URL` / `_USERNAME` / `_PASSWORD` | 可选 | 同写入库 / `ims_readonly` / `123456` | Text-to-SQL 只读库 |
| `IMS_BASE_URL` / `IMS_TOKEN` | 可选 | `http://localhost:8080` / 空 | **IMS 内部系统，需内网权限** |
| `APP_SECURITY_BLOCK_MALICIOUS` | 可选 | `true` | 是否拦截恶意提示注入 |
| `APP_SECURITY_ADVISOR_ENABLED` | 可选 | `true` | 安全 Advisor 总开关 |
| `APP_OPS_ENDPOINTS_ENABLED` | 可选 | `false` | 运维/知识库管理端点是否暴露 |

### 4. 启动

```bash
mvn spring-boot:run
# 默认端口 8081
```

启动成功后访问 `http://localhost:8081/actuator/health` 应返回 `UP`。

### 可选：IMS（内部系统）说明

`/ai/agent/query`、`/ai/ims/ask`、`/ai/sql/query` 依赖公司内部的 **IMS** 系统（设备库 / 接口 / token）。
**外部使用者没有 IMS 权限也能跑全部通用 AI 能力**——对话、RAG 文档问答、多 Agent、安全护栏、7 个企业模板接口都能用。
只有填了 `IMS_BASE_URL` + `IMS_TOKEN`（指向你自己的后端）后，IMS 相关接口才会真正查询数据。相关代码保留在仓库，配置全部走环境变量占位，不泄露任何内部地址或凭证。

---

## 常见问题（排错）

**Q：启动直接失败，报 `OpenAI API key must be set` 或 `EmbeddingConfig: MiniMax embedding 需要 api-key`？**
A：忘了配 `MINIMAX_API_KEY`。本项目密钥全外部化，不在代码里。按"快速开始 → 3. 配置环境变量"把 `.env.example` 复制成 `.env` 并填入你的 Key 即可（Spring Boot 3.2+ 会自动加载根目录 `.env`，无需 `export`）。注意此 Key 是 **Chat 与 Embedding 共用**的必填项，没有它就起不来。

**Q：接口返回 400 Bad Request（Tomcat 默认错误页）？**
A：URL 里带了 Tomcat 默认拒绝的字符（如 `{}` `[]` `|` `<>`）。本项目已通过 `server.tomcat.relaxed-query-chars` 放行这些字符，正常浏览器调用没问题；但用 `curl` 测时要注意：①空格必须写成 `%20`；②`{}` 会被 curl 当成通配符，需加 `-g` 关闭 glob，或用 `--data-urlencode` 自动编码：
```bash
curl -G "http://localhost:8081/ai/enterprise/code-explain" --data-urlencode "code=public void test(){}"
```

**Q：启动报 `vector` 扩展相关错误 / RAG 接口查不到内容？**
A：PG 上先执行一次 `CREATE EXTENSION IF NOT EXISTS vector;`（见"快速开始 → 2"）。扩展没启用，PGVector 表建不出来。

---

## 第一层：RAG（检索增强生成）

核心思路：让 LLM 回答前先检索外部资料，再把资料作为上下文生成答案，专治幻觉（hallucination）和知识过时。

| 阶段 | 做什么 | 实现类 / 方式 | 工程价值 |
|---|---|---|---|
| 1 分块 Chunking | 长文档切小块 | 递归字符分割 + 滑动重叠(overlap=150) | 避免句子被切两半丢语义 |
| 2 嵌入 Embedding | 文本 → 1536 维向量 | MiniMax embo-01 | 把文本变成"坐标点"，才能按距离检索 |
| 3 存储 Storage | 向量 + 原文落库 | PGVector 独立表 `chat_document` + HNSW 索引 | 持久化、重启不丢、多实例共享 |
| 4 检索 Retrieval | 找最相关片段 | 查询改写 + HyDE（条件触发）+ 向量+BM25 混合 + RRF 融合 + Cross-Encoder 重排 | 单路召回不准，混合+重排把真正相关的顶上来 |
| 5 生成 Generation | 基于片段作答 | 接地 grounding + 引用标注【文档N】+ faithfulness 护栏 | 防幻觉，找不到就明说"资料里没有" |

评估：RAGAS 指标——faithfulness、context_recall。

**面试钩子**：HyDE 与重排都不是每次都跑——top1 弱召回才触发 HyDE，省延迟省成本。

---

## 第二层：Agent（智能体）

核心思路：ReAct（推理+行动）范式——Reason → Act（调工具）→ Observe，循环至模型输出 ANSWER 才停。下一步由模型决定，而非程序员写死。

| 维度 | 实现 | 工程价值 |
|---|---|---|
| 决策接口 | `DecisionMaker` 函数式接口，`this::askModel` 注入（策略模式） | 真实问 LLM / 测试注入 stub 解耦 |
| 工具 Tool | `FIND_SCHEMA` / `RUN_SQL` / `DEVICE_STATUS` | 真实查数据 |
| 防死循环 | maxSteps 上限 + ANSWER 终止标记 + 工具失败当观察喂回 | 不卡死 |

**面试钩子**：Agent 与普通 LLM 调用的区别——普通只靠训练数据会编 SQL（幻觉），Agent 是真跑工具拿到真实结果再作答（接地 grounded）。

---

## 第三层：安全（Security，基于 OWASP Top 10 for LLM）

以 Advisor 模式嵌入链路——before 注入隔离 / after 检测泄露，默认全开（满足上生产前安全包默认开）。

| 防护点（OWASP 映射） | 实现类 | 说明 |
|---|---|---|
| LLM01 提示注入 | `PromptInjectionDetector` + `SecurityConstants` | 正则+打分，强命中即拦截 |
| LLM01 输入清洗 | `InputSanitizer` | 输入包裹隔离 |
| LLM02 输出泄露 | `OutputSanitizer` + `SensitiveDataPatterns` | 密钥正则脱敏 |
| LLM05/06 SQL 安全 | `SafeSqlValidator` | 禁堆叠查询 + 表白名单 |
| LLM08 限流 | `RateLimitInterceptor` + `TokenBucket` | 令牌桶限流 |
| LLM06/08 审计 | `AuditLogFilter` | 只记元数据不记 body |
| LLM03 最小权限 DB | `DbLeastPrivilegeConfig` | 只读账号 DataSource |
| 全局挂载 | `SecurityAdvisor` + `SafeWebConfig` | before 拦截 / after 检测 |
| 红队测试 Red Team | `SecurityRedTeamTest` | 9 用例全过 |

---

## 第四层：多 Agent 协作

核心思路：单个 Agent 自己选工具；多个 Agent 则"分工 + 交接（handoff）+ 评审闭环"。本实现用三个独立角色 + 一个编排者：

| 角色 | 职责 | 是否调工具 | 复用关系 |
|---|---|---|---|
| `Planner`（规划者） | 把问题拆成可独立执行的子任务 | 否 | 独立 |
| `Executor`（执行者） | 逐个子任务执行 | 是，委托已有的单 Agent `run` | **直接复用**单 Agent 的 ReAct 循环 + 工具链 |
| `Critic`（评审者） | 判断子任务回答是否完整正确 | 否 | 独立 |
| `Orchestrator`（编排者） | 串联三者，带 maxRounds 上限防死循环 | 否 | 装配以上三者 + ChatMemory |

闭环：`Planner` 出计划 → `Executor` 执行 → `Critic` 评审 → 不通过则把 `feedback` 回灌 `Planner` 重新规划 → 直到通过或达到 `maxRounds`。

**面试钩子**：多 Agent 不是堆 Agent 数量，而是"职责分离 + 可验证的交接协议 + 评审门禁"——Critic 不通过就重规划，比"一次性调用"鲁棒。

---

## 调用示例

- 企业模板（7 个 GET，参数含 `{}` `[]` 也不会 400）：
  ```bash
  curl -G "http://localhost:8081/ai/enterprise/code-explain" --data-urlencode "code=public void test(){}"
  ```
- Agent：`POST /ai/agent/query` `{"question":"设备现在有多少台","schema":"public","maxSteps":4}`
- 多 Agent：`POST /ai/multi/query` `{"question":"加工中的设备有多少台","schema":"public","maxSteps":3,"maxRounds":2}`
- IMS：`POST /ai/ims/ask` `{"question":"运行中的设备有多少","sessionId":"demo"}`

---

## 后续可拓展

- Agentic RAG / Self-RAG / GraphRAG / 流式 RAG
- 多 Agent 增强：引入 handoff 显式定义角色间传递格式、Critic 给出可量化评分而非布尔

> 部署到公开仓库前请务必 **轮换所有曾提交过的密钥**（MiniMax Key、163 邮箱授权码、IMS Token），因为旧仓库历史里曾含明文。
