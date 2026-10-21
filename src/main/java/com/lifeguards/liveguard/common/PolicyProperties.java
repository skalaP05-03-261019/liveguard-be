package com.lifeguards.liveguard.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 활성 정책 버전(TBD-08). BE·Agent·RAG가 환경변수 {@code LIVEGUARD_POLICY_VERSION}으로 같은 값을 쓴다.
 * 필터 버전({@code MessageFilter.version()})과는 별개다.
 */
@ConfigurationProperties("liveguard.policy")
public record PolicyProperties(String version) {

	public static final String DEFAULT_VERSION = "v3.1";

	/** 필터 규칙과 후보 근거가 v3.1 기준이라 다른 값은 받지 않는다(TBD-08, openapi PolicyVersion). */
	public PolicyProperties {
		version = version == null || version.isBlank() ? DEFAULT_VERSION : version.strip();
		if (!DEFAULT_VERSION.equals(version)) {
			throw new IllegalArgumentException(
					"지원하지 않는 정책 버전입니다: " + version + " (liveguard.policy.version은 " + DEFAULT_VERSION + " 고정)");
		}
	}

}
