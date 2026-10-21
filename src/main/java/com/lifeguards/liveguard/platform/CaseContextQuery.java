package com.lifeguards.liveguard.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.dto.response.ChannelInfo;
import com.lifeguards.liveguard.contracts.dto.response.ChannelInfo.EventInfo;
import com.lifeguards.liveguard.contracts.dto.response.ChannelInfo.ObservedLink;
import com.lifeguards.liveguard.contracts.dto.response.ContextData;
import com.lifeguards.liveguard.contracts.dto.response.MaskedMessage;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.filter.PiiMasker;
import com.lifeguards.liveguard.filter.UrlHosts;

/**
 * 내부 API(Agent)용 사건 조회. 사건은 messages.case_id로 찾는다 (guard.cases는 B2 소유라 직접 읽지 않는다).
 * 응답에는 닉네임·userId를 넣지 않고 사건 내 가명(U1, U2…)과 마스킹한 본문만 넣는다.
 */
@Service
public class CaseContextQuery {

	public static final int MAX_SIDE = 10;
	private static final int MAX_MESSAGES = 20;
	private static final Set<AuthorRole> OFFICIAL_ROLES = Set.of(AuthorRole.STREAMER, AuthorRole.OFFICIAL_STAFF,
			AuthorRole.MODERATOR);

	private record Row(UUID id, UUID channelId, UUID liveId, UUID authorId, String text, String visibility,
			Instant sentAt, UUID caseId) {
	}

	private static final String COLUMNS = "id, channel_id, live_id, author_id, text, visibility, sent_at, case_id";

	private final JdbcClient jdbc;
	private final JdbcChannelMembershipQuery memberships;

	CaseContextQuery(JdbcClient jdbc, JdbcChannelMembershipQuery memberships) {
		this.jdbc = jdbc;
		this.memberships = memberships;
	}

	/** 사건 메시지가 있는 채널. 없으면 empty. */
	@Transactional(readOnly = true)
	public Optional<UUID> channelOf(UUID caseId) {
		return caseMessages(caseId).stream().findFirst().map(Row::channelId);
	}

	/**
	 * 사건 메시지 + 첫 사건 메시지 앞 최대 before건 + 그 사이 다른 메시지 + 마지막 사건 메시지 뒤 최대 after건 (시간순).
	 * 20건을 넘으면 사건 메시지를 먼저 남기고 사건에 가까운 맥락부터 채우며, 이때 truncated=true.
	 */
	@Transactional(readOnly = true)
	public ContextData context(UUID caseId, int before, int after) {
		if (before < 0 || before > MAX_SIDE || after < 0 || after > MAX_SIDE) {
			throw new InvalidContractException("before and after must be between 0 and " + MAX_SIDE);
		}
		List<Row> evidence = requireCaseMessages(caseId);
		Row first = evidence.getFirst();
		Row last = evidence.getLast();

		// 문맥은 증거와 같은 방송 안에서만 모은다(사건은 한 방송에 속한다). 방송 도입 전 메시지(live_id NULL)가 증거면
		// 같은 채널의 도입 전 메시지끼리만 본다. 다른 방송의 메시지가 앞뒤 문맥에 섞이지 않게 한다
		boolean legacy = first.liveId() == null;
		String scope = legacy ? "channel_id = ? AND live_id IS NULL" : "live_id = ?";
		UUID scopeId = legacy ? first.channelId() : first.liveId();

		List<Row> window = new ArrayList<>();
		List<Row> earlier = new ArrayList<>(jdbc.sql("SELECT " + COLUMNS + " FROM platform.messages "
				+ "WHERE " + scope + " AND (sent_at, id) < (?, ?) ORDER BY sent_at DESC, id DESC LIMIT ?")
				.params(scopeId, Timestamp.from(first.sentAt()), first.id(), before)
				.query((rs, n) -> row(rs)).list());
		Collections.reverse(earlier);
		window.addAll(earlier);
		window.addAll(jdbc.sql("SELECT " + COLUMNS + " FROM platform.messages "
				+ "WHERE " + scope + " AND (sent_at, id) >= (?, ?) AND (sent_at, id) <= (?, ?) ORDER BY sent_at, id")
				.params(scopeId, Timestamp.from(first.sentAt()), first.id(), Timestamp.from(last.sentAt()),
						last.id())
				.query((rs, n) -> row(rs)).list());
		window.addAll(jdbc.sql("SELECT " + COLUMNS + " FROM platform.messages "
				+ "WHERE " + scope + " AND (sent_at, id) > (?, ?) ORDER BY sent_at, id LIMIT ?")
				.params(scopeId, Timestamp.from(last.sentAt()), last.id(), after)
				.query((rs, n) -> row(rs)).list());

		List<Row> selected = window.size() <= MAX_MESSAGES ? window : capKeepingEvidence(window, caseId);
		boolean truncated = selected.size() < window.size();

		// 사건 작성자가 U1, 나머지는 응답에 처음 나온 순서대로 U2, U3…
		Map<UUID, String> aliases = new LinkedHashMap<>();
		aliases.put(first.authorId(), "U1");
		for (Row row : selected) {
			aliases.computeIfAbsent(row.authorId(), id -> "U" + (aliases.size() + 1));
		}
		List<MaskedMessage> messages = selected.stream()
				.map(row -> new MaskedMessage(row.id(), aliases.get(row.authorId()), PiiMasker.mask(row.text()),
						row.sentAt(), row.visibility()))
				.toList();
		return new ContextData(messages, truncated);
	}

