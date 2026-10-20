package com.lifeguards.liveguard.platform.port;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * guard(B2)가 포트 구현을 {@code @Component}/{@code @Service}로 등록하면 임시 구현이 자동으로 빠진다
 * (스캔된 컴포넌트는 이 설정의 {@code @Bean}보다 먼저 등록된다).
 */
@Configuration(proxyBeanMethods = false)
class PlatformPortsConfiguration {

	@Bean
	@ConditionalOnMissingBean(CandidateSink.class)
	InMemoryCandidateSink inMemoryCandidateSink() {
		return new InMemoryCandidateSink();
	}

	@Bean
	@ConditionalOnMissingBean(KickStatusQuery.class)
	InMemoryKickStatus inMemoryKickStatus(Clock clock) {
		return new InMemoryKickStatus(clock);
	}

	@Bean
	@ConditionalOnMissingBean(GuardEventPublisher.class)
	InMemoryGuardEventPublisher inMemoryGuardEventPublisher() {
		return new InMemoryGuardEventPublisher();
	}

}
