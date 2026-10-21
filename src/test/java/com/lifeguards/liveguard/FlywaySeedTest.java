package com.lifeguards.liveguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

/** test 프로파일에서만 db/seed가 적용되는지, 시드 내용과 재실행 안전성을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FlywaySeedTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DataSource dataSource;

	@Test
	void seedsOneAccountPerRole() {
		assertEquals(List.of("MODERATOR", "PLATFORM_ADMIN", "VIEWER"),
				jdbc.queryForList("SELECT role FROM platform.users ORDER BY role", String.class));
	}

	@Test
	void seedsBcryptPasswordHashesOnly() {
		List<String> hashes = jdbc.queryForList("SELECT password_hash FROM platform.users", String.class);

		assertEquals(3, hashes.size());
		assertTrue(hashes.stream().allMatch(h -> h.matches("\\$2[aby]\\$10\\$[./0-9A-Za-z]{53}")));
	}

	@Test
	void seedsTwoChannelsWithOfficialHosts() {
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM platform.channels", Integer.class));
		assertEquals(2, jdbc.queryForObject(
				"SELECT count(*) FROM platform.channels WHERE jsonb_array_length(official_hosts) >= 1",
				Integer.class));
	}

	@Test
	void seedsViewerAndModeratorMembershipInBothChannels() {
		assertEquals(List.of("MODERATOR", "MODERATOR", "VIEWER", "VIEWER"), jdbc.queryForList(
				"SELECT member_role FROM platform.channel_members ORDER BY member_role", String.class));
		assertEquals(0, jdbc.queryForObject("""
				SELECT count(*) FROM platform.channel_members m JOIN platform.users u ON u.id = m.user_id
				WHERE u.role = 'PLATFORM_ADMIN'""", Integer.class));
	}

	@Test
	void seedsOneActiveLivePerChannel() {
		assertEquals(2, jdbc.queryForObject("""
				SELECT count(*) FROM platform.lives
				WHERE ended_at IS NULL AND channel_id IN (
					'20000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002')""",
				Integer.class));
	}

	@Test
	void rerunningTheSeedKeepsTheSameRows() {
		new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__dev_seed.sql")).execute(dataSource);

		assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM platform.users", Integer.class));
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM platform.channels", Integer.class));
		assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM platform.channel_members", Integer.class));
		// 이미 진행 중인 방송이 있으면 시드 방송을 다시 넣지 않는다(부분 유일 위반 없음)
		assertEquals(2, jdbc.queryForObject("""
				SELECT count(*) FROM platform.lives
				WHERE ended_at IS NULL AND channel_id IN (
					'20000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000002')""",
				Integer.class));
	}

}
