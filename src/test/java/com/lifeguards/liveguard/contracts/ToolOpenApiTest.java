package com.lifeguards.liveguard.contracts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.lifeguards.liveguard.contracts.dto.response.ActionExecutionResult;
import com.lifeguards.liveguard.contracts.dto.response.ChannelUserRole;
import com.lifeguards.liveguard.contracts.dto.response.KickEligibility;
import com.lifeguards.liveguard.contracts.dto.response.InternalCase;
import com.lifeguards.liveguard.contracts.dto.response.KickReviewBasis;
import com.lifeguards.liveguard.contracts.dto.response.SecurityFacts;
import com.lifeguards.liveguard.internal.BusinessActionCommand;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolOpenApiTest {
	private Map<?, ?> contract() throws IOException {
		try (var input = Files.newInputStream(Path.of("contracts/openapi.yaml"))) {
			return assertInstanceOf(Map.class, new Yaml().load(input));
		}
	}

	@Test
	void toolSchemasHaveTheSameFieldsAsJavaRecords() throws IOException {
		Map<?, ?> schemas = map(map(contract(), "components"), "schemas");
		for (Class<?> type : List.of(BusinessActionCommand.class, ActionExecutionResult.class,
				KickEligibility.class, KickReviewBasis.class, ChannelUserRole.class, SecurityFacts.class, InternalCase.class)) {
			Set<String> fields = Arrays.stream(type.getRecordComponents()).map(component -> component.getName())
					.collect(Collectors.toSet());
			assertEquals(fields, map(map(schemas, type.getSimpleName()), "properties").keySet(), type.getSimpleName());
		}
	}

	@Test
	void internalOperationsRequireServiceTokenAndHaveUniqueOperationIds() throws IOException {
		Map<?, ?> root = contract();
		assertEquals("X-Service-Token",
				map(map(map(root, "components"), "securitySchemes"), "serviceToken").get("name"));
		Set<String> operationIds = new HashSet<>();
		for (var path : map(root, "paths").entrySet()) {
			if (!path.getKey().toString().startsWith("/internal/")) {
				continue;
			}
			for (String verb : List.of("get", "post")) {
				Object operation = assertInstanceOf(Map.class, path.getValue()).get(verb);
				if (operation instanceof Map<?, ?> details) {
					Object operationId = details.get("operationId");
					assertNotNull(operationId);
					assertTrue(operationIds.add(operationId.toString()), "Duplicate operationId: " + operationId);
					List<?> security = assertInstanceOf(List.class, details.get("security"));
					assertTrue(security.stream().anyMatch(item -> item instanceof Map<?, ?> rule
							&& rule.containsKey("serviceToken")), path.getKey().toString());
				}
			}
		}
	}

	@Test
	void timeoutAndKickWindowsRemainSeparateAndReferencesResolve() throws IOException {
		Map<?, ?> root = contract();
		Map<?, ?> schemas = map(map(root, "components"), "schemas");
		Map<?, ?> timeoutProperties = map(map(map(schemas, "UserHistoryResponse"), "properties"), "data");
		assertTrue(!map(map(timeoutProperties, "properties"), "windowHours").containsKey("const"));
		assertEquals(24, map(map(map(schemas, "KickEligibility"), "properties"), "windowHours").get("const"));
		validateReferences(root, root);
	}

	private void validateReferences(Object node, Map<?, ?> root) {
		if (node instanceof Map<?, ?> entries) {
			if (entries.get("$ref") instanceof String ref && ref.startsWith("#/")) {
				Object resolved = root;
				for (String part : ref.substring(2).split("/")) {
					resolved = assertInstanceOf(Map.class, resolved).get(part.replace("~1", "/").replace("~0", "~"));
					assertNotNull(resolved, "Unresolved reference: " + ref);
				}
			}
			entries.values().forEach(value -> validateReferences(value, root));
		} else if (node instanceof List<?> values) {
			values.forEach(value -> validateReferences(value, root));
		}
	}

	private Map<?, ?> map(Map<?, ?> parent, String key) {
		return assertInstanceOf(Map.class, parent.get(key), key);
	}
}
