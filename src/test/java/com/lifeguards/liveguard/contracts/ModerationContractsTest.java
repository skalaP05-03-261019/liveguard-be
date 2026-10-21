package com.lifeguards.liveguard.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.lifeguards.liveguard.contracts.dto.event.ChatEvent;
import com.lifeguards.liveguard.contracts.dto.event.UserKickedEvent;
import com.lifeguards.liveguard.contracts.dto.request.AgentEventRequest;
import com.lifeguards.liveguard.contracts.dto.request.JudgmentRequest;
import com.lifeguards.liveguard.contracts.dto.request.JudgmentRequest.MessageFinding;
import com.lifeguards.liveguard.contracts.dto.request.KickDecisionRequest;
import com.lifeguards.liveguard.contracts.dto.request.LoginRequest;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.CaseSummary;
import com.lifeguards.liveguard.contracts.dto.response.ContextData;
import com.lifeguards.liveguard.contracts.dto.response.ErrorResponse;
import com.lifeguards.liveguard.contracts.dto.response.JudgmentResult;
import com.lifeguards.liveguard.contracts.dto.response.KickResult;
import com.lifeguards.liveguard.contracts.dto.response.MaskedMessage;
import com.lifeguards.liveguard.contracts.dto.response.Me;
import com.lifeguards.liveguard.contracts.dto.response.Message;
import com.lifeguards.liveguard.contracts.dto.response.MessagePage;
import com.lifeguards.liveguard.contracts.dto.response.UserHistory;
import com.lifeguards.liveguard.contracts.enums.ActionStatus;
import com.lifeguards.liveguard.contracts.enums.ActionType;
import com.lifeguards.liveguard.contracts.enums.AgentEventType;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;
import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.ConfirmationStatus;
import com.lifeguards.liveguard.contracts.enums.ContextDecision;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.KickDecision;
import com.lifeguards.liveguard.contracts.enums.KickReviewStatus;
import com.lifeguards.liveguard.contracts.enums.MatchingTag;
import com.lifeguards.liveguard.contracts.enums.ModerationReasonCode;
import com.lifeguards.liveguard.contracts.enums.Observation;
import com.lifeguards.liveguard.contracts.enums.ReviewKind;
import com.lifeguards.liveguard.contracts.enums.ReviewStatus;
import com.lifeguards.liveguard.contracts.enums.Role;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

class ModerationContractsTest {

	private static final String EMOJI = "😀";

