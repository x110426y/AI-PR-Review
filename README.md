# AI PR Review 助手 (全栈)

基于 **Spring Boot 3.2 + Spring AI + Vue 3** 的智能 Pull Request 代码审查助手，已实现 **Hybrid Search（稠密 + 稀疏）+ RRF（倒数排列融合）** 的完整 RAG 架构。前端提供简洁流式界面，后端自动获取 GitHub PR 变更内容，滑动窗口分片后通过虚拟线程并行调用大模型，并通过双路向量检索将团队编码规范动态注入 Prompt，输出贴合团队标准的结构化 Review 报告。

---

## 技术栈

| 层级 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **后端** | Java | 21 | Record、虚拟线程、`SseEmitter` 异步流 |
| | Spring Boot | 3.2.5 | Web + AI 自动配置 |
| | Spring AI | 1.0.0 (GA) | `ChatClient` + `BeanOutputConverter` + Milvus 集成 |
| | Reactor | 3.6+ | `Flux` 流式响应 |
| **AI Chat** | DeepSeek | V4-Flash | OpenAI 兼容协议 |
| **AI Embedding** | 硅基流动 (SiliconFlow) | BGE-Large-ZH-v1.5 | 稠密向量 (Dense, 1024 维)，中文优化 |
| **BM25** | 本地 Bigram | — | 稀疏向量 (Sparse)，零依赖纯 Java 实现 |
| **向量数据库** | Milvus | 2.4.0 | 双向量字段 (Dense + Sparse)，AUTOINDEX + IP 索引，RRF 融合 |
| **基础设施** | Docker Compose | — | Milvus Standalone (etcd + MinIO + Milvus) |
| **前端** | Vue 3 | 3.x | Composition API (`<script setup>`) |
| | Vite | 8.x | 开发服务器 + 构建打包 |
| | 原生 CSS | — | Scoped 样式，零 UI 框架依赖 |

---

## 项目结构

```
ai-pr-review/
├── pom.xml                              # Maven 构建配置 (Spring AI 1.0.0 GA + Milvus Starter)
├── docker-compose.yml                   # Milvus Standalone (etcd + MinIO + Milvus)
├── README.md
│
├── src/main/java/org/fourerif/
│   ├── Application.java                 # 启动入口 (禁用 Milvus + RestClient 自动配置)
│   ├── config/
│   │   ├── WebConfig.java               # 全局 CORS 跨域配置
│   │   ├── RagConfig.java               # RAG 配置: MilvusClientV2 + Hybrid Collection 初始化
│   │   └── HttpClientConfig.java        # HTTP 超时配置: OkHttp readTimeout=180s
│   ├── controller/
│   │   └── ReviewController.java        # REST + SSE 双端点
│   │                                    #   GET /api/review        (全量)
│   │                                    #   GET /api/review/stream (SSE)
│   ├── dto/
│   │   ├── ReviewResult.java            # AI 审查结果 (Record)
│   │   ├── RiskItem.java                # 风险项 (Record)
│   │   └── SuggestionItem.java          # 建议项 (Record)
│   └── service/
│       ├── GitHubService.java           # GitHub API + 滑动窗口分片
│       ├── SparseVectorService.java     # 本地 BM25: 字符 Bigram 分词 + IDF 索引
│       ├── MilvusHybridService.java     # Hybrid Schema 管理 + 双路检索 + RRF 融合
│       └── AiReviewService.java         # AI 审查 + Hybrid RAG + 虚拟线程并行聚合
│
├── src/main/resources/
│   ├── application.yml                  # Chat (DeepSeek) + Embedding (硅基流动) + Milvus 三合一
│   ├── prompts/
│   │   └── review-prompt.st             # AI Prompt 模板 (含 {teamConventions} 占位符)
│   └── rules/
│       └── team-conventions.md          # 团队编码规范 (RAG 知识库，6大类15条强制规则)
│
└── frontend/                            # 前端工程 (Vue 3 + Vite)
    ├── index.html
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── main.js                      # Vue 应用入口
        └── App.vue                      # 核心页面（搜索 + 流式结果展示）
```

---

## 快速启动

