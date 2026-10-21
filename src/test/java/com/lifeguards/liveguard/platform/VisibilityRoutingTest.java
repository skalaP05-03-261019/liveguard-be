package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.platform.VisibilityRouting.Audience;
import com.lifeguards.liveguard.platform.VisibilityRouting.Route;

class VisibilityRoutingTest {

	@Test
	void restoreToPublicGoesToEveryoneWithContentFromAnyState() {
		for (Visibility previous : new Visibility[] { Visibility.HELD, Visibility.HIDDEN }) {
			assertEquals(new Route(Audience.EVERYONE, true), VisibilityRouting.of(previous, Visibility.PUBLIC));
		}
	}

	@Test
	void hidingAPublicMessageGoesToEveryoneWithoutContent() {
		assertEquals(new Route(Audience.EVERYONE, false), VisibilityRouting.of(Visibility.PUBLIC, Visibility.HIDDEN));
		assertEquals(new Route(Audience.EVERYONE, false), VisibilityRouting.of(Visibility.PUBLIC, Visibility.HELD));
	}

	@Test
	void changesBetweenHeldAndHiddenStayWithAuthorAndOperators() {
		assertEquals(new Route(Audience.AUTHOR_AND_OPERATORS, false),
				VisibilityRouting.of(Visibility.HELD, Visibility.HIDDEN));
		assertEquals(new Route(Audience.AUTHOR_AND_OPERATORS, false),
				VisibilityRouting.of(Visibility.HIDDEN, Visibility.HELD));
	}

	@ParameterizedTest
	@EnumSource(Visibility.class)
	void noChangeSendsNothing(Visibility visibility) {
		assertEquals(new Route(Audience.NOBODY, false), VisibilityRouting.of(visibility, visibility));
	}

	@Test
	void contentIsOnlyEverIncludedWhenTheNewStateIsPublic() {
		for (Visibility previous : Visibility.values()) {
			for (Visibility current : Visibility.values()) {
				boolean includesContent = VisibilityRouting.of(previous, current).includeContent();
				assertEquals(current == Visibility.PUBLIC && previous != current, includesContent,
						previous + " -> " + current);
			}
		}
	}

}
