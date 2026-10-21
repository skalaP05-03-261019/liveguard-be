package com.lifeguards.liveguard.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lifeguards.liveguard.contracts.dto.request.AgentEventRequest;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import({ GlobalExceptionHandler.class, TraceIdFilter.class, GlobalExceptionHandlerTest.TestController.class })
@ExtendWith(OutputCaptureExtension.class)
@WithMockUser
class GlobalExceptionHandlerTest {

	private static final String VALID_MESSAGE = """
			{"clientMessageId":"11111111-1111-4111-8111-111111111111","text":"hello"}""";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void validRequestPassesThrough() throws Exception {
		mockMvc.perform(post("/test/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID_MESSAGE))
				.andExpect(status().isOk());
	}

	@Test
	void contractValidationFailureInBodyIs400() throws Exception {
		String secret = "x".repeat(501);
		String body = "{\"clientMessageId\":\"11111111-1111-4111-8111-111111111111\",\"text\":\"" + secret + "\"}";

		MvcResult result = mockMvc.perform(post("/test/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.retryable").value(false))
				.andExpect(jsonPath("$.error.message").value("text must be at most 500 characters"))
				.andReturn();

		assertFalse(result.getResponse().getContentAsString().contains(secret));
	}

	@Test
	void blankTextAndMissingFieldsAre400() throws Exception {
		mockMvc.perform(post("/test/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"11111111-1111-4111-8111-111111111111\",\"text\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("text must not be blank"));
		mockMvc.perform(post("/test/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void unknownFieldIsRejected() throws Exception {
		mockMvc.perform(post("/test/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
				{"clientMessageId":"11111111-1111-4111-8111-111111111111","text":"hello","role":"MODERATOR"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("Unknown field: role"));
	}

	@Test
	void agentEventToleratesUnknownTopLevelFields() throws Exception {
		mockMvc.perform(post("/test/agent-events").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"event\":\"TOOL_START\",\"payload\":{},\"extra\":1}"))
				.andExpect(status().isOk());
	}

	@Test
	void malformedJsonAndMissingBodyAre400WithoutEchoingInput() throws Exception {
		MvcResult result = mockMvc.perform(post("/test/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\": SECRET-TEXT"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("Malformed request body"))
				.andReturn();
		assertFalse(result.getResponse().getContentAsString().contains("SECRET-TEXT"));

		mockMvc.perform(post("/test/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void invalidEnumValueIs400WithoutEchoingInput() throws Exception {
		MvcResult result = mockMvc.perform(post("/test/agent-events").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"event\":\"SECRET-EVENT\",\"payload\":{}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andReturn();
		assertFalse(result.getResponse().getContentAsString().contains("SECRET-EVENT"));
	}

	@Test
	void badPathVariableAndMissingParameterAre400() throws Exception {
		MvcResult result = mockMvc.perform(get("/test/channels/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("Invalid value for 'channelId'"))
				.andReturn();
		assertFalse(result.getResponse().getContentAsString().contains("not-a-uuid"));

		mockMvc.perform(get("/test/page"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.message").value("Missing parameter: page"));
	}

	@Test
	void unknownRouteIs404() throws Exception {
		mockMvc.perform(get("/test/nothing-here"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.retryable").value(false));
	}

	@Test
	void unsupportedMethodAndMediaTypeAre400() throws Exception {
		mockMvc.perform(get("/test/messages"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		mockMvc.perform(post("/test/messages").with(csrf()).contentType(MediaType.TEXT_PLAIN).content("hello"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void errorIsJsonEvenWhenClientAcceptsSomethingElse() throws Exception {
		mockMvc.perform(get("/test/channels/not-a-uuid").accept(MediaType.TEXT_HTML))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@ParameterizedTest
	@EnumSource(ErrorCode.class)
	void apiExceptionMapsEveryErrorCodeToItsStatus(ErrorCode code) throws Exception {
		Map<ErrorCode, Integer> expected = Map.ofEntries(
				Map.entry(ErrorCode.VALIDATION_FAILED, 400),
				Map.entry(ErrorCode.UNAUTHENTICATED, 401),
				Map.entry(ErrorCode.FORBIDDEN, 403),
				Map.entry(ErrorCode.KICKED, 403),
				Map.entry(ErrorCode.TIMED_OUT, 403),
				Map.entry(ErrorCode.LIVE_ENDED, 409),
				Map.entry(ErrorCode.LIVE_ALREADY_ACTIVE, 409),
				Map.entry(ErrorCode.NOT_FOUND, 404),
				Map.entry(ErrorCode.STALE_VERSION, 409),
				Map.entry(ErrorCode.IDEMPOTENCY_CONFLICT, 409),
				Map.entry(ErrorCode.REVIEW_NOT_APPROVED, 422),
				Map.entry(ErrorCode.NOT_IMPLEMENTED, 501),
				Map.entry(ErrorCode.CASE_NOT_READY, 409),
				Map.entry(ErrorCode.TEMPORARY_UNAVAILABLE, 503),
				Map.entry(ErrorCode.INTERNAL_ERROR, 500));

		mockMvc.perform(get("/test/api-error/" + code.name()))
				.andExpect(status().is(expected.get(code)))
				.andExpect(jsonPath("$.error.code").value(code.name()))
				.andExpect(jsonPath("$.error.message").value("test message"))
				.andExpect(jsonPath("$.error.retryable").value(code == ErrorCode.TEMPORARY_UNAVAILABLE))
				.andExpect(jsonPath("$.error.until").doesNotExist())
				.andExpect(jsonPath("$.error.currentVersion").doesNotExist());
	}

	@Test
	void apiExceptionCarriesUntilAndCurrentVersion() throws Exception {
		mockMvc.perform(get("/test/kicked"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("KICKED"))
				.andExpect(jsonPath("$.error.until").value("2026-10-21T00:00:00Z"));
		mockMvc.perform(get("/test/stale"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("STALE_VERSION"))
				.andExpect(jsonPath("$.error.currentVersion").value(7));
	}

	@Test
	void invalidContractExceptionFromHandlerCodeIs400() throws Exception {
		mockMvc.perform(get("/test/invalid-contract"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("thing is required"));
	}

	@Test
	void unexpectedExceptionIs500WithoutLeakingMessageToResponseOrLog(CapturedOutput output) throws Exception {
		MvcResult result = mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.retryable").value(false))
				.andExpect(jsonPath("$.error.message").value("Internal server error"))
				.andReturn();

		String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
		assertFalse(result.getResponse().getContentAsString().contains("SECRET-CHAT-TEXT"));
		assertFalse(output.getAll().contains("SECRET-CHAT-TEXT"));
		assertTrue(output.getAll().contains("java.lang.IllegalStateException"));
		assertTrue(output.getAll().contains(traceId));
	}

	@Test
	void securityExceptionsAreNotSwallowedAs500() throws Exception {
		mockMvc.perform(get("/test/access-denied"))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/test/bad-credentials"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void bodyTraceIdMatchesResponseHeader() throws Exception {
		String inbound = UUID.randomUUID().toString();

		mockMvc.perform(get("/test/boom").header(TraceIdFilter.HEADER, inbound))
				.andExpect(header().string(TraceIdFilter.HEADER, inbound))
				.andExpect(jsonPath("$.traceId").value(inbound));
	}

	@RestController
	@RequestMapping("/test")
	static class TestController {

		@PostMapping("/messages")
		Map<String, Boolean> send(@RequestBody SendMessageRequest request) {
			return Map.of("ok", true);
		}

		@PostMapping("/agent-events")
		Map<String, Boolean> agentEvent(@RequestBody AgentEventRequest request) {
			return Map.of("ok", true);
		}

		@GetMapping("/channels/{channelId}")
		String channel(@PathVariable UUID channelId) {
			return channelId.toString();
		}

		@GetMapping("/page")
		String page(@RequestParam int page) {
			return "page " + page;
		}

		@GetMapping("/api-error/{code}")
		void apiError(@PathVariable ErrorCode code) {
			throw new ApiException(code, "test message");
		}

		@GetMapping("/kicked")
		void kicked() {
			throw new ApiException(ErrorCode.KICKED, "kicked", Instant.parse("2026-10-21T00:00:00Z"), null);
		}

		@GetMapping("/stale")
		void stale() {
			throw new ApiException(ErrorCode.STALE_VERSION, "stale", null, 7);
		}

		@GetMapping("/invalid-contract")
		void invalidContract() {
			throw new InvalidContractException("thing is required");
		}

		@GetMapping("/access-denied")
		void accessDenied() {
			throw new AccessDeniedException("denied");
		}

		@GetMapping("/bad-credentials")
		void badCredentials() {
			throw new BadCredentialsException("bad");
		}

		@GetMapping("/boom")
		void boom() {
			throw new IllegalStateException("SECRET-CHAT-TEXT");
		}

	}

}
