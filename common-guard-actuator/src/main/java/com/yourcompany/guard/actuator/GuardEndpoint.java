package com.yourcompany.guard.actuator;

import com.yourcompany.guard.autoconfigure.GuardProperties;
import com.yourcompany.guard.store.api.GuardStore;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Endpoint(id = "guard")
public class GuardEndpoint {
    private final GuardProperties properties;
    private final GuardStore guardStore;

    public GuardEndpoint(GuardProperties properties, GuardStore guardStore) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.guardStore = Objects.requireNonNull(guardStore, "guardStore");
    }

    @ReadOperation
    public Map<String, Object> guard() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("store", properties.getStore());
        result.put("idempotentKeyPrefix", properties.getIdempotent().getKeyPrefix());
        result.put("rateLimitKeyPrefix", properties.getRateLimit().getKeyPrefix());
        result.put("storeClass", guardStore.getClass().getName());
        return result;
    }
}

