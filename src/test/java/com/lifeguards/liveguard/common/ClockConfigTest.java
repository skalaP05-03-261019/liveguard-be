package com.lifeguards.liveguard.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ClockConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ClockConfig.class);

	@Test
	void providesSingleUtcClock() {
		runner.run(context -> {
			assertEquals(1, context.getBeansOfType(Clock.class).size());
			assertEquals(ZoneOffset.UTC, context.getBean(Clock.class).getZone());
		});
	}

}
