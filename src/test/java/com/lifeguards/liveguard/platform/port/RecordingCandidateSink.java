package com.lifeguards.liveguard.platform.port;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * B2 구현을 흉내 내는 스캔 대상 컴포넌트 (recording-sink 프로파일에서만 등록).
 * 스캔된 컴포넌트가 임시 구현을 대체하는 실제 경로를 그대로 쓴다.
 */
@Component
@Profile("recording-sink")
public class RecordingCandidateSink implements CandidateSink {

	public final List<CandidateRequest> requests = new CopyOnWriteArrayList<>();
	/** 요청을 받아 caseId를 정하거나(기본: 새 UUID) 예외를 던진다. 호출 안에서 DB를 확인하는 용도로도 쓴다. */
	public volatile Function<CandidateRequest, UUID> behavior = request -> UUID.randomUUID();

	@Override
	public UUID open(CandidateRequest request) {
		requests.add(request);
		return behavior.apply(request);
	}

	public void reset() {
		requests.clear();
		behavior = request -> UUID.randomUUID();
	}

}