### 1. 环境准备

- **后端**: JDK 21+、Maven 3.8+、Docker Desktop
- **前端**: Node.js 18+、npm 9+
- **外部服务**:
  - GitHub Personal Access Token — 用于调用 GitHub API
  - DeepSeek API Key — Chat 模型（或任意 OpenAI 兼容 Chat API）
  - 硅基流动 API Key — Embedding 模型（用于 RAG 向量检索）

### 2. 配置密钥

通过环境变量注入敏感配置（**绝对不可将真实密钥提交到代码仓库**）：

```bash
# Chat 模型 (DeepSeek)
export DEEPSEEK_API_KEY="sk-xxxxxxxxxxxxxxxxxxxxxxxx"

# Embedding 模型 (硅基流动，RAG 向量检索用)
export SILICONFLOW_API_KEY="sk-xxxxxxxxxxxxxxxxxxxxxxxx"

# GitHub API
export GITHUB_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxx"

# Milvus 向量数据库 (本地 Docker，默认值通常无需修改)
export MILVUS_URI="http://localhost:19530"       # Milvus gRPC 连接地址
export MILVUS_DATABASE="default"                 # 数据库名
export MILVUS_COLLECTION="team_conventions"      # 集合名
```

> **为什么分开两个 AI 提供商？** DeepSeek 负责代码审查（Chat），性价比极高；硅基流动的 `BAAI/bge-large-zh-v1.5` 免费且中文 Embedding 效果最好。两者在 `application.yml` 中通过 `spring.ai.openai.embedding` 子节点独立配置 `api-key` 和 `base-url`，互不干扰。
>
> 如果不想启用 RAG，只需删除 `src/main/resources/rules/team-conventions.md` 即可 — 系统会自动降级为通用审查模式。
>
> **完整配置参考**（`application.yml` 核心片段）：
>
> ```yaml
> spring:
>   ai:
>     openai:
>       api-key: ${DEEPSEEK_API_KEY}              # DeepSeek Chat
>       base-url: https://api.deepseek.com
>       chat:
>         options:
>           model: deepseek-v4-flash
>           temperature: 0.3
>       embedding:                                 # 硅基流动 Embedding (独立配置)
>         api-key: ${SILICONFLOW_API_KEY}
>         base-url: https://api.siliconflow.cn
>         options:
>           model: BAAI/bge-large-zh-v1.5          # 1024 维中文向量
>
>   vectorstore:
>     milvus:                                      # 本地 Milvus Standalone
>       database-name: ${MILVUS_DATABASE:default}
>       collection-name: ${MILVUS_COLLECTION:team_conventions}
>       embedding-dimension: 1024
>       metric-type: COSINE
>       index-type: AUTOINDEX
>       initialize-schema: true
>       uri: ${MILVUS_URI:http://localhost:19530}
> ```

### 3. 启动 Milvus 向量数据库

项目根目录已包含 `docker-compose.yml`，一键启动 Milvus Standalone：

```bash
cd ai-pr-review
docker compose up -d     # 启动 etcd + MinIO + Milvus Standalone
```

**验证所有容器正常运行：**

```bash
# 检查容器状态（3 个容器均应为 Up / healthy）
docker compose ps

# 健康检查端点
curl http://localhost:9091/healthz
# → OK
```

**端口说明：**

| 端口 | 服务 | 用途 |
|------|------|------|
| `19530` | Milvus gRPC / HTTP | 应用连接（向量写入 + 相似度检索） |
| `9091` | Milvus Metrics | 健康检查 / Prometheus 指标 |
| `9001` | MinIO Console | 对象存储管理界面（可选） |

> 首次启动需要拉取 `milvusdb/milvus:v2.4.0`、`minio/minio`、`quay.io/coreos/etcd:v3.5.5` 三个镜像，约 2–5 分钟。Milvus 启动后约 30 秒完成内部初始化（etcd 选主 + MinIO bucket 创建）。
>
> **数据持久化**：三个 Docker Volume（`etcd_data`、`minio_data`、`milvus_data`）确保重启后数据不丢失。如需彻底清理：`docker compose down -v`。
>
> **内存占用**：Milvus Standalone 默认约 1–2 GB，可在 Docker Desktop 中调整 `mem_limit`。

