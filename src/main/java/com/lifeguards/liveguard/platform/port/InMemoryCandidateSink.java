package com.lifeguards.liveguard.platform.port;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** B2 구현 전 임시 구현: 호출마다 새 caseId를 만들고 최근 요청만 메모리에 보관한다. */
public class InMemoryCandidateSink implements CandidateSink {

	private static final Logger log = LoggerFactory.getLogger(InMemoryCandidateSink.class);
	private static final int MAX_RETAINED = 1000;

	private final Deque<CandidateRequest> recent = new ArrayDeque<>();

	@Override
	public synchronized UUID open(CandidateRequest request) {
		UUID caseId = UUID.randomUUID();
		recent.addLast(request);
		if (recent.size() > MAX_RETAINED) {
			recent.removeFirst();
		}
		// 원문은 받지 않으므로 id와 판정만 남긴다
		log.debug("Candidate case {} for message {} ({})", caseId, request.messageId(), request.filterResult());
		return caseId;
	}

	public synchronized List<CandidateRequest> recentRequests() {
		return List.copyOf(recent);
	}

}
