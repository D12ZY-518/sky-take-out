# 阶段 10 学习复盘：Agent 鉴权、Profile 隔离与 MockMvc 测试

## 1. 这次解决了什么问题

改造前，`/user/ai/**` 和 `/user/service/**` 为了方便演示被 JWT 拦截器排除。两个 Controller 会自己读取 token：解析成功就使用真实用户，缺失或解析失败都静默使用演示用户 1。

这带来两个问题：

1. 生产环境里未登录请求也可能操作演示用户的数据；
2. 调用方带了过期或伪造 token 时，系统没有明确报错，反而悄悄换成用户 1，掩盖了登录态故障。

本次目标不是删除演示功能，而是让演示便利与生产安全可以同时存在：

- `dev`：没有 token 时允许使用演示用户；
- `prod` 或关闭演示开关：没有 token 返回 HTTP 401；
- 所有环境：只要主动携带了 token，就必须校验成功，坏 token 一律返回 HTTP 401。

## 2. 为什么抽出 AiUserResolver

改造前，`AiAssistantController` 和 `ServiceAgentController` 各写了一份几乎相同的 JWT 解析代码。重复代码的风险不只是“难看”，而是以后修复安全规则时可能只改了一处。

现在调用链变成：

```text
HTTP 请求
  │
  ├─ AiAssistantController
  │
  └─ ServiceAgentController
          │
          ▼
    AiUserResolver.resolve(request)
          │
          ├─ 无 token + demoEnabled=true  → userId=1
          ├─ 无 token + demoEnabled=false → UserNotLoginException
          ├─ 合法 token                   → JWT 中的 userId
          └─ 无效 token                   → UserNotLoginException
```

Controller 只负责 HTTP 入参和调用业务服务；身份规则集中在 `AiUserResolver`，这就是“单一职责”和“安全策略单一入口”。

关键文件：

- `sky-server/src/main/java/com/sky/security/AiUserResolver.java`
- `sky-server/src/main/java/com/sky/controller/user/AiAssistantController.java`
- `sky-server/src/main/java/com/sky/controller/user/ServiceAgentController.java`

## 3. Profile 与配置覆盖关系

`application.yml` 使用：

```yaml
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```

含义是：环境变量没有指定时使用 `dev`，部署时可以通过 `SPRING_PROFILES_ACTIVE=prod` 切换。

基础配置默认关闭演示身份：

```yaml
sky:
  ai:
    demo-enabled: false
```

`application-dev.yml` 再显式覆盖：

```yaml
sky:
  ai:
    demo-enabled: ${AI_DEMO_ENABLED:true}
```

因此安全默认值在基础配置中，开发便利只存在于 dev profile。这比“基础配置默认放行，prod 再尝试关闭”更稳，因为遗漏生产配置时系统会倾向拒绝访问，而不是意外放行。

参考：[Spring Boot Profiles 官方文档](https://docs.spring.io/spring-boot/reference/features/profiles.html)

## 4. 为什么异常要同时表达 HTTP 401 和业务错误体

原项目的 `GlobalExceptionHandler` 会把业务异常包装成：

```json
{"code": 0, "msg": "错误信息", "data": null}
```

但如果 HTTP 状态仍是 200，网关、浏览器、监控和调用方很难区分“请求成功但业务失败”和“身份未认证”。所以 `UserNotLoginException` 现在单独映射为：

```text
HTTP 401 Unauthorized
Body: {"code":0,"msg":"请先登录后再使用 AI 服务","data":null}
```

两层含义并不冲突：HTTP 状态给基础设施和客户端流程判断，统一业务体给前端展示具体错误。

## 5. MockMvc 测试在测什么

MockMvc 不启动真实 Tomcat，但会经过 Spring MVC 的请求映射、JSON 反序列化、Controller、异常处理器和响应序列化。因此它比直接调用 Controller 方法更接近真实 HTTP 行为，同时比启动完整应用更快。

本次共 6 个测试：

| 测试层 | 场景 | 期望 |
|---|---|---|
| `AiUserResolverTest` | dev 无 token | 返回演示用户 1 |
| `AiUserResolverTest` | prod 无 token | 抛未登录异常 |
| `AiUserResolverTest` | 合法 JWT | 返回 JWT 中 userId |
| `AiUserResolverTest` | dev 中坏 token | 仍拒绝，不降级 |
| `ServiceAgentControllerTest` | 身份解析失败 | HTTP 401 + `code=0` |
| `ServiceAgentControllerTest` | 身份解析成功 | userId/sessionId 正确转发 |

运行命令：

```powershell
mvn -B -pl sky-server -am test
```

结果：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。

参考：[Spring MockMvc 官方文档](https://docs.spring.io/spring-framework/reference/testing/mockmvc.html)

## 6. 为什么暂时不引入 Testcontainers

Testcontainers 可以在 JUnit 中启动临时 MySQL/Redis，非常适合验证 Mapper、事务和真实数据库差异。但本项目此前连基础 Java 测试都没有，直接加入容器测试会同时引入 Docker 环境、启动时间和数据初始化复杂度。

当前更合理的学习顺序是：

1. 先学会写快速的单元测试和 MockMvc 契约测试；
2. 再补 Service/Mapper 的数据库集成测试；
3. 当 H2 与 MySQL 行为差异或真实事务成为痛点时，再引入 Testcontainers。

这叫“按问题引入工具”，不是“为了技术栈好看而引入工具”。

候选项目：[Testcontainers Java（MIT）](https://github.com/testcontainers/testcontainers-java)

## 7. 面试时怎么讲

可以按下面的 STAR 结构回答：

> 我在给 AI 点餐与 Python Agent 增加演示入口时，发现缺失或无效 JWT 都会静默降级到演示用户 1，这在生产环境可能导致身份混淆。我把重复的 JWT 逻辑抽成统一的 `AiUserResolver`，通过 Spring Profile 让 dev 允许无 token 演示、prod 默认拒绝，并规定坏 token 在任何环境都返回 401。随后用 JUnit 和 MockMvc 覆盖 6 个身份与转发场景，确保改造不是只靠人工点击验证。

## 8. 你应该能回答的自测题

1. 为什么 `sessionId`、请求体里的 `userId` 都不能替代 JWT 身份？
2. 为什么 dev 模式下“无 token”可以演示，但“坏 token”仍应该返回 401？
3. 为什么把 `demo-enabled=false` 放在基础配置，而不是只在 prod 配置中关闭？
4. MockMvc 与直接调用 Controller 方法相比，多验证了哪些环节？
5. 为什么本阶段没有直接引入 Testcontainers？什么情况下应该引入？

如果这 5 题能用自己的语言回答清楚，这次改造才真正变成你的能力。
