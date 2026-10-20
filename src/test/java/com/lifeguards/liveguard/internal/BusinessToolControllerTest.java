package com.lifeguards.liveguard.internal;

import java.util.UUID;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BusinessToolControllerTest {
	private static final String ID = "11111111-1111-4111-8111-111111111111";

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest
	@ValueSource(strings = {"moderationCase", "chatContext", "streamInfo", "officialLinks", "securityFacts"})
	void contextViewsReturnExplicitNonRetryableStub(String view) throws Exception {
		mockMvc.perform(get("/internal/cases/{id}/context", ID).param("view", view)
						.header("X-Trace-Id", ID))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"))
				.andExpect(jsonPath("$.error.retryable").value(false))
				.andExpect(jsonPath("$.traceId").value(ID))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/internal/cases/" + ID + "/history",
			"/internal/cases/" + ID + "/kick-eligibility", "/internal/actions/" + ID})
	void remainingReadsReturnStubWithGeneratedTrace(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"))
				.andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern(
						"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"HIDE", "TIMEOUT", "RELEASE_HELD", "KICK"})
	void allAgentActionTypesAreAcceptedButNeverExecuted(String type) throws Exception {
		String extras = switch (type) {
			case "TIMEOUT" -> ",\"durationSeconds\":600";
			case "KICK" -> """
					,"decisionId":"%s","violationIds":["%s","%s","%s"]
					""".formatted(ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
			default -> "";
		};
		mockMvc.perform(post("/internal/actions/{id}/execute", ID)
						.contentType(MediaType.APPLICATION_JSON).content(command(type, extras)))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.error.retryable").value(false));
	}

	@Test
	void reconcileMatchesAgentBody() throws Exception {
		mockMvc.perform(post("/internal/cases/{id}/reviews/{decisionId}/reconcile", ID, ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"caseId\":\"" + ID + "\",\"decisionId\":\"" + ID + "\"}"))
				.andExpect(status().isNotImplemented());
	}

	@Test
	void invalidViewAndPolicyBoundsAreRejected() throws Exception {
		mockMvc.perform(get("/internal/cases/{id}/context", ID).param("view", "unknown"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/internal/cases/{id}/context", ID)
						.param("view", "chatContext").param("before", "11"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/internal/cases/{id}/history", ID).param("days", "0"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/internal/cases/{id}/history", ID).param("days", "31"))
				.andExpect(status().isNotImplemented());
		mockMvc.perform(get("/internal/cases/{id}/kick-eligibility", ID).param("days", "30"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void inconsistentIdsAndInvalidActionPayloadsAreRejected() throws Exception {
		mockMvc.perform(post("/internal/actions/{id}/execute", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON).content(command("HIDE", "")))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/internal/actions/{id}/execute", ID)
						.contentType(MediaType.APPLICATION_JSON).content(command("TIMEOUT", "")))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/internal/actions/{id}/execute", ID)
						.contentType(MediaType.APPLICATION_JSON).content(command("KICK",
								",\"decisionId\":\"" + ID + "\",\"violationIds\":[\"" + ID + "\"]")))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/internal/cases/{id}/reviews/{decisionId}/reconcile", ID, ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"caseId\":\"" + UUID.randomUUID() + "\",\"decisionId\":\"" + ID + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void openApiDocumentsAgentRoutesAndSchemas() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/internal/cases/{caseId}/context'].get").exists())
				.andExpect(jsonPath("$.paths['/internal/cases/{caseId}/history'].get").exists())
				.andExpect(jsonPath("$.paths['/internal/cases/{caseId}/kick-eligibility'].get").exists())
				.andExpect(jsonPath("$.paths['/internal/actions/{actionId}'].get").exists())
				.andExpect(jsonPath("$.paths['/internal/actions/{actionId}/execute'].post").exists())
				.andExpect(jsonPath("$.paths['/internal/cases/{caseId}/reviews/{decisionId}/reconcile'].post").exists())
				.andExpect(jsonPath("$.components.schemas.BusinessActionCommand.properties.violationIds").exists())
				.andExpect(jsonPath("$.components.schemas.InternalCase").exists())
				.andExpect(jsonPath("$.components.schemas.ContextData").exists())
				.andExpect(jsonPath("$.components.schemas.ChannelInfo").exists())
				.andExpect(jsonPath("$.components.schemas.UserHistory").exists());
	}

	private String command(String type, String extras) {
		return """
				{"actionId":"%1$s","planId":"%1$s","caseId":"%1$s","type":"%2$s","targetId":"%1$s",
				"expectedEvidenceVersion":1,"expectedTargetVersion":1,
				"policyVersion":"v3.1","idempotencyKey":"test-command"%3$s}
				""".formatted(ID, type, extras);
	}
}
