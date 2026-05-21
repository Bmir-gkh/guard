# common-guard 使用说明（详细版）

本文档基于当前工程代码整理，面向“直接接入业务项目”的使用与运维说明（Java 17 / Spring Boot 3.x）。

---

## 1. 项目简介

`common-guard` 是一个通用的 **幂等** 与 **限流** 组件，提供：

- 注解式使用：`@Idempotent` / `@RateLimit`
- Key 统一生成：支持 SpEL 表达式，并带应用名隔离前缀
- 存储可插拔：本地 Caffeine 或 Redis/Redisson
- 自动装配：按 `common.guard.store` 与类路径条件自动启用
- 监控能力：Micrometer 指标 + 可选 Actuator 端点

---

## 2. 模块划分（为什么拆分）

工程采用“职责单一 + 最小依赖”的拆分方式，便于：

- 使用方按需引入（只要 starter 即可）
- 外部依赖不强绑（Caffeine/Redisson 由业务方按需引入或 BOM 统一）
- 未来扩展新的 store/算法时不影响核心

模块说明：

- `common-guard-annotations`：纯注解与枚举（无 Spring 依赖）
- `common-guard-store-api`：`GuardStore` 抽象与限流请求模型
- `common-guard-core`：AOP 切面、SpEL Key 解析安全控制、异常定义
- `common-guard-store-caffeine`：本地 Caffeine 实现（适合单机/轻量）
- `common-guard-store-redisson`：Redisson 实现（适合集群/Redis）
- `common-guard-autoconfigure`：配置绑定与条件装配
- `common-guard-actuator`：可选 `/actuator/guard` 端点
- `common-guard-bom`：可选 BOM（统一外部依赖版本）
- `common-guard-spring-boot-starter`：推荐交付方式（聚合一键引入）

---

## 3. 推荐接入方式（只引入一个 Starter）

### 3.1 Maven 依赖

业务项目只需要引入：

```xml
<dependency>
  <groupId>com.yourcompany</groupId>
  <artifactId>common-guard-spring-boot-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

#### A) 使用本地 Caffeine 存储（store=local 或 auto 回落到 local）

再额外引入 Caffeine 实际库：

```xml
<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
</dependency>
```

#### B) 使用 Redis/Redisson 存储（store=redisson 或 auto 优先 redisson）

引入 Redisson starter（并在业务侧配置 `RedissonClient` Bean）：

```xml
<dependency>
  <groupId>org.redisson</groupId>
  <artifactId>redisson-spring-boot-starter</artifactId>
</dependency>
```

### 3.2 配置文件示例

```yaml
common:
  guard:
    enabled: true
    store: auto         # auto/local/redisson
    idempotent:
      key-prefix: "idem:"
      fail-on-error: false
    rate-limit:
      key-prefix: "rl:"
      fail-on-error: false
    caffeine:
      max-size: 10000
      expire-after-write-seconds: 600
    security:
      expression-max-length: 256
      key-max-length: 512
      expression-cache-size: 1000
```

---

## 4. 配置项（逐项说明）

| 配置项 | 默认值 | 说明 |
|---|---:|---|
| `common.guard.enabled` | `true` | 总开关 |
| `common.guard.store` | `auto` | 存储模式：`auto`/`local`/`redisson` |
| `common.guard.idempotent.key-prefix` | `idem:` | 幂等业务前缀（最终 key 会自动拼上 `spring.application.name`） |
| `common.guard.idempotent.fail-on-error` | `false` | 幂等存储异常时：`false` 放行防雪崩；`true` 拒绝更严格 |
| `common.guard.rate-limit.key-prefix` | `rl:` | 限流业务前缀（最终 key 会自动拼上 `spring.application.name`） |
| `common.guard.rate-limit.fail-on-error` | `false` | 限流存储异常时：`false` 放行；`true` 拒绝 |
| `common.guard.caffeine.max-size` | `10000` | Caffeine 最大条目数上限（防内存被撑爆） |
| `common.guard.caffeine.expire-after-write-seconds` | `600` | TokenBucket 的默认过期时间（秒） |
| `common.guard.security.expression-max-length` | `256` | SpEL 表达式最大长度 |
| `common.guard.security.key-max-length` | `512` | 最终 key 最大长度 |
| `common.guard.security.expression-cache-size` | `1000` | SpEL 解析缓存上限 |
| `common.guard.log.enabled` | `false` | 是否输出 key 日志（默认关闭） |
| `common.guard.log.raw-key` | `false` | 是否输出原始 key（可能包含 token 等敏感信息，需谨慎） |
| `common.guard.log.key-max-length` | `256` | key 日志最大输出长度，超过截断 |

---

## 5. 注解用法

### 5.1 幂等：@Idempotent

源码：[Idempotent](file:///Users/bmir/自我项目/guard/common-guard-annotations/src/main/java/com/yourcompany/guard/annotations/Idempotent.java)

常用参数：

- `key`：SpEL 表达式（必填），用来生成幂等 Key
- `expire`/`timeUnit`：幂等 key 的 TTL
- `message`：重复请求时的默认提示
- `bizNo`：业务单号（可选 SpEL），便于记录与返回
- `handler`：自定义重复请求处理器（返回你想抛出的异常）
- `onException`：业务异常时是否删除 key（允许重试）

示例：

```java
@Idempotent(key = "'order:' + #req.orderNo", expire = 10)
@PostMapping("/order")
public String create(@RequestBody OrderReq req) {
  return "OK";
}
```

### 5.2 限流：@RateLimit

源码：[RateLimit](file:///Users/bmir/自我项目/guard/common-guard-annotations/src/main/java/com/yourcompany/guard/annotations/RateLimit.java)

常用参数：

- `key`：SpEL 表达式（必填），用来生成限流 key
- `limit`：窗口内允许次数
- `window`/`timeUnit`：窗口大小
- `algorithm`：`FIXED_WINDOW`（固定窗口）/ `TOKEN_BUCKET`（令牌桶）
- `fallback`：被限流时调用的降级方法名（必须与原方法入参一致）

示例（固定窗口）：

```java
@RateLimit(key = "'login:' + #ip", limit = 5, window = 60)
@GetMapping("/send-sms")
public String sendSms(@RequestParam String ip) {
  return "OK";
}
```

示例（fallback）：

```java
@RateLimit(key = "'sms:' + #ip", limit = 5, window = 60, fallback = "sendSmsFallback")
@GetMapping("/send-sms2")
public String sendSms2(@RequestParam String ip) {
  return "OK";
}