	/** contracts.enums의 값은 openapi.yaml의 같은 이름 schema enum과 같다. AgentEventType만 AgentEventRequest.event의 inline enum. */
	@Test
	void enumValuesMatchOpenApiContract() throws IOException {
		Map<?, ?> schemas;
		try (var input = Files.newInputStream(Path.of("contracts/openapi.yaml"))) {
			Map<?, ?> root = assertInstanceOf(Map.class, new Yaml().load(input));
			schemas = (Map<?, ?>) ((Map<?, ?>) root.get("components")).get("schemas");
		}
		List<Class<? extends Enum<?>>> types = List.of(ActionStatus.class, ActionType.class, AuthorRole.class,
				CaseStatus.class, Category.class, ConfirmationStatus.class, ContextDecision.class, ErrorCode.class,
				FilterResult.class, HandlingRoute.class, KickDecision.class, KickReviewStatus.class, MatchingTag.class,
				ModerationReasonCode.class, Observation.class, ReviewKind.class, ReviewStatus.class, Role.class,
				Verdict.class, Visibility.class);
		for (Class<? extends Enum<?>> type : types) {
			Map<?, ?> schema = assertInstanceOf(Map.class, schemas.get(type.getSimpleName()), type.getSimpleName());
			assertValues(type, (List<?>) schema.get("enum"));
		}
		Map<?, ?> event = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) schemas.get("AgentEventRequest"))
				.get("properties")).get("event");
		assertValues(AgentEventType.class, (List<?>) event.get("enum"));
	}

	@Test
	void violationFindingRequiresCategoriesAndPolicies() {
		assertThrows(InvalidContractException.class, () -> finding(
				Verdict.VIOLATION, List.of(), List.of("POLICY-2"), List.of()));
		assertThrows(InvalidContractException.class, () -> finding(
				Verdict.VIOLATION, List.of(Category.HARASSMENT), List.of(), List.of()));

		MessageFinding finding = finding(Verdict.VIOLATION, List.of(Category.HARASSMENT), List.of("POLICY-2"), List.of());

		assertEquals(List.of(Category.HARASSMENT), finding.categories());
	}

	@Test
	void needsReviewFindingRequiresMissingFacts() {
		assertThrows(InvalidContractException.class, () -> finding(
				Verdict.NEEDS_REVIEW, List.of(), List.of(), List.of()));

		MessageFinding finding = finding(Verdict.NEEDS_REVIEW, List.of(), List.of(), List.of("공식 링크 여부 미확인"));

		assertEquals(List.of("공식 링크 여부 미확인"), finding.missingFacts());
	}

	@Test
	void noViolationFindingCarriesNoCategories() {
		assertDoesNotThrow(() -> finding(Verdict.NO_VIOLATION, List.of(), List.of(), List.of()));
		assertThrows(InvalidContractException.class, () -> finding(
				Verdict.NO_VIOLATION, List.of(Category.FLOOD), List.of(), List.of()));
	}

	@Test
	void judgmentIsAgentOutputForInvestigationRoutesOnly() {
		UUID id = UUID.randomUUID();
		MessageFinding finding = finding(Verdict.NO_VIOLATION, List.of(), List.of(), List.of());

		assertDoesNotThrow(() -> judgment(HandlingRoute.CONTEXT_REVIEW, List.of(finding)));
		assertDoesNotThrow(() -> judgment(HandlingRoute.PII_INVESTIGATION, List.of(finding)));
		assertThrows(InvalidContractException.class, () -> judgment(HandlingRoute.RULE_CONFIRMED, List.of(finding)));
		assertThrows(InvalidContractException.class, () -> judgment(HandlingRoute.CONTEXT_REVIEW, List.of()));
		// TARGET 메시지마다 결과는 하나
		assertThrows(InvalidContractException.class,
				() -> judgment(HandlingRoute.CONTEXT_REVIEW, List.of(finding, finding)));
		assertThrows(InvalidContractException.class, () -> new JudgmentRequest(
				id, id, HandlingRoute.CONTEXT_REVIEW, id, 0, "v3.1", List.of(finding)));
		assertThrows(InvalidContractException.class, () -> new JudgmentRequest(
				id, id, HandlingRoute.CONTEXT_REVIEW, id, 1, " ", List.of(finding)));
		assertThrows(InvalidContractException.class, () -> new MessageFinding(
				id, Verdict.NO_VIOLATION, List.of(), List.of(), "  ", List.of()));
	}

	@Test
	void chatTextMustHaveOneToFiveHundredCharacters() {
		assertTextBounds(text -> new SendMessageRequest(UUID.randomUUID(), text));
	}

	@Test
	void kickDecisionReasonMustHaveOneToFiveHundredCharacters() {
		assertTextBounds(reason -> new KickDecisionRequest(
				UUID.randomUUID(), 1, KickDecision.APPROVE, reason));
	}

	@Test
	void requestsRequireTheirIdentifiers() {
		assertThrows(InvalidContractException.class, () -> new SendMessageRequest(null, "안녕"));
		assertThrows(InvalidContractException.class,
				() -> new KickDecisionRequest(UUID.randomUUID(), 1, null, "근거"));
		assertThrows(InvalidContractException.class,
				() -> new KickDecisionRequest(null, 1, KickDecision.REJECT, "근거"));
	}

	@Test
	void requiredFieldsRejectNullWithInvalidContractException() {
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();

		assertThrows(InvalidContractException.class, () -> new LoginRequest(null, "pw"));
		assertThrows(InvalidContractException.class, () -> new LoginRequest("viewer1", null));
		assertThrows(InvalidContractException.class,
				() -> new AgentEventRequest(null, Map.of()));
		assertThrows(InvalidContractException.class,
				() -> new AgentEventRequest(AgentEventType.TOOL_END, null));
		assertThrows(InvalidContractException.class, () -> new MessageFinding(
				id, Verdict.NO_VIOLATION, null, List.of(), "근거", List.of()));
		assertThrows(InvalidContractException.class, () -> new MessageFinding(
				id, Verdict.NO_VIOLATION, List.of(), null, "근거", List.of()));
		assertThrows(InvalidContractException.class, () -> new Me(null, "닉네임", Role.VIEWER, List.of()));
		assertThrows(InvalidContractException.class, () -> new Me(id, "닉네임", Role.VIEWER, null));
		assertThrows(InvalidContractException.class,
				() -> new Message(id, id, id, "U1", "본문", null, 1, now, false, null));
		// 방송 도입 전 메시지는 liveId가 없다(A-17)
		assertNull(new Message(id, null, id, "U1", "본문", Visibility.PUBLIC, 1, now, false, null).liveId());
		assertThrows(InvalidContractException.class,
				() -> new CaseSummary(id, id, id, "U1", FilterResult.PASS, HandlingRoute.PASS, null,
						ConfirmationStatus.PENDING, null, null, 1, now));
		assertThrows(InvalidContractException.class,
				() -> new CaseSummary(id, id, id, "U1", FilterResult.PASS, HandlingRoute.PASS, CaseStatus.ANALYZING,
						null, null, null, 1, now));
		assertThrows(InvalidContractException.class,
				() -> new ChatEvent(id, id, "U1", "본문", Visibility.PUBLIC, 1, null));
		assertThrows(InvalidContractException.class,
				() -> new ChatEvent(id, null, "U1", "본문", Visibility.PUBLIC, 1, now));
		assertThrows(InvalidContractException.class, () -> new UserKickedEvent(null));
		assertThrows(InvalidContractException.class,
				() -> new KickResult(id, KickReviewStatus.EXECUTED, now, null, false));
		assertThrows(InvalidContractException.class, () -> new MessagePage(null, null));
		assertThrows(InvalidContractException.class, () -> new ContextData(null, false));
	}

	@Test
	void loginRequestDoesNotExposePasswordInToString() {
		String text = new LoginRequest("viewer1", "secret-pw").toString();

		assertTrue(text.contains("viewer1"));
		assertFalse(text.contains("secret-pw"));
	}

	@Test
	void agentEventPayloadAcceptsNullValuesAndIsImmutable() {
		Map<String, Object> payload = new HashMap<>();
		payload.put("tool", "get_chat_context");
		payload.put("status", null);

		AgentEventRequest request = new AgentEventRequest(AgentEventType.TOOL_END, payload);
		payload.put("later", "ignored");

		assertEquals(2, request.payload().size());
		assertThrows(UnsupportedOperationException.class, () -> request.payload().put("x", "y"));
	}

	@Test
	void contextDataAllowsAtMostTwentyMessages() {
		assertEquals(20, new ContextData(maskedMessages(20), false).messages().size());
		assertThrows(InvalidContractException.class, () -> new ContextData(maskedMessages(21), true));
	}

	@Test
	void timeoutHistoryHasNoWindowFieldAndRejectsNegativeCount() {
		// TIMEOUT 이력 범위는 같은 방송 전체(TBD-03)라 응답에 windowHours가 없다
		assertEquals(2, new UserHistory(2, List.of()).violationCount());
		assertThrows(InvalidContractException.class, () -> new UserHistory(-1, List.of()));
		assertThrows(InvalidContractException.class, () -> new UserHistory(0, null));
	}

	@Test
	void nullableFieldsAcceptNullButRequiredFieldsDoNot() {
		assertNull(new JudgmentResult(ConfirmationStatus.CONFIRMED_VIOLATION, null, null).reviewId());
		assertEquals(ReviewKind.CONTEXT,
				new JudgmentResult(ConfirmationStatus.PENDING, UUID.randomUUID(), ReviewKind.CONTEXT).reviewKind());
		assertNull(new MessagePage(List.of(), null).nextCursor());
		assertThrows(InvalidContractException.class, () -> new JudgmentResult(null, null, null));
		// 검토 ID와 종류는 함께 있거나 함께 없다 (A-9, A-16)
		assertThrows(InvalidContractException.class,
				() -> new JudgmentResult(ConfirmationStatus.PENDING, UUID.randomUUID(), null));
		assertThrows(InvalidContractException.class, () -> new ErrorResponse.ErrorBody(
				null, "오류", false, null, null));
		assertThrows(InvalidContractException.class, () -> new MaskedMessage(
				null, "U1", "본문", Instant.now()));
	}

	@Test
	void apiResponseWrapsDataWithTraceId() {
		ApiResponse<ContextData> response = new ApiResponse<>(new ContextData(List.of(), false), "trace-1");

		assertEquals("trace-1", response.traceId());
		assertThrows(InvalidContractException.class, () -> new ApiResponse<>(null, "trace-1"));
	}

	private static void assertValues(Class<? extends Enum<?>> type, List<?> contract) {
		assertNotNull(contract, type.getSimpleName() + " has no enum in openapi.yaml");
		Set<String> expected = contract.stream().map(Object::toString).collect(Collectors.toSet());
		Set<String> actual = Arrays.stream(type.getEnumConstants())
				.map(Enum::name)
				.collect(Collectors.toSet());

		assertEquals(expected, actual, type.getSimpleName());
		assertEquals(contract.size(), type.getEnumConstants().length, type.getSimpleName());
	}

	private static void assertTextBounds(Consumer<String> factory) {
		assertThrows(InvalidContractException.class, () -> factory.accept(null));
		assertThrows(InvalidContractException.class, () -> factory.accept(""));
		assertThrows(InvalidContractException.class, () -> factory.accept("   "));
		assertThrows(InvalidContractException.class, () -> factory.accept(" \n\t "));
		assertDoesNotThrow(() -> factory.accept("a"));
		assertDoesNotThrow(() -> factory.accept("a".repeat(500)));
		assertThrows(InvalidContractException.class, () -> factory.accept("a".repeat(501)));
		// maxLength는 UTF-16 단위가 아니라 코드포인트 수로 센다
		assertDoesNotThrow(() -> factory.accept(EMOJI.repeat(500)));
		assertThrows(InvalidContractException.class, () -> factory.accept(EMOJI.repeat(501)));
	}

	private static MessageFinding finding(
			Verdict verdict, List<Category> categories, List<String> policyIds, List<String> missingFacts) {
		return new MessageFinding(UUID.randomUUID(), verdict, categories, policyIds, "근거 설명", missingFacts);
	}

	private static JudgmentRequest judgment(HandlingRoute route, List<MessageFinding> findings) {
		UUID id = UUID.randomUUID();
		return new JudgmentRequest(id, id, route, id, 1, "v3.1", findings);
	}

	private static List<MaskedMessage> maskedMessages(int count) {
		return IntStream.range(0, count)
				.mapToObj(i -> new MaskedMessage(UUID.randomUUID(), "U" + i, "메시지 " + i, Instant.now()))
				.toList();
	}
}
