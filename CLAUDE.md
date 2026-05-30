# 任务目标
请帮我从零构建一个基于 Java Spring AI 的「AI PR Review 助手」项目。你需要完成项目初始化、编写核心业务代码，并生成一份符合要求的项目说明文档。

## 1. 技术栈要求
* 语言：Java 21 (优先使用 Record 和新特性)
* 框架：Spring Boot 3.2+
* AI 框架：Spring AI (使用最新稳定版 BOM，默认配置为 spring-ai-openai-starter)
* 构建工具：Maven (请帮我生成完整的 pom.xml)

## 2. 核心功能需求
请创建相应的 Controller, Service 和 DTO 层代码来实现以下逻辑：
1. **获取 PR 上下文**: 提供一个 REST 接口（如 `/api/review?prUrl=xxx`），用户传入 GitHub PR 链接。模拟或实现调用 GitHub API 获取该 PR 的 Diff 数据和基本描述。
2. **AI 智能分析 (核心)**: 使用 Spring AI 的 `ChatClient` 结合 Prompt 模板，分析获取到的代码变更。AI 需要完成：
    - 总结 PR 变更意图。
    - 识别潜在的风险代码（性能、安全、逻辑漏洞）。
    - 给出具体的 Review 建议和重构示例。
3. **结构化输出**: 使用 Spring AI 的 `BeanOutputConverter`，强制大模型输出 JSON，并映射到后端的 Java Record (例如 `ReviewResult` 类) 中，最后通过接口返回给前端。

## 3. 代码质量与规范
* 使用 `application.yml` 管理配置（如 GitHub Token 和 OpenAI API Key），代码中绝对不可硬编码密钥。
* Prompts 必须独立提取到 `src/main/resources/prompts/` 目录下，以 `.st` 结尾。
* 代码需要有清晰的中文注释。

## 4. 必须输出的文档 (README.md)
请在项目根目录生成一份高水准的 `README.md`，除了基本的启动说明外，必须包含以下“设计思路说明”章节（字数详实，显得专业）：
1. **系统架构与模型选择思路**：为什么选择 Spring AI 以及当前的主流大模型。
2. **上下文获取方式**：如何获取并组织 PR Diff，如何处理长文本截断问题。
3. **误报与漏报控制策略**：在 Prompt 设计和工程思路上如何避免 AI 胡说八道。
4. **未来扩展方向**：例如结合 RAG 引入团队代码规范、IDE 插件化等。

## 5. 执行步骤
1. 请先创建标准的 Spring Boot 目录结构和 `pom.xml`。
2. 创建 `application.yml` (留出 key 的占位符)。
3. 编写 DTO 实体类。
4. 编写 Prompt `.st` 文件。
5. 编写 GitHub Service 和 AI Review Service。
6. 编写 Controller。
7. 生成 README.md。

请一步一步执行，如果遇到需要确认的地方再问我，否则请直接生成并保存所有文件。