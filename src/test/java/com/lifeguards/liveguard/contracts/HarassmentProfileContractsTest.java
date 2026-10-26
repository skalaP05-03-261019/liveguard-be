package com.lifeguards.liveguard.contracts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.yaml.snakeyaml.Yaml;

import com.lifeguards.liveguard.contracts.dto.HarassmentAssessment;
import com.lifeguards.liveguard.contracts.dto.HarassmentAssessment.Clause;
import com.lifeguards.liveguard.contracts.dto.HarassmentAssessment.ExceptionStatus;
import com.lifeguards.liveguard.contracts.dto.HarassmentAssessment.RequirementStatus;
import com.lifeguards.liveguard.contracts.dto.HarassmentProfile;
import com.lifeguards.liveguard.contracts.dto.request.HarassmentSettingsRequest;
import com.lifeguards.liveguard.contracts.dto.request.JudgmentRequest;
import com.lifeguards.liveguard.contracts.dto.request.JudgmentRequest.MessageFinding;
import com.lifeguards.liveguard.contracts.dto.response.HarassmentSettings;
import com.lifeguards.liveguard.contracts.dto.response.ContextData;
import com.lifeguards.liveguard.contracts.dto.response.KickReview;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.HarassmentLevel;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.ReviewKind;
import com.lifeguards.liveguard.contracts.enums.ReviewStatus;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class HarassmentProfileContractsTest {
	private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private final JsonMapper mapper = JsonMapper.builder()
			.enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

	private HarassmentProfile profile(HarassmentLevel level) {
		return new HarassmentProfile(level, "harassment-profile-v1", 1);
	}

	private JudgmentRequest judgment(HarassmentLevel level, MessageFinding finding) {
		return new JudgmentRequest(ID, ID, HandlingRoute.CONTEXT_REVIEW, ID, 1, "v3.3",
				List.of(finding), 2, profile(level));
	}

	private Clause violation(String policyId) {
		return new Clause(policyId, Verdict.VIOLATION, RequirementStatus.MET, RequirementStatus.MET,
				List.of(ID), List.of(ID), ExceptionStatus.DOES_NOT_APPLY, List.of());
	}

	private Clause normal(String policyId) {
		return new Clause(policyId, Verdict.NO_VIOLATION, RequirementStatus.NOT_MET, RequirementStatus.NOT_MET,
				List.of(), List.of(), ExceptionStatus.DOES_NOT_APPLY, List.of());
	}

	private HarassmentAssessment assessment(List<Clause> clauses, ExceptionStatus status, List<UUID> evidence) {
		return new HarassmentAssessment(clauses.stream().map(clause -> new Clause(clause.policyId(),
				clause.verdict(), clause.targetStatus(), clause.conductStatus(), clause.targetEvidenceMessageIds(),
				clause.conductEvidenceMessageIds(), status, evidence)).toList());
	}

	private MessageFinding finding(Verdict verdict, List<Category> categories, List<String> policies,
			HarassmentAssessment assessment) {
		return new MessageFinding(ID, verdict, categories, policies, "masked evidence",
				verdict == Verdict.NEEDS_REVIEW ? List.of("target unknown") : List.of(), assessment);
	}

	@ParameterizedTest
	@EnumSource(HarassmentLevel.class)
	void settingsRoundTripAndPositiveRevision(HarassmentLevel level) {
		var request = new HarassmentSettingsRequest(ID, 1, level);
		assertEquals(request, mapper.readValue(mapper.writeValueAsString(request), HarassmentSettingsRequest.class));
		var settings = new HarassmentSettings(ID, "v3.3", "harassment-profile-v1", level, 1, Instant.EPOCH);
		assertEquals(settings, mapper.readValue(mapper.writeValueAsString(settings), HarassmentSettings.class));
		assertThrows(InvalidContractException.class, () -> new HarassmentSettingsRequest(ID, 0, level));
		assertThrows(InvalidContractException.class, () -> new HarassmentProfile(level, "harassment-profile-v1", 0));
		assertThrows(InvalidContractException.class, () -> new HarassmentProfile(level, "unknown", 1));
	}

	@Test
	void settingsRejectMissingUnknownAndExtraFields() {
		for (String json : List.of(
				"{\"clientRequestId\":\"" + ID + "\",\"harassmentLevel\":\"STANDARD\"}",
				"{\"clientRequestId\":\"" + ID + "\",\"expectedSettingsRevision\":1,\"harassmentLevel\":\"CUSTOM\"}",
				"{\"clientRequestId\":\"" + ID + "\",\"expectedSettingsRevision\":1,\"harassmentLevel\":\"STANDARD\",\"policyIds\":[]}")) {
			assertThrows(RuntimeException.class, () -> mapper.readValue(json, HarassmentSettingsRequest.class));
		}
	}

	@Test
	void legacyJudgmentRetainsItsJsonShape() {
		var old = new JudgmentRequest(ID, ID, HandlingRoute.CONTEXT_REVIEW, ID, 1, "v3.2",
				List.of(new MessageFinding(ID, Verdict.NO_VIOLATION, List.of(), List.of(), "normal", List.of())));
		var json = mapper.valueToTree(old);
		assertFalse(json.has("evidenceSchemaVersion"));
		assertFalse(json.has("harassmentProfile"));
		assertFalse(json.get("messageFindings").get(0).has("harassmentAssessment"));
		assertEquals(old, mapper.readValue(mapper.writeValueAsString(old), JudgmentRequest.class));
	}

	@Test
	void schemaPolicyAndProfileMustBeProvidedTogether() {
		for (Integer schema : new Integer[]{null, 1, 2, 3}) {
			if (!Integer.valueOf(2).equals(schema)) {
				assertThrows(InvalidContractException.class,
						() -> HarassmentProfile.validateEvidence(schema, "v3.3", profile(HarassmentLevel.STANDARD)));
			}

		}
		assertThrows(InvalidContractException.class, () -> HarassmentProfile.validateEvidence(2, "v3.3", null));
		assertThrows(InvalidContractException.class,
				() -> HarassmentProfile.validateEvidence(2, "v3.2", profile(HarassmentLevel.STANDARD)));
		assertDoesNotThrow(() -> HarassmentProfile.validateEvidence(null, "v3.2", null));
		assertDoesNotThrow(() -> HarassmentProfile.validateEvidence(1, "v3.1", null));
	}

	@Test
	void newJudgmentRejectsMissingOrNullProfileReference() {
		for (String suffix : List.of("", ",\"evidenceSchemaVersion\":2",
				",\"evidenceSchemaVersion\":2,\"harassmentProfile\":null")) {
			String body = """
					{"caseId":"%s","liveId":"%s","handlingRoute":"CONTEXT_REVIEW","snapshotId":"%s",
					"evidenceVersion":1,"policyVersion":"v3.3","messageFindings":[
					{"messageId":"%s","verdict":"NO_VIOLATION","categories":[],"policyIds":[],
					"reason":"normal","missingFacts":[]} ]%s}
					""".formatted(ID, ID, ID, ID, suffix);
			assertThrows(RuntimeException.class, () -> mapper.readValue(body, JudgmentRequest.class));
		}
	}

	@Test
	void contextRetainsSnapshotIdentityAndFrozenProfile() {
		var context = new ContextData(List.of(), false, ID, ID, 1, "v3.3", 2, profile(HarassmentLevel.RELAXED));
		assertEquals(context, mapper.readValue(mapper.writeValueAsString(context), ContextData.class));
		assertEquals("RELAXED", mapper.valueToTree(context).get("harassmentProfile").get("level").asString());
		assertThrows(InvalidContractException.class, () -> new ContextData(List.of(), false,
				ID, null, 1, "v3.3", 2, profile(HarassmentLevel.RELAXED)));
		assertFalse(mapper.valueToTree(new ContextData(List.of(), false)).has("harassmentProfile"));
	}

	@ParameterizedTest
	@EnumSource(HarassmentLevel.class)
	void structuredIndividualAttackIsValidAcrossProfiles(HarassmentLevel level) {
		var clauses = level == HarassmentLevel.RELAXED ? List.of(violation("CP-3.2"))
				: List.of(violation("CP-3.2"), normal("CP-3.3"));
		var assessment = assessment(clauses,
				level == HarassmentLevel.STRICT ? ExceptionStatus.NOT_APPLICABLE : ExceptionStatus.DOES_NOT_APPLY,
				List.of());
		var judgment = judgment(level, finding(Verdict.VIOLATION, List.of(Category.HARASSMENT),
				List.of("CP-3.2"), assessment));
		assertEquals(judgment, mapper.readValue(mapper.writeValueAsString(judgment), JudgmentRequest.class));
	}

	@Test
	void strictCannotApplyExceptionsAndStillRequiresAttackEvidence() {
		var assessment = assessment(List.of(normal("CP-3.2"), normal("CP-3.3")),
				ExceptionStatus.APPLIES, List.of(ID));
		assertThrows(InvalidContractException.class,
				() -> judgment(HarassmentLevel.STRICT, finding(Verdict.NO_VIOLATION, List.of(), List.of(), assessment)));
		assertThrows(InvalidContractException.class, () -> new Clause("CP-3.2", Verdict.VIOLATION,
				RequirementStatus.UNKNOWN, RequirementStatus.MET, List.of(), List.of(ID),
				ExceptionStatus.DOES_NOT_APPLY, List.of()));
		assertThrows(InvalidContractException.class, () -> new Clause("CP-3.2", Verdict.VIOLATION,
				RequirementStatus.MET, RequirementStatus.MET, List.of(), List.of(ID),
				ExceptionStatus.DOES_NOT_APPLY, List.of()));
	}

	@Test
	void strictCanReturnNormalWithoutException() {
		var assessment = assessment(List.of(normal("CP-3.2"), normal("CP-3.3")),
				ExceptionStatus.NOT_APPLICABLE, List.of());
		assertDoesNotThrow(() -> judgment(HarassmentLevel.STRICT,
				finding(Verdict.NO_VIOLATION, List.of(), List.of(), assessment)));
	}

	@Test
	void relaxedCannotEvaluateOrSubmitGroupClauseEvenAlongsidePersonalAttack() {
		var personal = assessment(List.of(violation("CP-3.2")),
				ExceptionStatus.DOES_NOT_APPLY, List.of());
		assertThrows(InvalidContractException.class, () -> judgment(HarassmentLevel.RELAXED,
				finding(Verdict.VIOLATION, List.of(Category.HARASSMENT), List.of("CP-3.2", "CP-3.3"), personal)));
		var both = assessment(List.of(violation("CP-3.2"), violation("CP-3.3")),
				ExceptionStatus.DOES_NOT_APPLY, List.of());
		assertThrows(InvalidContractException.class, () -> judgment(HarassmentLevel.RELAXED,
				finding(Verdict.VIOLATION, List.of(Category.HARASSMENT), List.of("CP-3.2", "CP-3.3"), both)));
	}

	@Test
	void assessmentRequiresEveryApplicableClauseExactlyOnce() {
		var assessment = assessment(List.of(normal("CP-3.2")),
				ExceptionStatus.DOES_NOT_APPLY, List.of());
		assertThrows(InvalidContractException.class, () -> judgment(HarassmentLevel.STANDARD,
				finding(Verdict.NO_VIOLATION, List.of(), List.of(), assessment)));
		assertThrows(InvalidContractException.class, () -> assessment(
				List.of(normal("CP-3.2"), normal("CP-3.2")), ExceptionStatus.DOES_NOT_APPLY, List.of()));
	}

	@Test
	void harassmentRequiresAssessmentButOtherCategoriesAndTheirExceptionsDoNot() {
		assertThrows(InvalidContractException.class, () -> judgment(HarassmentLevel.STANDARD,
				finding(Verdict.VIOLATION, List.of(Category.HARASSMENT), List.of("CP-3.2"), null)));
		for (Category category : List.of(Category.PROFANITY, Category.FLOOD, Category.PHISHING, Category.PII_EXPOSURE)) {
			assertDoesNotThrow(() -> judgment(HarassmentLevel.STRICT,
					finding(Verdict.VIOLATION, List.of(category), List.of("CP-5.1"), null)));
		}
		assertDoesNotThrow(() -> judgment(HarassmentLevel.STRICT,
				finding(Verdict.NO_VIOLATION, List.of(), List.of("CP-2.2"), null)));
	}

	@Test
	void unknownClauseRequiresNeedsReviewAndMissingFacts() {
		var unknown = new Clause("CP-3.2", Verdict.NEEDS_REVIEW, RequirementStatus.UNKNOWN,
				RequirementStatus.UNKNOWN, List.of(), List.of(), ExceptionStatus.UNKNOWN, List.of());
		var assessment = new HarassmentAssessment(List.of(unknown));
		assertDoesNotThrow(() -> judgment(HarassmentLevel.RELAXED,
				finding(Verdict.NEEDS_REVIEW, List.of(), List.of(), assessment)));
		assertThrows(InvalidContractException.class, () -> judgment(HarassmentLevel.RELAXED,
				finding(Verdict.NO_VIOLATION, List.of(), List.of(), assessment)));
	}

	@Test
	void violationCannotUseAppliedOrUnknownExceptionsOrHideStructuredPolicies() {
		for (ExceptionStatus status : List.of(ExceptionStatus.APPLIES, ExceptionStatus.UNKNOWN)) {
			assertThrows(InvalidContractException.class,
					() -> assessment(List.of(violation("CP-3.2")), status, List.of(ID)));
		}
		var assessment = assessment(List.of(violation("CP-3.2")), ExceptionStatus.DOES_NOT_APPLY, List.of());
		assertThrows(InvalidContractException.class, () -> judgment(HarassmentLevel.RELAXED,
				finding(Verdict.NO_VIOLATION, List.of(), List.of(), assessment)));
	}

	@Test
	void legacyFindingsCannotCarryNewAssessment() {
		var assessment = assessment(List.of(normal("CP-3.2")), ExceptionStatus.DOES_NOT_APPLY, List.of());
		assertThrows(InvalidContractException.class, () -> new JudgmentRequest(ID, ID,
				HandlingRoute.CONTEXT_REVIEW, ID, 1, "v3.2",
				List.of(finding(Verdict.NO_VIOLATION, List.of(), List.of(), assessment))));
	}

	@Test
	void kickProfileBelongsToDisplayedEvidenceNotWholeBasis() {
		var second = UUID.fromString("22222222-2222-4222-8222-222222222222");
		var last = UUID.fromString("33333333-3333-4333-8333-333333333333");
		var basis = List.of(ID, second, last);
		assertDoesNotThrow(() -> new KickReview(ID, ReviewKind.KICK, ID, "U1", basis, ID,
				ReviewStatus.PENDING, "summary", null, 1, Instant.EPOCH, last, "v3.3", 2,
				profile(HarassmentLevel.STRICT)));
		assertThrows(InvalidContractException.class, () -> new KickReview(ID, ReviewKind.KICK, ID, "U1",
				basis, ID, ReviewStatus.PENDING, "summary", null, 1, Instant.EPOCH,
				ID, "v3.3", 2, profile(HarassmentLevel.STRICT)));
	}

	@Test
	void groupQuotationExceptionDoesNotExemptIndependentPersonalAttack() {
		var quotedGroup = new Clause("CP-3.3", Verdict.NO_VIOLATION, RequirementStatus.MET,
				RequirementStatus.MET, List.of(ID), List.of(ID), ExceptionStatus.APPLIES, List.of(ID));
		var assessment = new HarassmentAssessment(List.of(violation("CP-3.2"), quotedGroup));
		assertDoesNotThrow(() -> judgment(HarassmentLevel.STANDARD,
				finding(Verdict.VIOLATION, List.of(Category.HARASSMENT), List.of("CP-3.2"), assessment)));
	}

	@Test
	void clauseVerdictMustMatchRequirementsRatherThanFreeText() {
		assertThrows(InvalidContractException.class, () -> new Clause("CP-3.2", Verdict.NO_VIOLATION,
				RequirementStatus.MET, RequirementStatus.MET, List.of(ID), List.of(ID),
				ExceptionStatus.DOES_NOT_APPLY, List.of()));
		assertThrows(InvalidContractException.class, () -> new Clause("CP-3.2", Verdict.NO_VIOLATION,
				RequirementStatus.UNKNOWN, RequirementStatus.UNKNOWN, List.of(), List.of(),
				ExceptionStatus.UNKNOWN, List.of()));
		assertDoesNotThrow(() -> new Clause("CP-3.2", Verdict.NO_VIOLATION,
				RequirementStatus.NOT_MET, RequirementStatus.UNKNOWN, List.of(), List.of(),
				ExceptionStatus.UNKNOWN, List.of()));
	}

	@Test
	void plannedSettingsOperationsUseSessionAndExistingErrors() throws Exception {
		try (var input = Files.newInputStream(Path.of("contracts/openapi.yaml"))) {
			Map<?, ?> root = assertInstanceOf(Map.class, new Yaml().load(input));
			assertEquals(List.of(Map.of("sessionCookie", List.of())), root.get("security"));
			Map<?, ?> paths = assertInstanceOf(Map.class, root.get("paths"));
			Map<?, ?> path = assertInstanceOf(Map.class, paths.get("/api/lives/{liveId}/harassment-settings"));
			for (String verb : List.of("get", "put")) {
				Map<?, ?> operation = assertInstanceOf(Map.class, path.get(verb));
				assertEquals("planned", operation.get("x-implementation-status"));
				assertFalse(operation.containsKey("security"), "inherits browser session security");
			}
			Map<?, ?> put = assertInstanceOf(Map.class, path.get("put"));
			assertTrue(assertInstanceOf(Map.class, put.get("responses")).containsKey("409"));
		}
	}
}
