package com.lifeguards.liveguard.guard;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;

/** Both message entry points use the same transactional incident pipeline. */
@Service
public class JdbcCandidateSink implements CandidateSink {
	private final LiveModerationService moderation;

	public JdbcCandidateSink(LiveModerationService moderation) {
		this.moderation = moderation;
	}

	@Override
	@Transactional
	public UUID open(CandidateRequest request) {
		return moderation.candidate(request);
	}
}
