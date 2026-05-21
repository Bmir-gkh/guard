package com.yourcompany.guard.core.key;

import com.yourcompany.guard.core.exception.IllegalExpressionException;
import org.springframework.context.expression.MapAccessor;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 基于 SpEL 的 Key 解析器。
 *
 * 关键点：
 * 1. Key 自动加隔离前缀：{appName}:{prefix}:{value}，避免多应用冲突
 * 2. 安全：通过 SecureEvaluationContextFactory 禁用类型引用/构造器/方法调用，防止 SpEL 注入
 * 3. 资源控制：限制表达式长度、最终 Key 长度、缓存数量，避免恶意长 Key 与缓存撑爆
 */
public class SpelKeyResolver implements GuardKeyResolver {
    private final SpelKeyResolverOptions options;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> expressionOrder = new ConcurrentLinkedDeque<>();

    public SpelKeyResolver(SpelKeyResolverOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String resolveKey(String prefix, String expression, Method method, Object[] args, Object target) {
        String value = evaluate(expression, method, args, target);
        if (value == null || value.isBlank()) {
            throw new IllegalExpressionException("表达式结果为空: " + expression);
        }

        // 统一修正 prefix 的冒号格式，避免出现多余 ":" 导致的 key 不一致
        String p = prefix == null ? "" : prefix.trim();
        while (p.startsWith(":")) {
            p = p.substring(1);
        }
        while (p.endsWith(":")) {
            p = p.substring(0, p.length() - 1);
        }

        String app = options.getAppName();
        String fullKey = p.isEmpty() ? (app + ":" + value) : (app + ":" + p + ":" + value);
        if (fullKey.length() > options.getKeyMaxLength()) {
            throw new IllegalExpressionException("Key 长度超限(" + fullKey.length() + " > " + options.getKeyMaxLength() + ")");
        }
        return fullKey;
    }

    @Override
    public String evaluate(String expression, Method method, Object[] args, Object target) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalExpressionException("表达式不能为空");
        }
        if (expression.length() > options.getExpressionMaxLength()) {
            throw new IllegalExpressionException("表达式长度超限(" + expression.length() + " > " + options.getExpressionMaxLength() + ")");
        }

