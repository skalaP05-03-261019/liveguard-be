package com.lifeguards.liveguard.internal;

import java.util.UUID;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "liveguard.internal.service-token=test-business-contract-token")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BusinessToolControllerTest {
	private static final String ID = "11111111-1111-4111-8111-111111111111";
	private static final String TOKEN = "test-business-contract-token";

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest
	@ValueSource(strings = {"/internal/cases/" + ID + "/security-facts",
			"/internal/actions/" + ID})
	void readsRequireServiceTokenAndReturnExplicitStub(String path) throws Exception {
		mockMvc.perform(get(path)).andExpect(status().isForbidden());
		mockMvc.perform(get(path).header("X-Service-Token", "wrong")).andExpect(status().isForbidden());
		mockMvc.perform(internal(get(path)).header("X-Trace-Id", ID))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"))
				.andExpect(jsonPath("$.error.retryable").value(false))
				.andExpect(jsonPath("$.traceId").value(ID))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/internal/cases/" + ID, "/internal/cases/" + ID + "/user-history",
			"/internal/cases/" + ID + "/kick-eligibility",
			"/internal/kick-reviews/" + ID + "/basis"})
	void implementedReadsRequireServiceTokenAndReturnNotFound(String path) throws Exception {
		mockMvc.perform(get(path)).andExpect(status().isForbidden());
		mockMvc.perform(get(path).header("X-Service-Token", "wrong")).andExpect(status().isForbidden());
		mockMvc.perform(internal(get(path)).header("X-Trace-Id", ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.traceId").value(ID))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void removedLegacyHistoryAliasIsNotFound() throws Exception {
		// A-12: /user-history의 옛 별칭은 삭제했다
		mockMvc.perform(internal(get("/internal/cases/{id}/history", ID)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void removedLegacyKickExecuteIsNotFound() throws Exception {
		mockMvc.perform(internal(post("/internal/kick-reviews/{id}/execute", ID)).header("Idempotency-Key", "kick:" + ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "invalid-trace", "11111111-1111-4111-8111-111111111ABC"})
	void traceNormalizationMatchesHeaderAndBody(String trace) throws Exception {
		var response = mockMvc.perform(internal(get("/internal/cases/{id}", ID))
						.header("X-Trace-Id", trace))
				.andExpect(status().isNotFound()).andReturn().getResponse();
		String bodyTrace = JsonPath.read(response.getContentAsString(), "$.traceId");
		UUID.fromString(bodyTrace);
		assertEquals(response.getHeader("X-Trace-Id"), bodyTrace);
	}

	@ParameterizedTest
	@ValueSource(strings = {"HIDE", "TIMEOUT", "RELEASE_HELD", "KICK"})
	void validatedActionContractRemainsNonExecutable(String type) throws Exception {
		String extras = switch (type) {
			case "TIMEOUT" -> ",\"durationSeconds\":600";
			case "KICK" -> kickBasis();
			case "RELEASE_HELD" -> ",\"decisionId\":\"" + ID + "\"";
			default -> "";
		};
		mockMvc.perform(internal(post("/internal/actions/{id}/execute", ID))
						.contentType(MediaType.APPLICATION_JSON).content(command(type, extras)))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.error.retryable").value(false));
	}

	@Test
	void executeWithoutServiceTokenIsRejected() throws Exception {
		mockMvc.perform(post("/internal/actions/{id}/execute", ID)
						.contentType(MediaType.APPLICATION_JSON).content(command("HIDE", "")))
				.andExpect(status().isForbidden());
	}

	@Test
	void reconcileRemainsLegacyNonExecutableContract() throws Exception {
		mockMvc.perform(internal(post("/internal/cases/{id}/reviews/{decisionId}/reconcile", ID, ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"caseId\":\"" + ID + "\",\"decisionId\":\"" + ID + "\"}"))
				.andExpect(status().isNotImplemented());
	}

	@Test
	void obsoleteViewsAndInvalidQueryRangesAreRejected() throws Exception {
		mockMvc.perform(internal(get("/internal/cases/{id}/context", ID)).param("view", "moderationCase"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(internal(get("/internal/cases/{id}/context", ID)).param("before", "11"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(internal(get("/internal/cases/{id}/user-history", ID)).param("days", "0"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(internal(get("/internal/cases/{id}/user-history", ID)).param("days", "31"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(internal(get("/internal/cases/{id}/kick-eligibility", ID)).param("days", "30"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void invalidActionReferencesAndLegacyFieldsAreRejected() throws Exception {
		mockMvc.perform(internal(post("/internal/actions/{id}/execute", UUID.randomUUID()))
						.contentType(MediaType.APPLICATION_JSON).content(command("HIDE", "")))
				.andExpect(status().isBadRequest());
		for (String body : new String[] {command("TIMEOUT", ""), command("KICK", ""),
				command("KICK", kickBasis().replace("\"validationRef\"", "\"obsoleteValidation\"")),
				command("KICK", kickBasis()).replace("\"liveId\":\"" + ID + "\",", ""),
				command("KICK", kickBasis() + ",\"durationSeconds\":86400"),
				command("HIDE", ",\"violationIds\":[]")}) {
			mockMvc.perform(internal(post("/internal/actions/{id}/execute", ID))
							.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void openApiDocumentsTypedContractsAndAuthentication() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.securitySchemes.serviceToken.name").value("X-Service-Token"))
				.andExpect(jsonPath("$.paths['/internal/cases/{caseId}/context'].get").exists())
				.andExpect(jsonPath("$.paths['/internal/cases/{caseId}/role'].get").exists())
				.andExpect(jsonPath("$.paths['/internal/cases/{caseId}/user-history'].get").exists())
				.andExpect(jsonPath("$.paths['/internal/cases/{caseId}/history']").doesNotExist())
				.andExpect(jsonPath("$.paths['/internal/kick-reviews/{reviewId}/basis'].get").exists())
				.andExpect(jsonPath("$.paths['/internal/actions/{actionId}/execute'].post.security[0].serviceToken").exists())
				.andExpect(jsonPath("$.components.schemas.BusinessActionCommand.properties.basisCaseIds").exists())
				.andExpect(jsonPath("$.components.schemas.BusinessActionCommand.properties.validationRef").exists())
				.andExpect(jsonPath("$.components.schemas.ActionExecutionResult.properties.applied").exists())
				.andExpect(jsonPath("$.components.schemas.KickReviewBasis").exists())
				.andExpect(jsonPath("$.components.schemas.SecurityFacts").exists())
				.andExpect(jsonPath("$.components.schemas.UserHistory").exists());
	}

	@Test
	void generatedCaseSchemaIncludesCanonicalAndDerivedTargetFields() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.InternalCase.properties.targets").exists())
				.andExpect(jsonPath("$.components.schemas.InternalCase.properties.targetMessageIds").exists())
				.andExpect(jsonPath("$.components.schemas.InternalCase.properties.targetVersions").exists());
	}

	private MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
		return request.header("X-Service-Token", TOKEN);
	}

	private String kickBasis() {
		return """
				,"decisionId":"%1$s","reviewId":"%1$s","basisSnapshotId":"%1$s","validationRef":"%1$s",
				"basisCaseIds":["%1$s","%2$s","%3$s"]
				""".formatted(ID, UUID.randomUUID(), UUID.randomUUID());
	}

	private String command(String type, String extras) {
		return """
				{"actionId":"%1$s","planId":"%1$s","caseId":"%1$s","liveId":"%1$s",
				"type":"%2$s","targetId":"%1$s","expectedEvidenceVersion":1,"expectedTargetVersion":1,
				"policyVersion":"test-policy-only","idempotencyKey":"test-command"%3$s}
				""".formatted(ID, type, extras);
	}
}
