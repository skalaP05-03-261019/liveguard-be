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
import com.lifeguards.liveguard.contracts.dto.request.KickDecisionRequest;
import com.lifeguards.liveguard.contracts.dto.request.LoginRequest;
import com.lifeguards.liveguard.contracts.dto.request.ReleaseRequest;
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
	void violationJudgmentRequiresCategoryEvidenceAndPolicies() {
		UUID evidence = UUID.randomUUID();

		assertThrows(InvalidContractException.class, () -> judgment(
				Verdict.VIOLATION, null, List.of(evidence), List.of("CP-2.1"), null));
		assertThrows(InvalidContractException.class, () -> judgment(
				Verdict.VIOLATION, Category.HARASSMENT, List.of(), List.of("CP-2.1"), null));
		assertThrows(InvalidContractException.class, () -> judgment(
				Verdict.VIOLATION, Category.HARASSMENT, List.of(evidence), List.of(), null));

		JudgmentRequest request = judgment(
				Verdict.VIOLATION, Category.HARASSMENT, List.of(evidence), List.of("CP-2.1"), null);

		assertEquals(Category.HARASSMENT, request.category());
	}

	@Test
	void needsReviewJudgmentRequiresMissingFacts() {
		assertThrows(InvalidContractException.class, () -> judgment(
				Verdict.NEEDS_REVIEW, null, List.of(), List.of(), null));
		assertThrows(InvalidContractException.class, () -> judgment(
				Verdict.NEEDS_REVIEW, null, List.of(), List.of(), List.of()));

		JudgmentRequest request = judgment(
				Verdict.NEEDS_REVIEW, null, List.of(), List.of(), List.of("공식 링크 여부 미확인"));

		assertEquals(List.of("공식 링크 여부 미확인"), request.missingFacts());
	}

	@Test
	void noViolationJudgmentNeedsNoExtraFields() {
		JudgmentRequest request = judgment(Verdict.NO_VIOLATION, null, List.of(), List.of(), null);

		assertNull(request.category());
		assertEquals(List.of(), request.policySnapshots());
		assertEquals(List.of(), request.missingFacts());
		assertDoesNotThrow(() -> judgment(
				Verdict.NO_VIOLATION, Category.FLOOD, List.of(), List.of(), null));
	}

	@Test
	void judgmentRequiresVerdictAndNonBlankReason() {
		assertThrows(InvalidContractException.class, () -> judgment(null, null, List.of(), List.of(), null));
		assertThrows(InvalidContractException.class, () -> new JudgmentRequest(
				1, Verdict.NO_VIOLATION, null, List.of(), List.of(), null, "  ", null, "trace"));
		assertThrows(InvalidContractException.class, () -> new JudgmentRequest(
				1, Verdict.NO_VIOLATION, null, List.of(), List.of(), null, "reason", null, null));
	}

	@Test
	void chatTextMustHaveOneToFiveHundredCharacters() {
		assertTextBounds(text -> new SendMessageRequest(UUID.randomUUID(), text));
	}

	@Test
	void releaseReasonMustHaveOneToFiveHundredCharacters() {
		assertTextBounds(reason -> new ReleaseRequest(1, reason));
	}

	@Test
	void kickDecisionReasonMustHaveOneToFiveHundredCharacters() {
		assertTextBounds(reason -> new KickDecisionRequest(
				KickDecision.APPROVE, reason, 1, UUID.randomUUID()));
	}

	@Test
	void requestsRequireTheirIdentifiers() {
		assertThrows(InvalidContractException.class, () -> new SendMessageRequest(null, "안녕"));
		assertThrows(InvalidContractException.class,
				() -> new KickDecisionRequest(null, "근거", 1, UUID.randomUUID()));
		assertThrows(InvalidContractException.class,
				() -> new KickDecisionRequest(KickDecision.REJECT, "근거", 1, null));
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
		assertThrows(InvalidContractException.class, () -> new JudgmentRequest(
				1, Verdict.NO_VIOLATION, null, null, List.of(), null, "근거", null, "trace-1"));
		assertThrows(InvalidContractException.class, () -> new JudgmentRequest(
				1, Verdict.NO_VIOLATION, null, List.of(), null, null, "근거", null, "trace-1"));
		assertThrows(InvalidContractException.class, () -> new Me(null, "닉네임", Role.VIEWER, List.of()));
		assertThrows(InvalidContractException.class, () -> new Me(id, "닉네임", Role.VIEWER, null));
		assertThrows(InvalidContractException.class,
				() -> new Message(id, id, "U1", "본문", null, 1, now, false));
		assertThrows(InvalidContractException.class,
				() -> new CaseSummary(id, id, "U1", FilterResult.PASS, null, null, null, 1, now));
		assertThrows(InvalidContractException.class,
				() -> new ChatEvent(id, "U1", "본문", Visibility.PUBLIC, 1, null));
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
		assertNull(new JudgmentResult(CaseStatus.HIDDEN, 1, null).kickReviewId());
		assertNull(new MessagePage(List.of(), null).nextCursor());
		assertThrows(InvalidContractException.class, () -> new JudgmentResult(null, 1, null));
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

	private static JudgmentRequest judgment(
			Verdict verdict,
			Category category,
			List<UUID> evidenceMessageIds,
			List<String> policyIds,
			List<String> missingFacts) {
		return new JudgmentRequest(
				1, verdict, category, evidenceMessageIds, policyIds, null, "근거 설명", missingFacts, "trace-1");
	}

	private static List<MaskedMessage> maskedMessages(int count) {
		return IntStream.range(0, count)
				.mapToObj(i -> new MaskedMessage(UUID.randomUUID(), "U" + i, "메시지 " + i, Instant.now()))
				.toList();
	}
}
