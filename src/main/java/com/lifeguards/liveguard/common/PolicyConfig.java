package com.lifeguards.liveguard.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PolicyProperties.class)
public class PolicyConfig {
}
