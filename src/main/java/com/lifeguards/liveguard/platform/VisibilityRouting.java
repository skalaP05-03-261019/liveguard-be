package com.lifeguards.liveguard.platform;

import com.lifeguards.liveguard.contracts.enums.Visibility;

/**
 * MESSAGE_VISIBILITY_CHANGED를 누구에게 어떤 내용으로 보낼지 정한다.
 * <ul>
 * <li>PUBLIC으로 복원: 전원에게 원문 포함 (다른 시청자는 HELD·HIDDEN 동안 CHAT을 받은 적이 없다)</li>
 * <li>공개됐던(PUBLIC) 메시지를 가림: 전원에게 ID만 (화면에서 지우게. 원문 금지)</li>
 * <li>HELD에서 HIDDEN: 작성자·담당 운영자에게만 ID만 (다른 시청자는 받은 적이 없는 메시지)</li>
 * </ul>
 */
final class VisibilityRouting {

	enum Audience {
		EVERYONE, AUTHOR_AND_OPERATORS, NOBODY
	}

	record Route(Audience audience, boolean includeContent) {
	}

	private VisibilityRouting() {
	}

	static Route of(Visibility previous, Visibility current) {
		if (previous == current) {
			return new Route(Audience.NOBODY, false);
		}
		if (current == Visibility.PUBLIC) {
			return new Route(Audience.EVERYONE, true);
		}
		if (previous == Visibility.PUBLIC) {
			return new Route(Audience.EVERYONE, false);
		}
		return new Route(Audience.AUTHOR_AND_OPERATORS, false);
	}

}
