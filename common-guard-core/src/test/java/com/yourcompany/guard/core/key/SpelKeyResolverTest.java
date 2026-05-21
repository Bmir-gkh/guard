package com.yourcompany.guard.core.key;

import com.yourcompany.guard.core.exception.IllegalExpressionException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpelKeyResolverTest {

    static class Req {
        private final String orderNo;

        Req(String orderNo) {
            this.orderNo = orderNo;
        }

        public String getOrderNo() {
            return orderNo;
        }
    }

    static class Demo {
        public String createOrder(Req req, String ip) {
            return "OK";
        }
    }

    static class WebDemo {
        public String handle(HttpServletRequest request, String username) {
            return "OK";
        }
    }

    @Test
    void resolvesKeyWithAppAndPrefix() throws Exception {
        SpelKeyResolverOptions options = new SpelKeyResolverOptions("demo-app", 256, 512, 100);
        SpelKeyResolver resolver = new SpelKeyResolver(options);

        Method method = Demo.class.getMethod("createOrder", Req.class, String.class);
        String key = resolver.resolveKey("idem:", "'order:' + #req.orderNo", method, new Object[]{new Req("A001"), "127.0.0.1"}, new Demo());

        assertEquals("demo-app:idem:order:A001", key);
    }

    @Test
    void injectsSafeRequestVariablesFromRequestArg() throws Exception {
        SpelKeyResolverOptions options = new SpelKeyResolverOptions("demo-app", 256, 512, 100);
        SpelKeyResolver resolver = new SpelKeyResolver(options);

        HttpServletRequest request = mockRequest(
                Map.of(
                        "Authorization", "Bearer abc",
                        "X-Device-Id", "d1"
                ),
                Map.of("username", new String[]{"tom"}),
                "1.1.1.1"
        );

        Method method = WebDemo.class.getMethod("handle", HttpServletRequest.class, String.class);
        Object[] args = new Object[]{request, "tom"};

        assertEquals("demo-app", resolver.evaluate("app", method, args, new WebDemo()));
        assertEquals("abc", resolver.evaluate("token", method, args, new WebDemo()));
        assertEquals("d1", resolver.evaluate("header['x-device-id']", method, args, new WebDemo()));
        assertEquals("tom", resolver.evaluate("param['username']", method, args, new WebDemo()));
        assertEquals("login:1.1.1.1:tom", resolver.evaluate("'login:' + ip + ':' + param['username']", method, args, new WebDemo()));
    }

    @Test
    void requestVariablesMissingReturnEmptyString() throws Exception {
        SpelKeyResolverOptions options = new SpelKeyResolverOptions("demo-app", 256, 512, 100);
        SpelKeyResolver resolver = new SpelKeyResolver(options);
        Method method = Demo.class.getMethod("createOrder", Req.class, String.class);

        assertEquals("", resolver.evaluate("token", method, new Object[]{new Req("A001"), "127.0.0.1"}, new Demo()));
    }

    @Test
    void resolveKeyThrowsWhenExpressionResultIsEmpty() throws Exception {
        SpelKeyResolverOptions options = new SpelKeyResolverOptions("demo-app", 256, 512, 100);
        SpelKeyResolver resolver = new SpelKeyResolver(options);
        Method method = Demo.class.getMethod("createOrder", Req.class, String.class);

        assertThrows(IllegalExpressionException.class, () ->
                resolver.resolveKey("idem:", "#req.orderNo", method, new Object[]{new Req(null), "127.0.0.1"}, new Demo())
        );
    }

    @Test
    void forbidsTypeReferenceAndConstructorAndMethodInvoke() throws Exception {
        SpelKeyResolverOptions options = new SpelKeyResolverOptions("app", 256, 512, 100);
        SpelKeyResolver resolver = new SpelKeyResolver(options);
        Method method = Demo.class.getMethod("createOrder", Req.class, String.class);

        assertThrows(IllegalExpressionException.class, () ->
                resolver.evaluate("T(java.lang.Runtime).getRuntime()", method, new Object[]{new Req("A001"), "127.0.0.1"}, new Demo())
        );
        assertThrows(IllegalExpressionException.class, () ->
                resolver.evaluate("new java.lang.ProcessBuilder()", method, new Object[]{new Req("A001"), "127.0.0.1"}, new Demo())
        );
        assertThrows(IllegalExpressionException.class, () ->
                resolver.evaluate("#ip.toUpperCase()", method, new Object[]{new Req("A001"), "127.0.0.1"}, new Demo())
        );
    }

    private static HttpServletRequest mockRequest(Map<String, String> headers, Map<String, String[]> params, String remoteAddr) {
        List<String> headerNames = headers == null ? List.of() : headers.keySet().stream().toList();
        return (HttpServletRequest) Proxy.newProxyInstance(
                SpelKeyResolverTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getHeaderNames")) {
                        return enumerationOf(headerNames);
                    }
                    if (name.equals("getHeader")) {
                        String key = args == null || args.length == 0 ? null : String.valueOf(args[0]);
                        return headers == null ? null : headers.get(key);
                    }
                    if (name.equals("getParameterMap")) {
                        return params == null ? Map.of() : params;
                    }
                    if (name.equals("getRemoteAddr")) {
                        return remoteAddr;
                    }
                    if (name.equals("toString")) {
                        return "mockRequest";
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("equals")) {
                        return proxy == (args == null ? null : args[0]);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                }
        );
    }

    private static Enumeration<String> enumerationOf(List<String> values) {
        String[] array = values == null ? new String[0] : values.toArray(new String[0]);
        return new Enumeration<>() {
            int i = 0;

            @Override
            public boolean hasMoreElements() {
                return i < array.length;
            }

            @Override
            public String nextElement() {
                return array[i++];
            }
        };
    }
}
