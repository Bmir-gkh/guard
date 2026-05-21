package com.yourcompany.guard.actuator;

import com.yourcompany.guard.autoconfigure.GuardProperties;
import com.yourcompany.guard.store.api.GuardStore;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(Endpoint.class)
public class GuardActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GuardEndpoint guardEndpoint(GuardProperties properties, GuardStore guardStore) {
        return new GuardEndpoint(properties, guardStore);
    }
}

