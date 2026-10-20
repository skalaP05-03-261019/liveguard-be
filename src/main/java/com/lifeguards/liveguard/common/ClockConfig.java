package com.lifeguards.liveguard.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각은 {@code Instant.now()} 대신 이 Clock을 주입받아 쓴다 (강퇴 만료 시험용). */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
