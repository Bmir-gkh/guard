# common-guard 项目梳理与实现说明

本文档用于把项目用到的技术、模块关系、关键实现逻辑、设计取舍和典型使用场景“理清思路”，便于二次开发与交付培训。

---

## 1. 这个项目解决什么问题

在 Web/API 服务中，常见两类保护需求：

1) **幂等**：避免同一业务请求被重复提交（尤其是支付/下单/配置提交等写操作）  
2) **限流**：保护接口免受高频访问（短信、登录、敏感操作等）

本项目提供的能力：

- 注解式接入：`@Idempotent` / `@RateLimit`
- SpEL Key：通过表达式生成 key，并自动带上应用隔离前缀
- 存储可插拔：Caffeine（本地）/ Redisson（Redis）
- Spring Boot 自动装配（推荐 starter 一键引入）
- 监控：Micrometer 指标、可选 Actuator 端点
- 诊断：可选输出 key 日志（默认关闭，支持脱敏/原文）

---

## 2. 用了哪些技术/组件

**Spring 生态**

- Spring AOP（`spring-boot-starter-aop`）：用切面拦截注解方法
- Spring Expression（SpEL）：解析 key 表达式
- Spring Boot AutoConfiguration：条件装配（`AutoConfiguration.imports`）
- （可选）Actuator：提供 `/actuator/guard`
- （可选）Micrometer：输出指标到 Prometheus/Grafana 等

**存储实现**

- Caffeine（可选）：本地缓存实现幂等与限流
- Redisson（可选）：Redis 分布式实现幂等与限流

**日志**

- SLF4J：输出 key 日志、排障信息

---

## 3. 模块结构与职责

```
common-guard
├── common-guard-annotations
├── common-guard-store-api
├── common-guard-core
├── common-guard-store-caffeine
├── common-guard-store-redisson
├── common-guard-autoconfigure
├── common-guard-spring-boot-starter
├── common-guard-actuator
└── common-guard-bom
```

**推荐交付**：业务方只需引入 `common-guard-spring-boot-starter`，其余模块作为内部实现传递依赖。

---

## 4. 关键接口与扩展点

### 4.1 GuardStore（存储抽象）