public String sendSmsFallback(String ip) {
  return "FALLBACK";
}
```

---

## 6. Key 生成与 SpEL 使用说明

### 6.1 Key 的最终格式

最终 key 始终包含应用隔离前缀：

```
{spring.application.name}:{业务前缀}:{SpEL结果}
```

例如 `spring.application.name=demo-app`，幂等前缀 `idem:`，表达式结果 `order:A001`：

```
demo-app:idem:order:A001
```

### 6.2 SpEL 变量（方法入参）

默认变量绑定：

- 如果能获取到参数名（建议开启 `-parameters`，本工程已在编译插件中开启），可用 `#req`、`#ip` 这种方式
- 如果获取不到参数名，仍可用 `#p0`、`#p1` 或 `#args[0]`

### 6.3 从 Header / 参数 / IP / Token 取值（推荐）

当运行在 Web 环境（Spring MVC）时，组件会尝试从当前请求中采集 **安全快照** 注入到 SpEL 上下文中（只暴露不可变 Map 与字符串），避免开发者在表达式里写复杂逻辑。

默认可用变量（推荐直接使用，不需要 `#`）：

- `header`：`Map<String,String>`，所有请求头（读取忽略大小写，`header['Authorization']` 与 `header['authorization']` 等价）
- `param`：`Map<String,String>`，所有 Query 参数（取第一个值）
- `ip`：`String`，客户端 IP（remoteAddr）
- `token`：`String`，从 `Authorization` 头自动提取的纯 token（自动去掉 `Bearer ` 前缀）
- `app`：`String`，应用名（`spring.application.name`）

向后兼容变量（仍可用）：

- `headers`：`Map<String,String>`，同时支持原始 header 名与小写 key
- `clientIp`：`String`

示例：

```java
@RateLimit(key = "'sms:' + header['x-device-id'] + ':' + ip", limit = 5, window = 60)
@GetMapping("/send-sms")
public String sendSms(HttpServletRequest request) {
  return "OK";
}
```

### 6.4 空值与非法表达式处理

- 表达式为空 / 超长 / 解析失败：抛 `IllegalExpressionException`
- 作为注解 `key` 使用时，表达式结果为空（null/空串/空白）：抛 `IllegalExpressionException`（避免 key 碰撞与脏数据）

### 6.5 SpEL 安全策略（防注入）

Key 解析明确禁用：

- 类型引用：`T(xxx)`
- 构造器：`new xxx()`
- 方法调用：例如 `#ip.toUpperCase()`

仅允许“读取变量/属性”以满足拼 key 场景。

---

## 7. 存储模式与行为差异

### 7.1 store=auto 的选择规则

优先级：

1. 容器中存在 `RedissonClient` 且类路径包含 Redisson 实现 -> 使用 redisson
2. 否则类路径存在 Caffeine -> 使用 local（Caffeine）
3. 都不可用 -> 启动失败（提示缺少依赖/配置）

### 7.2 Caffeine（本地）