### 4. 启动后端

```bash
# 进入项目根目录
cd ai-pr-review

# 编译
./mvnw clean compile -q

# 启动 (默认监听 http://localhost:8080)
./mvnw spring-boot:run
```

> **HTTP 超时说明**：OkHttp 默认 readTimeout=10s，对大型 PR Diff 的 AI 审查不够。项目已通过 `HttpClientConfig.java` 将 readTimeout 延长至 **180s**，并排除了 Spring Boot 默认的 `RestClientAutoConfiguration`。如果你的 DeepSeek API 响应特别慢，可在 `HttpClientConfig` 中进一步调大 `readTimeout`。

### 5. 启动前端

```bash
# 进入前端目录
cd frontend

# 安装依赖（仅首次）
npm install

# 启动开发服务器 (默认监听 http://localhost:5173)
npm run dev
```

### 6. 使用

1. 浏览器打开 `http://localhost:5173`
2. 输入 GitHub PR 链接，如 `https://github.com/spring-projects/spring-ai/pull/123`
3. 点击「开始 Review」
4. **流式体验**：页面通过 SSE 实时展示"字符已接收 / 已用时间"进度指标，无需等待即可感知 AI 正在工作
5. 流结束后自动渲染结构化审查报告：变更总结、风险列表（含严重等级 Badge）、改进建议（含重构代码块）

### 7. API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/review?prUrl=...` | GET | 全量响应：等待 AI 完成后一次性返回 JSON |
| `/api/review/stream?prUrl=...` | GET | SSE 流式：单分片时逐 Token 推送（打字机体验）；多分片时推送进度事件 + 最终聚合 JSON |

### 8. 命令行测试

也可直接调用后端接口：

```bash
curl "http://localhost:8080/api/review?prUrl=https://github.com/spring-projects/spring-ai/pull/123"
```

返回示例：

```json
{
  "summary": "本次 PR 为 ChatClient 增加了流式响应的重试机制，涉及 spring-ai-core 模块...",
  "risks": [
    {
      "severity": "HIGH",
      "category": "SECURITY",
      "location": "ChatClient.java:156",
      "description": "API Key 通过日志打印输出，存在密钥泄露风险",
      "recommendation": "将日志级别调整为 DEBUG 或移除敏感字段的日志输出"
    }
  ],
  "suggestions": [
    {
      "location": "RetryTemplate.java:89",
      "description": "魔法值 3000 应提取为常量",
      "refactoredCode": "private static final long RETRY_TIMEOUT_MS = 3000L;"
    }
  ]
}
```

---

## 设计思路说明

### 一、系统架构与模型选择思路

**为什么选择 Spring AI？**

Spring AI 是 Spring 生态官方推出的 AI 集成框架，其核心价值在于**统一抽象层**：通过一致的 `ChatClient` API 屏蔽不同大模型厂商的 SDK 差异，使应用代码与具体模型解耦。它原生集成了 OpenAI、Azure OpenAI、Ollama、HuggingFace 等多种后端，同时提供了 `BeanOutputConverter` 等工具将大模型文本输出自动转换为强类型的 Java Bean，极大降低了"非结构化 LLM 输出 → 结构化业务数据"的工程复杂度。

**为什么选择 Vue 3 + Vite？**

Vue 3 的 Composition API（`<script setup>`）提供了比 Options API 更灵活的代码组织方式，适合快速迭代；Vite 基于原生 ES Module 的开发服务器实现毫秒级热更新，相比 Webpack 显著提升开发体验。前端仅使用原生 CSS（Scoped），不引入 UI 框架，保持零依赖、轻量化的特性，适合内部工具场景。

在技术选型上，本项目遵循以下原则：

