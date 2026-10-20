package com.lifeguards.liveguard.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class TraceIdFilterTest {

	private final TraceIdFilter filter = new TraceIdFilter();

	@Test
	void generatesTraceIdWhenHeaderIsMissing() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInChain = new AtomicReference<>();

		filter.doFilter(new MockHttpServletRequest(), response, capturing(seenInChain));

		String traceId = response.getHeader(TraceIdFilter.HEADER);
		assertEquals(traceId, UUID.fromString(traceId).toString());
		assertEquals(traceId, seenInChain.get());
	}

	@Test
	void reusesInboundTraceIdInUuidFormat() throws Exception {
		String inbound = UUID.randomUUID().toString().toUpperCase();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.HEADER, inbound);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInChain = new AtomicReference<>();

		filter.doFilter(request, response, capturing(seenInChain));

		assertEquals(inbound.toLowerCase(), response.getHeader(TraceIdFilter.HEADER));
		assertEquals(inbound.toLowerCase(), seenInChain.get());
	}

	@Test
	void replacesInboundTraceIdThatIsNotAUuid() throws Exception {
		String forged = "abc\r\nSet-Cookie: x=1";
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.HEADER, forged);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, capturing(new AtomicReference<>()));

		String traceId = response.getHeader(TraceIdFilter.HEADER);
		assertNotEquals(forged, traceId);
		assertTrue(traceId.matches("[0-9a-f-]{36}"));
	}

	@Test
	void clearsMdcAfterTheRequest() throws Exception {
		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
				capturing(new AtomicReference<>()));

		assertNull(MDC.get(TraceIdFilter.MDC_KEY));
		assertNull(TraceIdFilter.current());
	}

	private static FilterChain capturing(AtomicReference<String> seen) {
		return (request, response) -> seen.set(TraceIdFilter.current());
	}

}
