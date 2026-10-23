package com.lifeguards.liveguard.common;

import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 활성 정책 버전(TBD-08). BE·Agent·RAG가 환경변수 {@code LIVEGUARD_POLICY_VERSION}으로 같은 값을 쓴다.
 * 필터 버전({@code MessageFilter.version()})과는 별개다.
 * 새 후보는 활성 버전으로 기록하고, 이미 있는 사건은 기록된 버전으로 계속 판정한다(v3.2 전환, A-27).
 */
@ConfigurationProperties("liveguard.policy")
public record PolicyProperties(String version) {

	public static final String DEFAULT_VERSION = "v3.2";

	/** 버전별 정책 조항 ID. 판정의 policyIds는 사건 버전의 조항만 인용할 수 있다. v3.2는 CP-3.3(집단 대상 비하·배제)을 더했다. */
	private static final Set<String> V31_CLAUSES = Set.of(
			"CP-1.1", "CP-1.2", "CP-2.1", "CP-2.2", "CP-3.1", "CP-3.2", "CP-4.1", "CP-4.2",
			"CP-5.1", "CP-5.2", "SP-2.1", "SP-3.1", "CP-6.1", "MP-1.1", "MP-1.2", "MP-1.3",
			"MP-1.4", "MP-2.1", "MP-2.2", "MP-2.3", "MP-3.1", "MP-3.2", "MP-4.1", "MP-4.2",
			"MP-4.3", "SP-4.1", "MM-1.1", "MM-2.1", "CP-7.1");
	private static final Map<String, Set<String>> CLAUSES = Map.of(
			"v3.1", V31_CLAUSES,
			"v3.2", union(V31_CLAUSES, "CP-3.3"));

	/** 설정할 수 있는 버전. 되돌릴 때를 위해 v3.1도 받는다(openapi PolicyVersion). */
	public PolicyProperties {
		version = version == null || version.isBlank() ? DEFAULT_VERSION : version.strip();
		if (!CLAUSES.containsKey(version)) {
			throw new IllegalArgumentException(
					"지원하지 않는 정책 버전입니다: " + version + " (liveguard.policy.version은 " + CLAUSES.keySet() + " 중 하나)");
		}
	}

	/** 그 버전의 정책 조항 ID. 모르는 버전이면 빈 집합이다. */
	public static Set<String> clauseIds(String version) {
		return CLAUSES.getOrDefault(version, Set.of());
	}

	private static Set<String> union(Set<String> base, String... added) {
		var all = new java.util.HashSet<>(base);
		all.addAll(Set.of(added));
		return Set.copyOf(all);
	}

}