| 考量维度 | 选择 | 理由 |
|----------|------|------|
| 框架成熟度 | Spring Boot 3.2 + Spring AI 1.0 | Spring Boot 3.2 LTS + Spring AI 1.0 GA 正式版，API 稳定 |
| 模型能力 | DeepSeek-V4 | 代码理解能力强，性价比高，支持 OpenAI 兼容协议 |
| 可替换性 | ChatClient 抽象层 | 更换模型仅需切换 Starter（如 `spring-ai-ollama`），业务代码零改动 |
| 结构化输出 | BeanOutputConverter | 无需手写 JSON Schema 或复杂正则解析，直接将 JSON 反序列化为 Java Record |
| 前端开发 | Vue 3 + Vite | 轻量、快速、学习成本低，单文件组件 (SFC) 天然契合小型工具项目 |

**架构分层：**

```
┌─────────────────────────────────────────────────────────┐
│  前端 (Vue 3 + Vite)                                     │
│  EventSource → SSE 流式接收 → 进度仪表 + 结构化卡片渲染    │
├─────────────────────────────────────────────────────────┤
│  Controller 层                                           │
│  GET /api/review          → 全量 JSON                    │
│  GET /api/review/stream   → SSE (逐 Token / 进度)         │
├─────────────────────────────────────────────────────────┤
│  Service 层                                              │
│  ┌──────────────────┐  ┌──────────────────────────────┐  │
│  │ GitHubService     │  │ AiReviewService              │  │
│  │ · URL 解析        │  │ · Hybrid RAG (双路+RRF)      │  │
│  │ · PR 元数据       │  │ · 单分片流式 (Flux)           │  │
│  │ · 滑动窗口分片    │  │ · 多分片并行 (虚拟线程×N)     │  │
│  └──────────────────┘  │ · 结果聚合 + 去重             │  │
│                         └──────────────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│  RAG 层 — Hybrid Search + RRF                            │
│  ┌───────────────────┐  ┌─────────────────────────────┐  │
│  │ MilvusHybridService│  │ team-conventions.md          │  │
│  │ · Dense (1024d)   │  │ 6大类 / 15条团队强制规范      │  │
│  │ · Sparse (BM25)   │  └─────────────────────────────┘  │
│  │ · RRF Fusion (k=60)│                                  │
│  └───────────────────┘                                   │
├─────────────────────────────────────────────────────────┤
│  DTO 层: ReviewResult / RiskItem / Suggestion            │
├─────────────────────────────────────────────────────────┤
│ 外部: GitHub API │ DeepSeek (Chat) │ 硅基流动 (Embedding) │ Milvus (向量库) │
└─────────────────────────────────────────────────────────┘
```

每一层职责清晰：Controller 负责路由分发（全量 vs 流式），GitHubService 负责 PR 数据获取和滑动窗口分片，AiReviewService 负责 Hybrid RAG 检索 + AI 调用编排 + 结果聚合去重，RAG 层负责团队规范的双向量知识库管理 + 双路检索 + RRF 融合排序，SparseVectorService 提供本地 BM25 稀疏向量编码，DTO 定义前后端数据契约。

---

### 二、上下文获取方式

**PR 数据的获取与组织：**

GitHub 提供了两组关键 API：

1. **PR 元数据** — `GET /repos/{owner}/{repo}/pulls/{number}`，返回标题（title）、描述（body）、变更文件列表等结构化 JSON。
2. **PR Diff** — 同一端点，仅需将 Accept 头改为 `application/vnd.github.v3.diff`，即可获得标准 unified diff 格式的纯文本。

本项目将这两部分数据组合后注入 Prompt 模板：

```
┌──────────┐    ┌──────────────┐    ┌────────────────┐
│ PR URL   │───▶│ 解析 owner/   │───▶│ GitHub API     │
│ 用户输入  │    │ repo/number  │    │ 并行获取两数据  │
└──────────┘    └──────────────┘    └───────┬────────┘
                                            │
                              ┌─────────────┴─────────────┐
                              │  PR 标题 + 描述 + Diff     │
                              │  填充到 review-prompt.st   │
                              └─────────────┬─────────────┘
                                            │
                              ┌─────────────┴─────────────┐
                              │  发送至 DeepSeek API      │
                              └───────────────────────────┘
```

**长文本处理：滑动窗口 + 重叠分片 + 虚拟线程并行**

