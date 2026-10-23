package com.lifeguards.liveguard.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PolicyPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PolicyConfig.class);

	@Test
	void defaultsToV32WhenUnsetOrBlank() {
		assertEquals("v3.2", new PolicyProperties(null).version());
		assertEquals("v3.2", new PolicyProperties("  ").version());
		runner.run(context -> assertEquals("v3.2", context.getBean(PolicyProperties.class).version()));
	}

	@Test
	void bindsTheSupportedVersionsIgnoringSurroundingSpaces() {
		// v3.1은 되돌리기용으로 계속 받는다(A-27)
		runner.withPropertyValues("liveguard.policy.version= v3.1 ")
				.run(context -> assertEquals("v3.1", context.getBean(PolicyProperties.class).version()));
		runner.withPropertyValues("liveguard.policy.version=v3.2")
				.run(context -> assertEquals("v3.2", context.getBean(PolicyProperties.class).version()));
	}

	@Test
	void rejectsUnsupportedVersionsWhileBinding() {
		assertThrows(IllegalArgumentException.class, () -> new PolicyProperties("v3.3"));
		assertThrows(IllegalArgumentException.class, () -> new PolicyProperties("3.1"));
		runner.withPropertyValues("liveguard.policy.version=v3.3")
				.run(context -> assertNotNull(context.getStartupFailure()));
	}

	@Test
	void onlyV32AddsTheGroupHarassmentClause() {
		assertTrue(PolicyProperties.clauseIds("v3.2").contains("CP-3.3"));
		assertFalse(PolicyProperties.clauseIds("v3.1").contains("CP-3.3"));
		assertTrue(PolicyProperties.clauseIds("v3.2").containsAll(PolicyProperties.clauseIds("v3.1")));
		assertEquals(PolicyProperties.clauseIds("v3.1").size() + 1, PolicyProperties.clauseIds("v3.2").size());
		assertTrue(PolicyProperties.clauseIds("v9").isEmpty());
	}

	@Test
	void onlyV31RequiresVerifiedPiiFactsForConfirmation() {
		assertTrue(PolicyProperties.requiresVerifiedPii("v3.1"));
		assertFalse(PolicyProperties.requiresVerifiedPii("v3.2"));
		// 모르는 버전은 닫힌 기본값이다
		assertTrue(PolicyProperties.requiresVerifiedPii("null"));
	}

}
