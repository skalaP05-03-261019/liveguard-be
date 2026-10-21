package com.lifeguards.liveguard.platform.port;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lifeguards.liveguard.support.CaseFixtures;

/**
 * B2 동작을 제어하는 계약 테스트 대역. 실제 사건 FK를 만족시키면서 기본 sink 대신 주입한다.
 */
@Component
@Profile("recording-sink")
@Primary
public class RecordingCandidateSink implements CandidateSink {

	private final JdbcTemplate jdbc;

	public RecordingCandidateSink(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public final List<CandidateRequest> requests = new CopyOnWriteArrayList<>();
	/** 요청을 받아 caseId를 정하거나(기본: 새 UUID) 예외를 던진다. 호출 안에서 DB를 확인하는 용도로도 쓴다. */
	public volatile Function<CandidateRequest, UUID> behavior = request -> UUID.randomUUID();

	@Override
	public UUID open(CandidateRequest request) {
		requests.add(request);
		UUID caseId = behavior.apply(request);
		CaseFixtures.insertCase(jdbc, caseId, request.channelId(), request.userId());
		return caseId;
	}

	public void reset() {
		requests.clear();
		behavior = request -> UUID.randomUUID();
	}

}