	@Transactional(readOnly = true)
	public ChannelInfo channelInfo(UUID caseId) {
		List<Row> evidence = requireCaseMessages(caseId);
		Row first = evidence.getFirst();
		List<String> officialHosts = jdbc.sql(
				"SELECT jsonb_array_elements_text(official_hosts) FROM platform.channels WHERE id = ?")
				.param(first.channelId()).query(String.class).list();
		List<EventInfo> events = jdbc.sql("SELECT e ->> 'title' AS title, e ->> 'summary' AS summary "
				+ "FROM platform.channels c, jsonb_array_elements(c.official_events) e WHERE c.id = ?")
				.param(first.channelId())
				.query((rs, n) -> new EventInfo(rs.getString("title"), rs.getString("summary"))).list();
		// 현재 역할이다(사건 당시 역할이 아니다). 멤버십을 확인할 수 없으면 VIEWER로 추정하지 않고 UNKNOWN이며,
		// isOfficialOperator=false는 공식 운영자로 확인되지 않았다는 뜻일 뿐 시청자로 확인됐다는 뜻이 아니다
		AuthorRole authorRole = memberships.memberRole(first.channelId(), first.authorId()).orElse(AuthorRole.UNKNOWN);

		Set<String> official = officialHosts.stream().map(UrlHosts::normalizeHost).collect(Collectors.toSet());
		List<ObservedLink> observedLinks = new ArrayList<>();
		for (Row row : evidence) {
			for (String host : UrlHosts.extract(row.text())) {
				observedLinks.add(new ObservedLink(row.id(), host, official.contains(host)));
			}
		}
		return new ChannelInfo(officialHosts, events, authorRole, OFFICIAL_ROLES.contains(authorRole),
				observedLinks);
	}

	private List<Row> requireCaseMessages(UUID caseId) {
		List<Row> rows = caseMessages(caseId);
		if (rows.isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Case has no messages");
		}
		return rows;
	}

	private List<Row> caseMessages(UUID caseId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM platform.messages WHERE case_id = ? ORDER BY sent_at, id")
				.param(caseId).query((rs, n) -> row(rs)).list();
	}

	/** 사건 메시지(넘치면 최근 것)를 먼저 남기고, 남는 자리는 가장 가까운 사건 메시지와의 거리 순으로 채운다. */
	private static List<Row> capKeepingEvidence(List<Row> window, UUID caseId) {
		List<Integer> evidenceIndexes = new ArrayList<>();
		for (int i = 0; i < window.size(); i++) {
			if (caseId.equals(window.get(i).caseId())) {
				evidenceIndexes.add(i);
			}
		}
		List<Integer> keep = new ArrayList<>(evidenceIndexes.subList(
				Math.max(0, evidenceIndexes.size() - MAX_MESSAGES), evidenceIndexes.size()));
		List<Integer> context = new ArrayList<>();
		for (int i = 0; i < window.size(); i++) {
			if (!caseId.equals(window.get(i).caseId())) {
				context.add(i);
			}
		}
		context.sort(Comparator.comparingInt((Integer i) -> distance(i, keep)).thenComparingInt(i -> i));
		for (Integer index : context) {
			if (keep.size() >= MAX_MESSAGES) {
				break;
			}
			keep.add(index);
		}
		return keep.stream().sorted().map(window::get).toList();
	}

	private static int distance(int index, List<Integer> anchors) {
		return anchors.stream().mapToInt(anchor -> Math.abs(anchor - index)).min().orElse(Integer.MAX_VALUE);
	}

	private static Row row(ResultSet rs) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getObject("channel_id", UUID.class),
				rs.getObject("live_id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("text"),
				rs.getString("visibility"), rs.getObject("sent_at", OffsetDateTime.class).toInstant(),
				rs.getObject("case_id", UUID.class));
	}

}
