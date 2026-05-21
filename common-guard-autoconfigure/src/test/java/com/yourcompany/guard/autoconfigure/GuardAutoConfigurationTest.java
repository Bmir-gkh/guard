package com.yourcompany.guard.autoconfigure;

import com.yourcompany.guard.core.key.GuardKeyResolver;
import com.yourcompany.guard.store.api.GuardStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GuardAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GuardAutoConfiguration.class));

    @Test
    void autoStoreFallsBackToCaffeineWhenPresent() {
        contextRunner
                .withPropertyValues(
                        "spring.application.name=test-app",
                        "common.guard.enabled=true",
                        "common.guard.store=auto",
                        "common.guard.caffeine.max-size=10000",
                        "common.guard.caffeine.expire-after-write-seconds=600"
                )
                .run(context -> {
                    GuardStore store = context.getBean(GuardStore.class);
                    assertNotNull(store);
                    assertEquals("com.yourcompany.guard.store.caffeine.CaffeineGuardStore", store.getClass().getName());

                    GuardKeyResolver resolver = context.getBean(GuardKeyResolver.class);
                    assertNotNull(resolver);
                });
    }
}