大型 PR 的 Diff 可能达到数万行甚至数十万行。若不分片直接发送，三个问题无法回避：Token 窗口不够、模型注意力稀释、响应延迟线性增长。之前的 MVP 方案采用 20000 字符的简单硬截断（丢弃后半部分），但这会导致严重的代码上下文丢失和审查漏报。

当前已升级为以下完整方案：

**第一步 — 滑动窗口分片 (`GitHubService.chunkDiffWithOverlap`)**

```
原始 Diff (6000 行)
│
├── Chunk 1: 行   1-800  ─┐
│                          ├─ 重叠区: 行 651-800 (150 行)
├── Chunk 2: 行 651-1450 ─┘
│                          ├─ 重叠区: 行 1301-1450
├── Chunk 3: 行 1301-2100 ...
│
...共 N 个分片，Diff 100% 覆盖，零丢弃
```

- **窗口大小**：800 行/Chunk（约 2–3 万字符，模型注意力最佳区间）
- **重叠行数**：150 行，确保分片边界的代码在两个相邻窗口中均可见
- **为什么需要重叠？** 代码审查的上下文依赖很强——函数的调用方和被调用方可能落在不同分片中。150 行的重叠区确保边界附近的代码可被相邻两个窗口分别审查，大幅降低"边界漏报"

**第二步 — 虚拟线程并行审查 (`AiReviewService.reviewChunksParallel`)**

```
                    ┌─ Virtual Thread #1 ── AI 审查 Chunk 1 ─┐
                    ├─ Virtual Thread #2 ── AI 审查 Chunk 2 ─┤
PR Diff (N Chunks) ─┼─ Virtual Thread #3 ── AI 审查 Chunk 3 ─┼─ 聚合 + 去重
                    ├─ Virtual Thread #4 ── AI 审查 Chunk 4 ─┤
                    └─ Virtual Thread #5 ── AI 审查 Chunk 5 ─┘
                                          (全部并行，总耗时 ≈ 最慢单分片)
```

- **并发模型**：`Executors.newVirtualThreadPerTaskExecutor()` — 虚拟线程启动成本 ~1μs，JVM 在少量 OS 线程上调度大量虚拟线程，天然适合"长时间 IO 等待"（等 AI API 响应）
- **时间线**：N 个分片串行需 N×30 秒，并行后 ≈ max(每个分片耗时) ≈ 30 秒
- **容错**：单分片失败不中断全局 — 异常被捕获、记录日志、该分片返回 null 后被过滤，仅合并成功的结果

**第三步 — 结果聚合与去重 (`AiReviewService.mergeResults`)**

- **summary**：各分片摘要按序拼接，标注共有多少个分片
- **risks / suggestions**：合并后按 `(location, description)` 精确去重
  - 去重前先剥离 `[分片 X/N]` 前缀，确保重叠区内的同一问题不会因分片不同而被重复报告
- **全失败兜底**：若所有分片均失败，返回明确错误摘要 + 空列表，不会返回 null 导致前端崩溃

**对比：**

| 维度 | 旧方案 (硬截断) | 新方案 (滑动窗口并行) |
|------|:---:|:---:|
| Diff 覆盖率 | ~30-50% (20000 字符后丢弃) | **100%** |
| 审查耗时 | O(N) 串行 | **O(1)** 虚拟线程并发 |
| 边界漏报 | 严重 | 低 (150 行重叠) |
| 重复报告 | 无 | 精确去重 |
| 单点故障 | 全部失败 | 分片级隔离 |

**未来可进一步优化的方向：**

- **语义分片**：当前按行数机械切分，未来可引入语言感知的 AST 解析（如 `tree-sitter`）按函数/类边界智能切分
- **文件级过滤**：根据 `.gitignore` 或配置白名单过滤 lock 文件、生成代码等无需审查的文件

---

### 三、RAG 检索增强生成 — Hybrid Search + RRF

通用 AI 代码审查最大的局限在于**不理解团队内部规范**。例如 "Controller 禁止直接调用 DAO" 这样的架构约束，通用模型无从知晓，容易将违反团队规范的代码评判为"正常"。RAG 架构通过在 Prompt 中动态注入相关的团队规范，让模型"带着团队眼镜"进行审查。

