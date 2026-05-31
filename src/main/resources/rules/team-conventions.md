# 团队代码规范 (Team Coding Conventions)

> 本文件为团队自定义的强制代码规范，AI Review 时将优先以这些规则为准。
> 违反以下任何一条规范均应标记为 **HIGH** 风险等级。

---

## 1. 分层架构约束 (Layered Architecture)

### 1.1 Controller 层职责
- Controller 层负责接收 HTTP 请求、参数校验、调用 Service 并返回响应。
- **禁止：** Controller 直接调用 DAO / Mapper / Repository 接口。
- **禁止：** Controller 中包含业务逻辑（如数据组装、复杂条件判断）。
- 正确做法：`Controller → Service → Repository`

### 1.2 Service 层职责
- Service 层负责业务逻辑编排、事务管理。
- 每个 Service 方法应具有单一职责。
- **禁止：** Service 直接操作 HttpServletRequest / HttpServletResponse。

---

## 2. 数据库与 SQL 安全 (Database & SQL Security)

### 2.1 SQL 注入防护
- 所有 SQL 查询必须使用参数化查询（PreparedStatement / MyBatis `#{}` 语法）。
- **禁止：** 使用字符串拼接构造 SQL 语句。
- **禁止：** 在 MyBatis 中使用 `${}` 代替 `#{}`，除非明确用于动态表名/列名且已做白名单校验。

### 2.2 敏感数据
- 数据库密码、API Key 等敏感配置必须通过环境变量或配置中心注入。
- **禁止：** 在代码或配置文件中硬编码密码、Token、密钥。

---

## 3. 代码质量规范 (Code Quality)

### 3.1 魔法值
- 所有魔法值（Magic Numbers / Magic Strings）必须提取为命名常量。
- 例外：0、1、-1 在简单循环或比较中不强制提取。
- 常量命名规范：`public static final` + 全大写 + 下划线分隔（如 `MAX_RETRY_COUNT`）。

### 3.2 异常处理
- **禁止：** 空 catch 块（捕获异常后不做任何处理）。
- **禁止：** 捕获 Exception 后仅调用 `printStackTrace()`。
- 必须记录有意义的错误日志，或将异常包装后向上抛出。
- 对可恢复的异常应有降级策略。

### 3.3 空指针防护
- 对所有外部输入（HTTP 参数、RPC 返回值、数据库查询结果）进行 null 检查。
- 优先使用 `Optional` 或 Java 21 的 `Objects.requireNonNullElse()`。
- **禁止：** 在未检查 null 的情况下直接调用对象方法。

---

## 4. 日志与监控 (Logging & Monitoring)

### 4.1 日志规范
- 关键业务节点（数据库操作、外部 API 调用、异常捕获）必须记录日志。
- 日志级别使用规范：
  - `ERROR`：系统错误，需要人工介入。
  - `WARN`：可恢复的异常，降级处理。
  - `INFO`：关键业务节点。
  - `DEBUG`：调试信息，生产环境默认关闭。
- **禁止：** 在循环中打印 `INFO` 级别日志。
- **禁止：** 在日志中打印敏感信息（密码、Token、身份证号、手机号）。

### 4.2 性能监控
- 所有外部 API 调用必须记录响应时间。
- 慢查询（超过 1 秒）必须记录 WARN 日志并包含 SQL 语句和执行时间。

---

## 5. API 设计规范 (API Design)

### 5.1 RESTful 规范
- GET 请求用于查询，不得修改数据。
- POST 用于创建，PUT 用于全量更新，PATCH 用于部分更新。
- 响应体统一使用 `Result<T>` 包装（包含 code、message、data 字段）。

### 5.2 并发安全
- 对共享可变状态的操作必须保证线程安全（使用 `synchronized`、`ReentrantLock` 或 `ConcurrentHashMap`）。
- 在高并发场景下优先使用无锁数据结构（如 `AtomicInteger`、`LongAdder`）。

---

## 6. 依赖与版本管理 (Dependency Management)

### 6.1 第三方库
- 引入新的第三方依赖前需要团队评审。
- **禁止：** 引入已知有 CVE 漏洞的依赖版本。
- 优先使用 Spring 生态内的解决方案，避免引入功能重叠的库。

### 6.2 版本锁定
- 所有依赖版本必须在父 POM 的 `<dependencyManagement>` 中统一管理。
- **禁止：** 在子模块中单独指定版本号。