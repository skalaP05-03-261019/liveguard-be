package com.lifeguards.liveguard.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 활성 정책 버전(TBD-08). BE·Agent·RAG가 환경변수 {@code LIVEGUARD_POLICY_VERSION}으로 같은 값을 쓴다.
 * 필터 버전({@code MessageFilter.version()})과는 별개다.
 */
@ConfigurationProperties("liveguard.policy")
public record PolicyProperties(String version) {

	public static final String DEFAULT_VERSION = "v3.1";

	public PolicyProperties {
		version = version == null || version.isBlank() ? DEFAULT_VERSION : version.strip();
	}

}