本项目的 RAG 已从**单路稠密检索**升级为**双路 Hybrid Search + RRF（倒数排列融合）**，最大化规范检索的准确率。

**Hybrid RAG 数据流：**

```
启动时 (一次性，RagConfig.initVectorStore)：
  team-conventions.md (Markdown, 6大类 / 15条规则)
    → TokenTextSplitter (chunkSize=300 tokens, overlap=50, maxChunks=50)
      → [双向量生成]
          ├─ Dense:  硅基流动 BGE-Large-ZH-v1.5 → 1024 维 FloatVector
          └─ Sparse: SparseVectorService (Local BM25 + Char Bigram) → SparseFloatVector
            → MilvusHybridService → Milvus Standalone (双向量字段, 持久化到 MinIO)

每次审查时 (对每个 Diff Chunk)：
  Diff Chunk 前 500 字符 (文件路径 + 类名 + 首段代码)
    → [双路并行检索]
        ├─ Dense 路:  Embedding → COSINE → ANN Top-K×2
        └─ Sparse 路: BM25 Bigram → IP (Inner Product) → WAND Top-K×2
          → RRF Ranker (k=60) 融合排序 → 最终 Top-K 规范
            → 注入 Prompt {teamConventions} 占位符
              → ChatClient 审查 (含团队规范约束)
```

**两路检索的分工与互补：**

| 维度 | Dense 路 (稠密) | Sparse 路 (稀疏) |
|------|----------------|-------------------|
| **模型/算法** | BGE-Large-ZH-v1.5 (1024d) | Local BM25 + 字符 Bigram |
| **相似度度量** | COSINE (余弦相似度) | IP (内积) |
| **索引类型** | AUTOINDEX | SPARSE_INVERTED_INDEX |
| **擅长场景** | 语义相似："这个改动看起来像是分层架构问题" | 关键词精确匹配："这段代码包含 `Statement.executeQuery`" |
| **对规范的覆盖** | 概念级规则（架构分层、异常处理规范） | 术语级规则（SQL注入、空指针、Controller/DAO） |

**为什么选择 RRF (Reciprocal Rank Fusion) 而非加权求和？**

Dense 打分（COSINE, 0~1）和 Sparse 打分（IP, 无上界）的数值尺度差异极大。硬加权需要对两种分数的分布做归一化处理，超参敏感且对不同查询不稳定。RRF 仅依赖排名位置（而非原始分数），天然跨异构检索器可比：

```
RRF_score(d) = Σ 1 / (k + rank_i(d))

其中 k=60 (标准平滑参数), rank_i(d) 是文档 d 在检索器 i 中的排名
```

两路各自召回 topK×2 个候选，RRF 重新排序后取最终 Top-K。被两路同时排在前列的文档将获得最高 RRF 分数——这是一种天然的交叉验证机制，有效降低单路检索的噪音。

**RagConfig 设计说明：**

项目同时排除了两个 Spring 自动配置：
- `MilvusVectorStoreAutoConfiguration` — 因为 Spring AI 的 `MilvusVectorStore` 仅支持单稠密向量字段，无法创建 Hybrid Schema
- `RestClientAutoConfiguration` — 因为 OkHttp 默认 readTimeout=10s 对 AI Chat API 不够，需用自定义长超时 Builder

`RagConfig` 手动管理 Milvus 的完整生命周期：创建 `MilvusClientV2`（支持 Hybrid Search API）→ 删旧表 → 创建含 Dense + Sparse 双字段的 Collection → 为两个字段分别建索引（AUTOINDEX + SPARSE_INVERTED_INDEX）→ 批量插入 → Load 到内存。

**Query 构造策略 — 为什么取 Diff 前 500 字符？**

Unified Diff 格式的开头天然包含最关键的结构化信息：

```diff
diff --git a/UserService.java b/UserService.java    ← 文件路径 → 匹配 "Service 层" 规范
@@ -45,6 +45,15 @@ public class UserService {      ← 类名/方法 → 定位模块
+    String sql = "SELECT * FROM ...";              ← 代码片段 → 匹配 "SQL 注入" 规范
```