        try {
            Expression exp = getOrParse(expression);
            // 安全上下文：禁止调用静态方法、构造器、任意方法，只允许读取变量/属性
            StandardEvaluationContext context = SecureEvaluationContextFactory.create(method, args, target);
            // 预置安全变量：app/header/param/ip/token/args，同时保留方法入参变量绑定
            bindVariables(context, method, args);
            Object value = exp.getValue(context);
            return value == null ? "" : String.valueOf(value);
        } catch (IllegalExpressionException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalExpressionException("表达式解析失败: " + expression, e);
        }
    }

    private Expression getOrParse(String expression) {
        Expression cached = expressionCache.get(expression);
        if (cached != null) {
            return cached;
        }

        // 解析结果缓存，避免每次都 parse 影响性能
        Expression parsed = parser.parseExpression(expression);
        Expression existing = expressionCache.putIfAbsent(expression, parsed);
        if (existing == null) {
            expressionOrder.addLast(expression);
            trimCacheIfNeeded();
            return parsed;
        }
        return existing;
    }

    private void trimCacheIfNeeded() {
        // 简单 FIFO 淘汰，控制缓存上限，避免表达式缓存被恶意撑爆
        int max = options.getExpressionCacheSize();
        if (max <= 0) {
            expressionCache.clear();
            expressionOrder.clear();
            return;
        }
        while (expressionCache.size() > max) {
            String first = expressionOrder.pollFirst();
            if (first == null) {
                return;
            }
            expressionCache.remove(first);
        }
    }

    private void bindVariables(StandardEvaluationContext context, Method method, Object[] args) {
        Object[] safeArgs = args == null ? new Object[0] : args;
        Map<String, Object> root = new HashMap<>();
        root.put("args", safeArgs);
        root.put("app", options.getAppName());
        root.put("header", Collections.<String, String>emptyMap());
        root.put("param", Collections.<String, String>emptyMap());
        root.put("ip", "");
        root.put("token", "");
        root.put("headers", Collections.<String, String>emptyMap());
        root.put("clientIp", "");

        RequestSnapshot snapshot = RequestSnapshot.tryFromSpringRequestContext();
        if (snapshot == null) {
            snapshot = RequestSnapshot.tryFromArgs(safeArgs);
        }
        if (snapshot != null) {
            root.put("header", snapshot.headerCaseInsensitive);
            root.put("param", snapshot.paramFirstValue);
            root.put("ip", snapshot.ip);
            root.put("token", snapshot.token);

            // 向后兼容：旧变量名仍可用
            root.put("headers", snapshot.headersCompat);
            root.put("clientIp", snapshot.ip);

            context.setVariable("header", snapshot.headerCaseInsensitive);
            context.setVariable("param", snapshot.paramFirstValue);
            context.setVariable("ip", snapshot.ip);
            context.setVariable("token", snapshot.token);
            context.setVariable("headers", snapshot.headersCompat);
            context.setVariable("clientIp", snapshot.ip);
        }

        context.setVariable("args", safeArgs);
        context.setVariable("app", options.getAppName());
        context.setVariable("header", (Map<String, String>) root.get("header"));
        context.setVariable("param", (Map<String, String>) root.get("param"));
        context.setVariable("ip", String.valueOf(root.get("ip")));
        context.setVariable("token", String.valueOf(root.get("token")));
        context.setVariable("headers", (Map<String, String>) root.get("headers"));
        context.setVariable("clientIp", String.valueOf(root.get("clientIp")));

        root.put("target", context.getRootObject() == null ? null : context.getRootObject().getValue());
        context.setRootObject(root);
        context.addPropertyAccessor(new MapAccessor());

        if (safeArgs.length == 0) {
            return;
        }
        String[] names = parameterNameDiscoverer.getParameterNames(method);
        if (names == null || names.length == 0) {
            // 无法获取参数名时，提供 p0/p1/... 变量，保证表达式仍可用
            for (int i = 0; i < safeArgs.length; i++) {
                context.setVariable("p" + i, safeArgs[i]);
                root.put("p" + i, safeArgs[i]);
            }
            return;
        }
        for (int i = 0; i < Math.min(names.length, safeArgs.length); i++) {
            context.setVariable(names[i], safeArgs[i]);
            root.put(names[i], safeArgs[i]);
        }
    }

    private static final class RequestSnapshot {
        final Map<String, String> headerLowercase;
        final Map<String, String> headerCaseInsensitive;
        final Map<String, String> headersCompat;
        final Map<String, String> paramFirstValue;
        final String ip;
        final String token;

        private RequestSnapshot(
                Map<String, String> headerLowercase,
                Map<String, String> headerCaseInsensitive,
                Map<String, String> headersCompat,
                Map<String, String> paramFirstValue,
                String ip,
                String token
        ) {
            this.headerLowercase = headerLowercase;
            this.headerCaseInsensitive = headerCaseInsensitive;
            this.headersCompat = headersCompat;
            this.paramFirstValue = paramFirstValue;
            this.ip = ip == null ? "" : ip;
            this.token = token == null ? "" : token;
        }

        static RequestSnapshot tryFromSpringRequestContext() {
            Object request = getRequestFromRequestContextHolder();
            if (request == null) {
                return null;
            }
            return tryFromRequestObject(request);
        }

        static RequestSnapshot tryFromArgs(Object[] args) {
            if (args == null || args.length == 0) {
                return null;
            }
            for (Object arg : args) {
                if (arg == null) {
                    continue;
                }
                if (isServletRequest(arg)) {
                    RequestSnapshot snapshot = tryFromRequestObject(arg);
                    if (snapshot != null) {
                        return snapshot;
                    }
                }
            }
            return null;
        }

        private static RequestSnapshot tryFromRequestObject(Object request) {
            try {
                Map<String, String> headerLower = new HashMap<>();
                Map<String, String> headerCompat = new HashMap<>();

                Enumeration<?> headerNames = (Enumeration<?>) invokeNoArg(request, "getHeaderNames");
                if (headerNames != null) {
                    int count = 0;
                    while (headerNames.hasMoreElements() && count < 200) {
                        Object nameObj = headerNames.nextElement();
                        if (!(nameObj instanceof String name) || name.isBlank()) {
                            continue;
                        }
                        String value = safeGetHeader(request, name);
                        if (value != null) {
                            String limited = limitLength(value, 1024);
                            String lowerName = name.toLowerCase(Locale.ROOT);
                            headerLower.put(lowerName, limited);
                            headerCompat.put(name, limited);
                            headerCompat.put(lowerName, limited);
                        }
                        count++;
                    }
                }

                Map<String, String> param = new HashMap<>();
                Object paramMapObj = invokeNoArg(request, "getParameterMap");
                if (paramMapObj instanceof Map<?, ?> m) {
                    int count = 0;
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (count >= 200) {
                            break;
                        }
                        if (!(e.getKey() instanceof String key) || key.isBlank()) {
                            continue;
                        }
                        String first = firstParamValue(e.getValue());
                        if (first != null) {
                            param.put(key, limitLength(first, 1024));
                        }
                        count++;
                    }
                }

                String ip = null;
                try {
                    Object ipObj = invokeNoArg(request, "getRemoteAddr");
                    ip = ipObj == null ? null : String.valueOf(ipObj);
                } catch (Exception ignored) {
                }

                String token = extractBearerToken(headerLower);

                return new RequestSnapshot(
                        Collections.unmodifiableMap(headerLower),
                        new CaseInsensitiveStringMap(Collections.unmodifiableMap(headerLower)),
                        Collections.unmodifiableMap(headerCompat),
                        Collections.unmodifiableMap(param),
                        ip,
                        token
                );
            } catch (Exception e) {
                return null;
            }
        }

        private static String firstParamValue(Object raw) {
            if (raw == null) {
                return null;
            }
            if (raw instanceof String[] arr) {
                return arr.length == 0 ? null : arr[0];
            }
            if (raw.getClass().isArray()) {
                Object first = java.lang.reflect.Array.getLength(raw) > 0 ? java.lang.reflect.Array.get(raw, 0) : null;
                return first == null ? null : String.valueOf(first);
            }
            if (raw instanceof Iterable<?> it) {
                for (Object o : it) {
                    return o == null ? null : String.valueOf(o);
                }
            }
            return String.valueOf(raw);
        }

        private static String extractBearerToken(Map<String, String> headerLower) {
            String auth = headerLower.get("authorization");
            if (auth == null || auth.isBlank()) {
                return "";
            }
            String trimmed = auth.trim();
            if (trimmed.length() >= 7 && trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return trimmed.substring(7).trim();
            }
            return trimmed;
        }

        private static String safeGetHeader(Object request, String name) {
            try {
                Object v = invoke(request, "getHeader", new Class<?>[]{String.class}, new Object[]{name});
                return v == null ? null : String.valueOf(v);
            } catch (Exception e) {
                return null;
            }
        }

        private static Object getRequestFromRequestContextHolder() {
            try {
                Class<?> holder = Class.forName("org.springframework.web.context.request.RequestContextHolder");
                Object attrs = holder.getMethod("getRequestAttributes").invoke(null);
                if (attrs == null) {
                    return null;
                }
                if (!"org.springframework.web.context.request.ServletRequestAttributes".equals(attrs.getClass().getName())) {
                    return null;
                }
                return invokeNoArg(attrs, "getRequest");
            } catch (ClassNotFoundException e) {
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        private static boolean isServletRequest(Object obj) {
            return implementsInterface(obj.getClass(), "jakarta.servlet.http.HttpServletRequest")
                    || implementsInterface(obj.getClass(), "javax.servlet.http.HttpServletRequest");
        }

        private static boolean implementsInterface(Class<?> type, String interfaceName) {
            for (Class<?> itf : type.getInterfaces()) {
                if (interfaceName.equals(itf.getName())) {
                    return true;
                }
            }
            Class<?> superClass = type.getSuperclass();
            if (superClass == null || superClass == Object.class) {
                return false;
            }
            return implementsInterface(superClass, interfaceName);
        }

        private static Object invokeNoArg(Object target, String methodName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
            return target.getClass().getMethod(methodName).invoke(target);
        }

        private static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object[] args)
                throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
            Method m = target.getClass().getMethod(methodName, paramTypes);
            return m.invoke(target, args);
        }

        private static String limitLength(String value, int max) {
            if (value == null) {
                return null;
            }
            if (value.length() <= max) {
                return value;
            }
            return value.substring(0, max);
        }
    }

    private static final class CaseInsensitiveStringMap implements Map<String, String> {
        private final Map<String, String> lowerMap;

        private CaseInsensitiveStringMap(Map<String, String> lowerMap) {
            this.lowerMap = lowerMap == null ? Collections.emptyMap() : lowerMap;
        }

        @Override
        public int size() {
            return lowerMap.size();
        }

        @Override
        public boolean isEmpty() {
            return lowerMap.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            if (key instanceof String s) {
                return lowerMap.containsKey(s.toLowerCase(Locale.ROOT));
            }
            return false;
        }

        @Override
        public boolean containsValue(Object value) {
            return lowerMap.containsValue(value);
        }

        @Override
        public String get(Object key) {
            if (key instanceof String s) {
                return lowerMap.get(s.toLowerCase(Locale.ROOT));
            }
            return null;
        }

        @Override
        public String put(String key, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String remove(Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putAll(Map<? extends String, ? extends String> m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Set<String> keySet() {
            return lowerMap.keySet();
        }

        @Override
        public java.util.Collection<String> values() {
            return lowerMap.values();
        }

        @Override
        public java.util.Set<Entry<String, String>> entrySet() {
            return lowerMap.entrySet();
        }
    }
}
