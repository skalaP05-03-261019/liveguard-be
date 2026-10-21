package com.lifeguards.liveguard.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PolicyPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PolicyConfig.class);

	@Test
	void defaultsToV31WhenUnsetOrBlank() {
		assertEquals("v3.1", new PolicyProperties(null).version());
		assertEquals("v3.1", new PolicyProperties("  ").version());
		runner.run(context -> assertEquals("v3.1", context.getBean(PolicyProperties.class).version()));
	}

	@Test
	void bindsTheConfiguredVersion() {
		runner.withPropertyValues("liveguard.policy.version= v3.2 ")
				.run(context -> assertEquals("v3.2", context.getBean(PolicyProperties.class).version()));
	}

}
