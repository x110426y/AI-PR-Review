# AI PR Review 助手 (全栈)

基于 **Spring Boot 3.2 + Spring AI + Vue 3** 的智能 Pull Request 代码审查助手。前端提供简洁的搜索界面，后端自动获取 GitHub PR 变更内容并调用大模型进行多维度代码审查，输出结构化的 Review 报告。

---

## 技术栈

| 层级 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **后端** | Java | 21 | 优先使用 Record、虚拟线程等新特性 |
| | Spring Boot | 3.2.5 | 提供 Web、AI 自动配置 |
| | Spring AI | 1.0.0-SNAPSHOT | 统一 AI 调用抽象层 |
| | DeepSeek / OpenAI | — | 默认配置 DeepSeek，可切换任意 OpenAI 兼容模型 |
| | Maven | 3.8+ | 构建与依赖管理 |
| **前端** | Vue 3 | 3.x | Composition API (`<script setup>`) |
| | Vite | 8.x | 极速开发构建工具 |
| | 原生 CSS | — | Scoped 样式，无第三方 UI 库依赖 |

---

## 项目结构

```
ai-pr-review/
├── pom.xml                              # Maven 构建配置
├── README.md
│
├── src/main/java/org/fourerif/
│   ├── Application.java                 # Spring Boot 启动入口
│   ├── config/
│   │   └── WebConfig.java               # 全局 CORS 跨域配置
│   ├── controller/
│   │   └── ReviewController.java        # REST 控制器 (GET /api/review)
│   ├── dto/
│   │   ├── ReviewResult.java            # AI 审查结果 (Record)
│   │   ├── RiskItem.java                # 风险项 (Record)
│   │   └── SuggestionItem.java          # 建议项 (Record)
│   └── service/
│       ├── GitHubService.java           # GitHub API 调用服务
│       └── AiReviewService.java         # AI 审查核心服务
│
├── src/main/resources/
│   ├── application.yml                  # 应用配置 (密钥占位符)
│   └── prompts/
│       └── review-prompt.st             # AI Prompt 模板
│
└── frontend/                            # 前端工程 (Vue 3 + Vite)
    ├── index.html
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── main.js                      # Vue 应用入口
        └── App.vue                      # 核心页面（搜索 + 结果展示）
```

---

## 快速启动

### 1. 环境准备

- **后端**: JDK 21+、Maven 3.8+
- **前端**: Node.js 18+、npm 9+
- **外部服务**:
  - GitHub Personal Access Token（用于调用 GitHub API）
  - DeepSeek API Key（或任意 OpenAI 兼容的 API Key）

### 2. 配置密钥

通过环境变量注入敏感配置（**绝对不可将真实密钥提交到代码仓库**）：

```bash
export DEEPSEEK_API_KEY="sk-xxxxxxxxxxxxxxxxxxxxxxxx"
export GITHUB_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxx"
```

> 如使用其他模型（如 OpenAI 官方），需同步修改 `application.yml` 中的 `base-url` 和 `model` 配置。

### 3. 启动后端

```bash
# 进入项目根目录
cd ai-pr-review

# 编译
./mvnw clean compile -q

# 启动 (默认监听 http://localhost:8080)
./mvnw spring-boot:run
```

### 4. 启动前端

```bash
# 进入前端目录
cd frontend

# 安装依赖（仅首次）
npm install

# 启动开发服务器 (默认监听 http://localhost:5173)
npm run dev
```

### 5. 使用

1. 浏览器打开 `http://localhost:5173`
2. 输入 GitHub PR 链接，如 `https://github.com/spring-projects/spring-ai/pull/123`
3. 点击「开始 Review」，等待 10–30 秒
4. 查看结构化的审查报告：变更总结、风险列表、改进建议

### 6. 命令行测试

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
| 框架成熟度 | Spring Boot 3.2 + Spring AI 1.0 | Spring Boot 3.2 是稳定大版本，Spring AI 已发布 M6 里程碑，API 趋于稳定 |
| 模型能力 | DeepSeek-V4 | 代码理解能力强，性价比高，支持 OpenAI 兼容协议 |
| 可替换性 | ChatClient 抽象层 | 更换模型仅需切换 Starter（如 `spring-ai-ollama`），业务代码零改动 |
| 结构化输出 | BeanOutputConverter | 无需手写 JSON Schema 或复杂正则解析，直接将 JSON 反序列化为 Java Record |
| 前端开发 | Vue 3 + Vite | 轻量、快速、学习成本低，单文件组件 (SFC) 天然契合小型工具项目 |

**架构分层：**

```
┌─────────────────────────────────────────┐
│  前端 (Vue 3 + Vite)                     │
│  App.vue  — 搜索输入 + 结果卡片展示       │
├─────────────────────────────────────────┤
│  Controller 层 (REST API)                │
│  ReviewController  — GET /api/review     │
├─────────────────────────────────────────┤
│  Service 层 (业务逻辑)                    │
│  ┌──────────────┐  ┌──────────────────┐  │
│  │ GitHubService │  │ AiReviewService  │  │
│  │ (PR 数据获取)  │  │ (AI 代码审查)    │  │
│  └──────────────┘  └──────────────────┘  │
├─────────────────────────────────────────┤
│  DTO 层 (数据传输对象)                    │
│  ReviewResult / RiskItem / Suggestion   │
├─────────────────────────────────────────┤
│  外部依赖                                │
│  ┌──────────┐  ┌──────────┐             │
│  │ GitHub   │  │ DeepSeek │             │
│  │ REST API │  │ API      │             │
│  └──────────┘  └──────────┘             │
└─────────────────────────────────────────┘
```

