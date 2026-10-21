package com.lifeguards.liveguard.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.lifeguards.liveguard.contracts.enums.MatchingTag;
import com.lifeguards.liveguard.filter.FilterInput;
import com.lifeguards.liveguard.filter.MessageFilter;
import com.lifeguards.liveguard.filter.TextNormalizer;

class MatchingTagContractTest {

	private final MessageFilter filter = new MessageFilter();

	@Test
	void enumValuesMatchTheOpenApiSchemaAndInternalCaseUsesIt() throws IOException {
		Map<?, ?> root;
		try (var input = Files.newInputStream(Path.of("contracts/openapi.yaml"))) {
			root = assertInstanceOf(Map.class, new Yaml().load(input));
		}
		Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) root.get("components")).get("schemas");

		Set<String> inContract = new HashSet<>(((List<?>) ((Map<?, ?>) schemas.get("MatchingTag")).get("enum"))
				.stream().map(Object::toString).toList());
		Set<String> inJava = Arrays.stream(MatchingTag.values()).map(Enum::name).collect(Collectors.toSet());
		assertEquals(inContract, inJava);
		assertEquals(9, inJava.size());

		Map<?, ?> items = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) schemas.get("InternalCase"))
				.get("properties")).get("matchingTags")).get("items");
		assertEquals("#/components/schemas/MatchingTag", items.get("$ref"));
	}

	@Test
	void everyTagTheFilterCanEmitIsInTheContractAndEachOneIsReachable() {
		Set<String> emitted = new HashSet<>();
		for (String text : List.of(
				"무대 미쳤다",                                              // ATTACK_TERM
				"씨발",                                                    // CERTAIN_PROFANITY
				"공식 이벤트 evil.test 에서 로그인하세요",                     // OFFICIAL_CLAIM, CREDENTIAL_REQUEST, LINK
				"같은 말")) {
			emitted.addAll(filter.classify(new FilterInput(text, TextNormalizer.normalize(text), Set.of(), 3))
					.matchingTags());
		}

		Set<String> valid = Arrays.stream(MatchingTag.values()).map(Enum::name).collect(Collectors.toSet());
		assertTrue(valid.containsAll(emitted), "계약에 없는 태그: " + emitted);
		// PII 태그는 PII 필터(TBD-02 3순위) 구현 전이라 필터가 아직 만들지 않는다. 계약(openapi v1.0, E-8)에는
		// 먼저 넣었으므로 "도달 가능" 검사에서만 뺀다. PII 필터를 구현하면 이 제외를 지우고 입력 예시를 추가한다.
		Set<String> notYetEmitted = Set.of(
				MatchingTag.PII_PHONE.name(), MatchingTag.PII_ACCOUNT.name(), MatchingTag.PII_EMAIL.name());
		Set<String> reachable = valid.stream().filter(tag -> !notYetEmitted.contains(tag)).collect(Collectors.toSet());
		assertEquals(reachable, emitted, "PII 태그를 뺀 enum의 모든 값이 실제로 만들어질 수 있어야 한다");
	}

}
