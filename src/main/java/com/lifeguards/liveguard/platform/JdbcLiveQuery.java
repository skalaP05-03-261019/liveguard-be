package com.lifeguards.liveguard.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lifeguards.liveguard.contracts.dto.response.Live;
import com.lifeguards.liveguard.platform.port.LiveQuery;

@Repository
class JdbcLiveQuery implements LiveQuery {

	private static final String COLUMNS = "id, channel_id, started_at, ended_at";

	private final JdbcClient jdbc;

	JdbcLiveQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Live> find(UUID liveId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM platform.lives WHERE id = ?")
				.param(liveId).query(JdbcLiveQuery::toLive).optional();
	}

	@Override
	public Optional<Live> currentLive(UUID channelId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM platform.lives WHERE channel_id = ? AND ended_at IS NULL")
				.param(channelId).query(JdbcLiveQuery::toLive).optional();
	}

	/** 전송과 종료를 직렬화한다. 종료 UPDATE는 이 공유 잠금이 풀릴 때까지 기다린다. */
	Optional<Live> lockForSend(UUID liveId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM platform.lives WHERE id = ? FOR SHARE")
				.param(liveId).query(JdbcLiveQuery::toLive).optional();
	}

	/** 진행 중 방송이 이미 있으면 empty (채널당 하나, 부분 UNIQUE). */
	Optional<Live> start(UUID channelId, Instant startedAt) {
		return jdbc.sql("INSERT INTO platform.lives (channel_id, started_at) VALUES (?, ?) "
				+ "ON CONFLICT (channel_id) WHERE ended_at IS NULL DO NOTHING RETURNING " + COLUMNS)
				.params(channelId, Timestamp.from(startedAt)).query(JdbcLiveQuery::toLive).optional();
	}

	/** 이미 끝난 방송이면 empty. */
	Optional<Live> end(UUID liveId, Instant endedAt) {
		return jdbc.sql("UPDATE platform.lives SET ended_at = ? WHERE id = ? AND ended_at IS NULL RETURNING " + COLUMNS)
				.params(Timestamp.from(endedAt), liveId).query(JdbcLiveQuery::toLive).optional();
	}

	private static Live toLive(ResultSet rs, int rowNum) throws SQLException {
		OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
		return new Live(rs.getObject("id", UUID.class), rs.getObject("channel_id", UUID.class),
				rs.getObject("started_at", OffsetDateTime.class).toInstant(),
				endedAt == null ? null : endedAt.toInstant());
	}

}
