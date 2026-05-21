# 写了一个通用的“幂等 + 限流”Spring Boot Starter：common-guard

平时做接口开发，最容易被忽视的两个点，一个是“重复提交”，另一个是“流量打爆”。前者让你出现重复扣款/重复写库/重复发券，后者让你短信接口一天把预算烧光，或者把系统拖到雪崩。

我一直不太喜欢在每个业务里重复写一套“幂等校验 + 限流计数 + Redis 脚本 + 异常兜底”，也不喜欢大家各自写一份，风格、策略、监控都不一致。于是就把这块抽成了一个通用 starter：**common-guard**。

这篇文章介绍：为什么做、怎么设计、怎么用、以及一些踩坑点。

---

## 1. 为什么要做这个

大多数项目里，幂等和限流通常是“被动补齐”的：

- 线上出现了重复提交，才去补一段“查 redis / 查数据库 / 加锁”的代码；
- 某个接口被刷，才临时上 nginx 限速、或者在 controller 里加个计数器；
- 代码写完后没有统一的监控指标，出了问题只能翻日志。

common-guard 的目标是把这些事情“前置”和“标准化”：

1) **业务侧接入简单**：注解一贴就生效  
2) **策略可控**：key 怎么生成、异常怎么处理、存储故障怎么兜底  
3) **存储可插拔**：单机/开发环境可以本地缓存，线上集群用 Redis  
4) **可观测**：知道重复提交多少、拒绝多少、存储异常多少  

---

## 2. 这个项目提供了什么

核心能力两件事：

- **幂等**：`@Idempotent`  
  - 原子抢占一个 key（带 TTL）
  - 抢占失败说明重复请求，走 handler 或直接抛异常
  - 业务异常时可选释放 key（允许重试）

- **限流**：`@RateLimit`  
  - 固定窗口或令牌桶
  - 支持 fallback 方法降级

额外能力：

- **SpEL Key 解析**（但做了安全限制）
- **预置安全变量**：`token/header/param/ip/app`，写 key 更省事
- **Micrometer 指标**：接 Prometheus/Grafana 很方便
- **可选 key 日志**：排障时输出 key（默认关闭，支持脱敏）

---

## 3. 设计思路：别让 SpEL 变成安全坑

很多人用 SpEL 生成 key 的第一反应是：

```java
"'user:' + #request.getHeader('Authorization')"
```

问题是：一旦放开方法调用、类型引用、构造器，SpEL 很容易从“拼 key”变成“执行表达式”，安全风险非常大。

common-guard 的做法是：

1) **禁用类型引用 / 构造器 / 方法调用**，只允许“读变量/属性”
2) **把常用的请求信息提前采集成安全快照**，作为变量注入给表达式用

所以你可以直接写：

```java
"'cfg:' + token"
```

或者：

```java
"'order:' + header['x-user-id'] + ':' + #req.orderNo"
```

不用在表达式里写复杂逻辑，也不用担心“表达式能干出你预料之外的事”。

---

## 4. 使用方式（推荐：只引一个 starter）

### 4.1 引入依赖

```xml
<dependency>
  <groupId>com.yourcompany</groupId>
  <artifactId>common-guard-spring-boot-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

然后根据你用的 store 再补一条依赖：

**本地模式（Caffeine）**

```xml
<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
</dependency>
```

**分布式模式（Redisson / Redis）**

建议只引入 redisson 核心库并自己提供 `RedissonClient`（避免跟现有 redis 配置冲突）：

```xml
<dependency>
  <groupId>org.redisson</groupId>
  <artifactId>redisson</artifactId>
</dependency>
```

### 4.2 基础配置

```yaml
common:
  guard:
    enabled: true
    store: auto   # auto/local/redisson
```

---

## 5. 写几个最常用的例子

### 5.1 防重复提交（基于 token）

```java
@Idempotent(key = "'cfg:' + token", expire = 3, timeUnit = TimeUnit.SECONDS, message = "请勿重复提交")
@PostMapping("/config")
public String saveConfig() {
  return "OK";
}
```

### 5.2 幂等（基于 header + 业务单号）

```java
public record OrderReq(String orderNo) {}

@Idempotent(key = "'order:' + header['x-user-id'] + ':' + #req.orderNo", bizNo = "#req.orderNo", expire = 30)
@PostMapping("/order")
public String create(@RequestBody OrderReq req) {
  return "OK";
}
```

这里的 `bizNo` 只是为了日志/排障定位，不影响幂等判定。

### 5.3 短信限流（固定窗口）

```java
@RateLimit(key = "'sms:' + ip", limit = 5, window = 60, timeUnit = TimeUnit.SECONDS)
@GetMapping("/send-sms")
public String sendSms() {
  return "OK";
}
```

含义：同一个 key 在 60 秒窗口内最多 5 次。

### 5.4 限流 + fallback 降级

```java
@RateLimit(key = "'query:' + ip", limit = 3, window = 10, fallback = "queryFallback")
@GetMapping("/query")
public String query() {
  return "OK";
}

public String queryFallback() {
  return "TOO_MANY_REQUESTS";
}
```

---

## 6. 一些实际使用建议

1) **key 的设计尽量稳定且可解释**  
推荐：`userId + 业务单号`。不要用随机值；也不要让 key “可能为空”。

2) **集群部署优先 Redisson**  
本地缓存适合开发环境或单机服务；多实例要一致性就用 Redis。

3) **存储异常的兜底策略要想清楚**  
默认是“存储异常时放行”，避免 Redis 抖一下就把业务全拒了。

4) **key 日志默认别开**  
排障时开一会儿可以，但不要长期输出原始 key（尤其 key 里拼了 token 的场景）。

---

## 7. 写在最后

common-guard 不是为了“炫技”，就是想把幂等与限流这两件事做成：

- 能复用
- 可观测
- 不踩安全坑
- 业务侧接入成本低

如果你也遇到过重复提交/接口被刷/线上限流策略不统一的问题，这类 starter 能省掉不少“每个项目重复造轮子”的成本。

项目更细的说明与更多场景示例见：

- 使用说明（详细版）：[USAGE.md](file:///Users/bmir/自我项目/guard/USAGE.md)
- 项目梳理与实现说明：[PROJECT_OVERVIEW.md](file:///Users/bmir/自我项目/guard/PROJECT_OVERVIEW.md)