每一层职责清晰：Controller 负责参数校验和路由，Service 封装外部调用和 AI 交互逻辑，DTO 定义数据契约。前端通过 HTTP 与 Controller 层通信，前后端完全解耦，可独立部署、独立开发。

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

**长文本截断策略：**

代码 Diff 可能非常庞大（大型 PR 可达数万行），直接传输存在三个问题：

1. **Token 限制**：模型上下文窗口有限，一次性消耗过多 tokens 会挤压输出空间，且费用高昂。
2. **注意力稀释**：大量不相关的变更会稀释模型对关键代码的注意力，导致漏报风险。
3. **响应延迟**：输入越长，模型推理时间越长。

当前采用的截断方案：**硬截断 + 明确告知**。在 `GitHubService.truncateDiff()` 中，当 Diff 超过 20000 字符时保留前半部分，并在末尾附加截断提示。该方案简单可靠，适合 MVP 阶段。

**未来优化方向：**

- **文件级过滤**：根据 `.gitignore` 或配置的白名单过滤不需要审查的文件（如 lock 文件、生成代码）。
- **智能摘要**：对大文件先生成摘要再送入主 Prompt，而非直接丢弃。
- **分片审查**：将超大 PR 按文件拆分，逐片调用 AI 后合并结果，确保不丢失任何变更。

---

### 三、误报与漏报控制策略

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

### 四、未来扩展方向

**1. 流式输出 (SSE — Server-Sent Events)**

当前 AI 调用采用"请求-全量响应"模式，用户需等待 10–30 秒才能看到结果。未来可改造为 SSE 流式输出：后端通过 Spring AI 的 `ChatClient.stream()` 获取增量 Token，经 Spring WebFlux 的 `Flux<ServerSentEvent>` 推送至前端；前端使用 `EventSource` API 逐段渲染 Markdown 格式的审查结果。这将提供类似 ChatGPT 的打字机体验，显著降低用户等待感知。

**2. Diff 智能分片**

替代当前的 20000 字符简单硬截断，引入语言感知的 AST 解析器（如 `javaparser`、`tree-sitter`）识别代码块的语义边界，按函数/类/文件为单位进行智能分片。每个分片独立送审 AI，结果按风险严重程度排序汇总。该方案确保：
- 关键变更（安全敏感函数、核心业务逻辑）不因截断被遗漏
- 每个分片大小可控，模型注意力集中
- 支持并行调用 AI 进一步缩短总耗时

**3. RAG + 团队代码规范**

当前 Prompt 中的审查规则是通用型的。在实际团队中，每个组织都有独特的编码规范和架构约束（如"Controller 层禁止直接调用 DAO"、"所有 SQL 必须使用参数化查询"）。未来可通过 RAG（检索增强生成）架构，将团队内部的编码规范文档、历史 Review 记录、SonarQube 规则等向量化存入向量数据库（如 pgvector、Milvus），在发送 Prompt 前检索最相关的规范条目注入上下文，使 AI 审查结果更贴合团队实际标准。

**4. 历史 Review 学习**

将每次人工采纳/拒绝 AI 建议的反馈记录下来，形成标注数据集。通过 LoRA 微调或 Prompt 中提供 few-shot 示例，让模型学习特定团队的 Review 偏好，逐步降低误报率。

**5. IDE 插件化**

将审查能力封装为 VS Code / IntelliJ IDEA 插件，开发者在提交 PR 前可在本地触发 AI Review，实现"提交前自检"。插件可复用本项目的 Service 层逻辑，仅需替换 Controller 为插件内的 Action 入口。

**6. CI/CD 集成 (GitHub App / Webhook)**

将本项目改造为 GitHub App，通过 Webhook 监听 PR 的 `opened` 和 `synchronize` 事件，自动触发 Review 并将结果以 PR Comment 或 Review 的形式回写到 GitHub，实现全自动化流程。Spring AI 的异步调用能力（`CompletableFuture` + 虚拟线程）可支撑高并发 Webhook 处理。

**7. 多模型 Ablation 评估**

搭建评估流水线，将同一组 PR 同时发送给 GPT-4o、Claude Sonnet、DeepSeek-V3 等多个模型，收集各自的审查结果并对比人工标注，量化不同模型在代码审查场景下的准确率、召回率和 F1-score，为模型选型提供数据依据。

**8. 前端增强 — 历史记录与对比视图**

前端可扩展本地历史记录功能（IndexedDB），保存用户最近的审查请求和结果，支持快速回溯。进一步可实现"左右分栏 Diff 视图"，左侧展示代码变更，右侧展示 AI 审查标注，提供类似 GitHub PR Review 界面的沉浸式体验。

---

## License

MIT