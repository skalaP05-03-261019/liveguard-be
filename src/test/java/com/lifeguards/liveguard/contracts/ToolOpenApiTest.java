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

import com.lifeguards.liveguard.contracts.dto.event.ActionEvent;
import com.lifeguards.liveguard.contracts.dto.event.CaseEvent;
import com.lifeguards.liveguard.contracts.dto.event.ContextReviewRequiredEvent;
import com.lifeguards.liveguard.contracts.dto.event.KickReviewRequiredEvent;
import com.lifeguards.liveguard.contracts.dto.event.ReviewDecidedEvent;
import com.lifeguards.liveguard.contracts.dto.request.ContextDecisionRequest;
import com.lifeguards.liveguard.contracts.dto.request.JudgmentRequest;
import com.lifeguards.liveguard.contracts.dto.request.KickDecisionRequest;
import com.lifeguards.liveguard.contracts.dto.response.ActionExecutionResult;
import com.lifeguards.liveguard.contracts.dto.response.CaseSummary;
import com.lifeguards.liveguard.contracts.dto.response.ContextReview;
import com.lifeguards.liveguard.contracts.dto.response.Decision;
import com.lifeguards.liveguard.contracts.dto.response.KickRevalidation;
import com.lifeguards.liveguard.contracts.dto.response.KickReview;
import com.lifeguards.liveguard.contracts.dto.response.Live;
import com.lifeguards.liveguard.contracts.dto.response.UserHistory;
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
			if (type == InternalCase.class) {
				fields.addAll(Set.of("targetMessageIds", "targetVersions"));
			}
			assertEquals(fields, map(map(schemas, type.getSimpleName()), "properties").keySet(), type.getSimpleName());
		}
	}

	/** v1.0에서 openapi에 맞춘 계약 DTO. 로직에 연결된 Message·ChatEvent 등은 liveId 구현 때 추가한다. */
	@Test
	void v1ContractRecordsHaveTheSameFieldsAsOpenApiSchemas() throws IOException {
		Map<?, ?> schemas = map(map(contract(), "components"), "schemas");
		Map<Class<?>, String> records = Map.ofEntries(
				Map.entry(UserHistory.class, "UserHistory"),
				Map.entry(UserHistory.RecentCase.class, "UserHistoryRecentCase"),
				Map.entry(CaseSummary.class, "CaseSummary"),
				Map.entry(CaseEvent.class, "CaseEvent"),
				Map.entry(KickReview.class, "KickReview"),
				Map.entry(ContextReview.class, "ContextReview"),
				Map.entry(Decision.class, "Decision"),
				Map.entry(Live.class, "Live"),
				Map.entry(KickRevalidation.class, "KickRevalidation"),
				Map.entry(KickDecisionRequest.class, "KickDecisionRequest"),
				Map.entry(ContextDecisionRequest.class, "ContextDecisionRequest"),
				Map.entry(JudgmentRequest.class, "JudgmentRequest"),
				Map.entry(JudgmentRequest.MessageFinding.class, "MessageFinding"),
				Map.entry(ContextReviewRequiredEvent.class, "ContextReviewRequiredEvent"),
				Map.entry(KickReviewRequiredEvent.class, "KickReviewRequiredEvent"),
				Map.entry(ReviewDecidedEvent.class, "ReviewDecidedEvent"),
				Map.entry(ActionEvent.class, "ActionEvent"));
		for (var entry : records.entrySet()) {
			Set<String> fields = Arrays.stream(entry.getKey().getRecordComponents()).map(component -> component.getName())
					.collect(Collectors.toSet());
			assertEquals(fields, map(map(schemas, entry.getValue()), "properties").keySet(), entry.getValue());
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
		Map<?, ?> timeoutProperties = map(map(schemas, "UserHistory"), "properties");
		assertTrue(!timeoutProperties.containsKey("windowHours"), "TIMEOUT history scope is the whole live (TBD-03)");
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