接口：[GuardStore](file:///Users/bmir/自我项目/guard/common-guard-store-api/src/main/java/com/yourcompany/guard/store/api/GuardStore.java)

- `acquireIdempotent(key, ttl)`：原子抢占幂等 key
- `releaseIdempotent(key)`：业务异常时可释放 key（允许重试）
- `acquireRate(request)`：限流获取许可（算法由 request 携带）

扩展：你可以新增 `common-guard-store-xxx` 模块实现该接口接入新的存储（例如 Hazelcast）。

### 4.2 GuardKeyResolver（key 解析）

接口：[GuardKeyResolver](file:///Users/bmir/自我项目/guard/common-guard-core/src/main/java/com/yourcompany/guard/core/key/GuardKeyResolver.java)

默认实现：[SpelKeyResolver](file:///Users/bmir/自我项目/guard/common-guard-core/src/main/java/com/yourcompany/guard/core/key/SpelKeyResolver.java)

扩展：可以自定义实现，替换 SpEL（例如用你们自己的模板/哈希策略）。

---

## 5. 重点逻辑：幂等切面如何工作

实现：[IdempotentAspect](file:///Users/bmir/自我项目/guard/common-guard-core/src/main/java/com/yourcompany/guard/core/aop/IdempotentAspect.java)

执行流程（简化）：

1. 读取 `@Idempotent`
2. 解析 key（SpEL + 应用隔离前缀）
3. `GuardStore.acquireIdempotent(key, ttl)` 抢占
4. 抢占失败：调用 `handler` 或抛 `IdempotentException`
5. 抢占成功：执行目标方法
6. 目标方法异常：按 `onException` 决定是否 `releaseIdempotent(key)`

关键配置：

- `common.guard.idempotent.fail-on-error`  
  - `false`（默认）：存储异常时放行，避免雪崩  
  - `true`：存储异常时拒绝，更严格

---

## 6. 重点逻辑：限流切面如何工作

实现：[RateLimitAspect](file:///Users/bmir/自我项目/guard/common-guard-core/src/main/java/com/yourcompany/guard/core/aop/RateLimitAspect.java)

执行流程（简化）：

1. 读取 `@RateLimit`
2. 解析 key（SpEL + 应用隔离前缀）
3. `GuardStore.acquireRate(request)` 判定是否允许
4. 不允许：执行 `fallback`（同参数方法）或抛 `RateLimitException`

支持算法（见注解 `algorithm`）：

- 固定窗口：`FIXED_WINDOW`
- 令牌桶：`TOKEN_BUCKET`

---

## 7. 重点逻辑：SpEL 安全变量与安全策略

实现：[SpelKeyResolver](file:///Users/bmir/自我项目/guard/common-guard-core/src/main/java/com/yourcompany/guard/core/key/SpelKeyResolver.java)

### 7.1 为什么要限制 SpEL

SpEL 如果开放 `T(xxx)`、`new`、方法调用，可能导致：

- 代码注入风险
- 资源耗尽（恶意长表达式/大量解析）

因此本项目禁用：

- 类型引用 `T(xxx)`
- 构造器 `new Xxx()`
- 任意方法调用 `#x.toUpperCase()`

实现位置：[SecureEvaluationContextFactory](file:///Users/bmir/自我项目/guard/common-guard-core/src/main/java/com/yourcompany/guard/core/key/SecureEvaluationContextFactory.java)

### 7.2 预置安全变量（强烈推荐使用）

项目把请求相关信息采集为“安全快照”，直接注入为简单变量与不可变 Map，避免你在 SpEL 里写复杂逻辑：

- `app`：应用名
- `header`：请求头 Map（读取忽略大小写）
- `param`：请求参数 Map（取第一个值）
- `ip`：客户端 IP
- `token`：从 `Authorization` 自动解析的纯 token（自动去 Bearer）
- `args`：方法入参数组

来源优先级：

1. Spring MVC 线程：`RequestContextHolder` 拿当前请求
2. 方法参数中包含 `HttpServletRequest`：从参数提取

### 7.3 关键约束：key 表达式结果不能为空

注解里的 `key` 表达式必须产生非空结果，否则会抛 `IllegalExpressionException`，避免产生 `...:null`、`...:` 这类不稳定 key 导致碰撞。

---

## 8. 存储实现说明（核心差异）

### 8.1 Caffeine（本地）

实现：[CaffeineGuardStore](file:///Users/bmir/自我项目/guard/common-guard-store-caffeine/src/main/java/com/yourcompany/guard/store/caffeine/CaffeineGuardStore.java)

- 幂等：`putIfAbsent` 原子语义
- 限流固定窗口：`key:windowIndex` 的计数器
- 限流令牌桶：每个 key 一个桶，按周期 refill

适用：单机/边缘降级/对跨节点一致性要求不高的场景。

### 8.2 Redisson（Redis）

实现：[RedissonGuardStore](file:///Users/bmir/自我项目/guard/common-guard-store-redisson/src/main/java/com/yourcompany/guard/store/redisson/RedissonGuardStore.java)

- 幂等：`RBucket.trySet` + TTL
- 固定窗口：`RAtomicLong` + 首次设置 TTL
- 令牌桶：`RRateLimiter`

适用：多实例部署、需要跨节点一致性的场景。

---

## 9. 自动装配与交付方式

自动装配：[GuardAutoConfiguration](file:///Users/bmir/自我项目/guard/common-guard-autoconfigure/src/main/java/com/yourcompany/guard/autoconfigure/GuardAutoConfiguration.java)

- `common.guard.enabled=true` 才生效
- `store=auto` 选择：优先 RedissonClient，否则 Caffeine

交付推荐：

- 使用方只引入：`common-guard-spring-boot-starter`
- 然后按 store 再引入外部依赖：`caffeine` 或 `redisson`

---

## 10. 监控与日志

### 10.1 指标（Micrometer）

实现：[MicrometerGuardMetrics](file:///Users/bmir/自我项目/guard/common-guard-autoconfigure/src/main/java/com/yourcompany/guard/autoconfigure/MicrometerGuardMetrics.java)

指标名称见 USAGE.md 的监控章节。

### 10.2 key 日志（排障）

默认关闭。开启方式：

```yaml
common:
  guard:
    log:
      enabled: true
      raw-key: false
      key-max-length: 256
```

- `raw-key=false`：脱敏输出 + `keyHash`（推荐）
- `raw-key=true`：输出原始 key（可能包含 token，谨慎）

---

## 11. 场景示例（覆盖常见用法）

### 11.1 写接口防重复提交（基于 token）

```java
@Idempotent(key = "'cfg:' + token", expire = 3, timeUnit = TimeUnit.SECONDS, message = "请勿重复提交")
@GetMapping("/config")
public Result<?> config() {
  return Result.ok();
}
```

### 11.2 写接口防重复提交（基于 header + 业务单号）

```java
@Idempotent(key = "'order:' + header['x-user-id'] + ':' + #req.orderNo", bizNo = "#req.orderNo", expire = 10)
@PostMapping("/order")
public Result<?> create(@RequestBody OrderReq req) {
  return Result.ok();
}
```

### 11.3 业务异常不释放 key（保持幂等保护）

```java
@Idempotent(key = "'pay:' + #req.payNo", onException = OnException.KEEP_KEY)
@PostMapping("/pay")
public Result<?> pay(@RequestBody PayReq req) {
  return Result.ok();
}
```

### 11.4 自定义重复请求处理器（返回自定义异常/业务码）

```java
public class BizIdemHandler implements IdempotentExceptionHandler {
  @Override
  public RuntimeException handle(IdempotentViolation v) {
    return new RuntimeException("重复提交: " + v.getBizNo());
  }
}

@Idempotent(key = "'x:' + token", handler = BizIdemHandler.class)
@PostMapping("/demo")
public String demo() {
  return "OK";
}
```

### 11.5 短信接口限流（按 IP + 用户名）

```java
@RateLimit(key = "'sms:' + ip + ':' + param['username']", limit = 5, window = 60)
@GetMapping("/send-sms")
public String sms() {
  return "OK";
}
```

### 11.6 登录限流（令牌桶，平滑突发）

```java
@RateLimit(key = "'login:' + ip", limit = 10, window = 60, algorithm = LimitAlgorithm.TOKEN_BUCKET)
@PostMapping("/login")
public String login() {
  return "OK";
}
```

### 11.7 被限流时 fallback 降级

```java
@RateLimit(key = "'login:' + ip", limit = 5, window = 60, fallback = "loginFallback")
@PostMapping("/login2")
public String login2() {
  return "OK";
}

public String loginFallback() {
  return "TOO_MANY_REQUESTS";
}
```

### 11.8 非 Web 场景（没有 request，使用方法参数）

```java
@Idempotent(key = "'job:' + #p0", expire = 30, timeUnit = TimeUnit.SECONDS)
public void runOnce(String jobId) {
}
```

---

## 12. 风险点与检查结论（本次细致检查）

已重点核查并处理/确认：

- SpEL key 的 header 访问大小写问题：`header['Authorization']` 与 `header['authorization']` 等价
- key 表达式结果为空时：直接抛异常，避免落入 `...:null` 造成碰撞
- Caffeine 实现 `timeUnit=null` 的 NPE 风险：已做默认值兜底
- key 日志默认关闭，避免默认泄露敏感信息；开启时支持脱敏与 hash

工程构建验证：

- `mvn test` 全量通过（含核心单测与自动装配测试）