源码：[CaffeineGuardStore](file:///Users/bmir/自我项目/guard/common-guard-store-caffeine/src/main/java/com/yourcompany/guard/store/caffeine/CaffeineGuardStore.java)

- 幂等：`putIfAbsent` 原子抢占
- 固定窗口：`key:windowIndex` 计数；窗口过期依赖 Caffeine 过期淘汰
- 令牌桶：每个 key 一个桶，按周期 refill（每周期补满）

建议：

- 多实例部署下，本地幂等不具备跨节点一致性；集群场景优先 redisson

### 7.3 Redisson（Redis）

源码：[RedissonGuardStore](file:///Users/bmir/自我项目/guard/common-guard-store-redisson/src/main/java/com/yourcompany/guard/store/redisson/RedissonGuardStore.java)

- 幂等：`RBucket.trySet` + TTL
- 固定窗口：`RAtomicLong` 计数，首次计数设置 TTL
- 令牌桶：`RRateLimiter`

建议：

- `store=redisson` 时确保 Redis 可用性与超时配置合理

---

## 8. 失败策略（fail-on-error）

当存储层出现异常（例如 Redis 超时）：

- `fail-on-error=false`（默认）：放行请求，避免存储异常导致全站雪崩
- `fail-on-error=true`：拒绝请求，更严格但可能扩大故障影响面

分别对应：

- `common.guard.idempotent.fail-on-error`
- `common.guard.rate-limit.fail-on-error`

---

## 9. 监控与运维

### 9.1 Micrometer 指标

当 Spring 容器中存在 `MeterRegistry` Bean 时自动启用：

- `guard.idempotent.acquire.time`：幂等存储耗时（Timer）
- `guard.idempotent.duplicate`：幂等重复次数（Counter）
- `guard.rate-limit.acquire.time`：限流存储耗时（Timer）
- `guard.rate-limit.rejected`：限流拒绝次数（Counter）
- `guard.store.error`：存储异常次数（Counter）

### 9.2 Actuator 端点（可选）

引入 `common-guard-actuator` 后提供：

- `/actuator/guard`：返回当前启用状态、store 模式、前缀、实际 GuardStore 实现类

---

## 9.3 Key 日志（可选）

默认不输出 key 日志（避免泄露敏感信息与日志膨胀）。如需排障可临时开启：

```yaml
common:
  guard:
    log:
      enabled: true
      raw-key: false
      key-max-length: 256
```

- `raw-key=false` 时会对 key 做简单脱敏并输出 `keyHash`（便于关联排查）
- 如确实需要输出原始完整 key，可将 `raw-key=true`（不建议在生产长期开启）

## 10. 常见问题（Troubleshooting）

1) **启动失败：GuardStore 未找到**
- 原因：`store=auto` 时既没有 `RedissonClient`，也没有引入 Caffeine 实际库
- 处理：引入 `caffeine` 或引入 `redisson-spring-boot-starter` 并配置 `RedissonClient`

2) **SpEL 表达式结果为空**
- 现象：抛 `IllegalExpressionException("表达式结果为空")`
- 处理：确保参与拼 key 的字段非空；必要时换成更稳的 key 设计（例如 userId + 业务单号）

3) **fallback 方法找不到**
- 原因：fallback 方法名不对或参数签名不一致
- 处理：fallback 方法需与原方法入参完全一致

---

## 10.1 ⚠️ 使用注意事项

### 1) 存储依赖必须按需引入

Starter 不会强制传递 Caffeine 或 Redisson 的底层实现，你需要根据 `store` 的配置自行引入对应依赖：

- **本地模式**（`store: local`）

```xml
<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
  <version>3.1.8</version>
</dependency>
```

- **分布式模式**（`store: redisson`）

需要引入 Redisson 核心库，并手动创建 `RedissonClient` Bean。

### 2) Redisson 模式下必须提供 RedissonClient Bean

如果你的项目已经使用 `spring-data-redis`，不建议引入 `redisson-spring-boot-starter`，否则可能与既有 Redis 配置产生冲突。

推荐做法：只引入 Redisson 核心库，并基于你现有的 Redis 配置创建 `RedissonClient`：

```xml
<dependency>
  <groupId>org.redisson</groupId>
  <artifactId>redisson</artifactId>
  <version>3.23.4</version>
</dependency>
```

```java
@Configuration
public class RedissonConfig {

  @Value("${spring.data.redis.host}")
  private String host;

  @Value("${spring.data.redis.port}")
  private int port;

  @Value("${spring.data.redis.password}")
  private String password;

  @Bean
  public RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer()
        .setAddress("redis://" + host + ":" + port)
        .setPassword(password);
    return Redisson.create(config);
  }
}
```

之后将 `common.guard.store` 配置为 `redisson` 或 `auto` 即可使用。

### 3) 常见启动报错

```text
java.lang.IllegalStateException: GuardStore 未找到
```

原因：未引入正确的存储依赖，或 `RedissonClient` Bean 缺失。

解决：按上述说明引入对应依赖并确保 Bean 存在。

## 11. 推荐实践

- Key 设计优先使用：用户唯一标识 + 业务唯一号（避免越权与碰撞）
- 对外接口（尤其写接口）建议幂等；对短信/验证码/登录等建议限流
- 集群部署优先选择 Redisson（Caffeine 适合单机或边缘降级）
- 对 Redis 故障敏感的业务优先 `fail-on-error=false`，配合监控告警