- **选前 500 字符**：文件路径 + 变更位置 + 首段代码，语义密度最高
- **不选用完整 Diff**：过长查询被 Dense Embedding 模型截断（BGE 最大 512 tokens），且稀释 BM25 的关键词信号
- **不选用 PR 标题**：缺少代码级语义，"修复 bug" 无法匹配任何规范

**容错降级：**

| 异常场景 | 行为 |
|----------|------|
| 向量库为空 / 无匹配 | `hybridSearch()` 返回空列表 → 填入 `（无额外团队规范约束，按通用最佳实践审查）` |
| Sparse 路无命中词表 | 填入哑元维度 `(0, 0.0)` 的稀疏向量，对 RRF 排名无影响，等效退化为 Dense-only |
| Embedding 调用失败 / Milvus 不可达 | catch 异常，记录 WARN 日志，返回空字符串，**不阻塞审查主流程** |
| `{teamConventions}` 占位符残留 | 不可能 — 始终有 fallback 文本兜底 |

**技术选型 — 为什么选择这套组合？**

| 考量 | 选择 | 理由 |
|------|------|------|
| **向量数据库** | Milvus 2.4 Standalone | 原生支持 SparseFloatVector + hybrid_search + RRF，一条 Docker 命令即可部署 |
| **稠密模型** | BGE-Large-ZH-v1.5 (硅基流动) | 1024 维中文优化，免费 API，语义理解能力强 |
| **稀疏算法** | Local BM25 + 字符 Bigram | 零外部依赖，毫秒级，中文混合文本鲁棒，确定性输出 |
| **融合方法** | RRF (k=60) | 跨异构检索器天然可比，无超参调优，学术验证充分 |
| **持久化** | MinIO (Docker Volume) | 应用重启无需重新 Embedding，数据零丢失 |

| 考量 | Milvus (当前) | SimpleVectorStore (旧) | PgVectorStore / Redis |
|------|:---:|:---:|:---:|
| 部署复杂度 | Docker 一条命令 | 零（JVM 堆内存） | 需额外部署 + 配置 |
| 数据持久化 | ✅ 自动持久化到 MinIO | ❌ 重启即丢失 | ✅ |
| 数据规模 | 百万级文档 | < 10,000 条 | 百万级文档 |
| 检索速度 | AUTOINDEX + SPARSE_INVERTED, < 10ms | 暴力全量, < 1ms | 近似最近邻, < 5ms |
| Hybrid Search | ✅ 原生 SparseFloatVector + RRF | ❌ 不支持 | 取决于实现 |
| 实际效果 | 双路召回 + RRF 交叉验证, 精准度最高 | 单路 Dense, 关键词匹配弱 | 取决于实现 |

---

### 四、误报与漏报控制策略

AI 代码审查最大的挑战不是"没发现问题"，而是"发现了不存在的问题"（误报）或"放过了真正的问题"（漏报）。本项目从 Prompt 工程和系统架构两个层面进行控制：

**Prompt 设计层面：**

| 策略 | 具体措施 | 效果 |
|------|----------|------|
| **角色锚定** | Prompt 首句设定为"资深代码审查专家"，激活模型对代码质量的领域知识 | 减少泛泛而谈的通用回答 |
| **分类约束** | 风险分为 SECURITY / PERFORMANCE / LOGIC / STYLE 四类，每类附带具体判定标准 | 防止模型乱贴标签，确保每条风险有明确归属 |
| **置信度标注** | 要求对不确定的风险在描述中标注"低置信度" | 下游可据此过滤或降级展示，降低误报影响 |
| **禁止猜测行号** | 明确要求"不要编造不存在的代码行号"，无法确定时使用模糊位置 | 避免模型虚构具体行号，提升结果可信度 |
| **空数组兜底** | 允许并鼓励无问题时返回空数组 `[]` | 防止模型为"完成任务"而强行编造风险 |
| **低温度参数** | `temperature=0.3`，远低于创意写作场景的 0.8~1.0 | 输出更确定、更一致，减少随机幻觉 |

**工程架构层面：**

1. **结构强制输出**：使用 Spring AI 的 `BeanOutputConverter` 将 JSON Schema 注入 Prompt，大模型输出必须是符合约定的 JSON。解析失败时直接报错，而非静默降级，保证调用方不会被非结构化文本污染。
2. **敏感配置隔离**：GitHub Token 和 API Key 通过环境变量注入，代码中零硬编码。`application.yml` 仅包含占位符，避免密钥泄露导致的误操作追溯困难。
3. **全链路日志**：每个关键步骤（URL 解析、API 调用、AI 请求/响应、结果解析）均记录日志，方便追溯某一轮审查中模型的"思考过程"，为 Prompt 迭代提供数据支撑。
4. **前端输入校验**：在前端对 PR URL 进行正则校验，拦截明显非法的输入，减少无效请求对后端的冲击。

**误报/漏报的认知：**

需要明确的是，100% 消除误报和漏报在现阶段是不现实的——即便是人类 Senior Reviewer 之间也常存在意见分歧。本项目的目标不是替代人工 Review，而是作为**人工审查前的第一道自动化筛选**：将高风险、高置信度的问题排在前面，帮助 Reviewer 快速聚焦最值得关注的代码片段。低置信度或风格类建议则更多作为参考信息，由 Reviewer 自行判断采纳与否。

---

### 五、未来扩展方向

**1. ✅ 流式输出 (SSE) — 已完成**
**2. ✅ 滑动窗口 + 重叠分片 + 虚拟线程并行 — 已完成**
**3. ✅ RAG 检索增强生成 — 已完成**
**4. ✅ Hybrid Search + RRF (Dense + Sparse) — 已完成**

~~原有单路 Dense 检索~~ → 已升级为：Dense (BGE-Large-ZH, 1024d + COSINE) + Sparse (Local BM25 + 字符 Bigram + IP) 双路并行检索 → Milvus hybrid_search + RRF Ranker (k=60) 融合排序 → Top-3 规范动态注入 Prompt。实测检索耗时 ~150ms，两路交叉验证有效降低单路噪音。详见设计思路第三节。

**5. 语义分片 (AST-aware Chunking)**

当前滑动窗口按固定行数机械切分，可能将一个函数截断在两个分片中。未来可引入语言感知的 AST 解析器（如 `tree-sitter`）按函数/类/方法边界智能切分，确保每个分片包含完整的语义单元，进一步提升审查质量。

**6. 历史 Review 学习**

将每次人工采纳/拒绝 AI 建议的反馈记录下来，形成标注数据集。通过 LoRA 微调或 Prompt 中提供 few-shot 示例，让模型学习特定团队的 Review 偏好，逐步降低误报率。

**7. IDE 插件化**

将审查能力封装为 VS Code / IntelliJ IDEA 插件，开发者在提交 PR 前可在本地触发 AI Review，实现"提交前自检"。插件可复用本项目的 Service 层逻辑，仅需替换 Controller 为插件内的 Action 入口。

**8. CI/CD 集成 (GitHub App / Webhook)**

将本项目改造为 GitHub App，通过 Webhook 监听 PR 的 `opened` 和 `synchronize` 事件，自动触发 Review 并将结果以 PR Comment 或 Review 的形式回写到 GitHub，实现全自动化流程。Spring AI 的异步调用能力（`CompletableFuture` + 虚拟线程）可支撑高并发 Webhook 处理。

**9. 多模型 Ablation 评估**

搭建评估流水线，将同一组 PR 同时发送给 GPT-4o、Claude Sonnet、DeepSeek-V3 等多个模型，收集各自的审查结果并对比人工标注，量化不同模型在代码审查场景下的准确率、召回率和 F1-score，为模型选型提供数据依据。

**10. 前端增强 — 历史记录与对比视图**

前端可扩展本地历史记录功能（IndexedDB），保存用户最近的审查请求和结果，支持快速回溯。进一步可实现"左右分栏 Diff 视图"，左侧展示代码变更，右侧展示 AI 审查标注，提供类似 GitHub PR Review 界面的沉浸式体验。

---

## License

MIT