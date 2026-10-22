package com.lifeguards.liveguard;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CanonicalUpgradeTest {
	@Autowired PostgreSQLContainer postgres;

	@Test
	void upgradesExistingGuardDatabaseWithLatePlatformMigrationAndReconcilesFindingTimes() throws Exception {
		String database="upgrade_"+UUID.randomUUID().toString().replace("-","");
		String url=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+database);
		Path location=Path.of("build","canonical-upgrade-"+database);
		Files.createDirectories(location);
		List<String> oldFiles=List.of("V100__schemas_and_extensions.sql","V101__platform_core.sql",
			"V102__live_integration.sql","V200__guard_cases_and_findings.sql","V201__agent_task_outbox.sql",
			"V202__live_review_integration.sql","V203__live_review_integration.sql","V204__kick_ban_ownership.sql");
		try(Connection admin=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())){
			admin.createStatement().execute("CREATE DATABASE "+database);
			try {
				for(String file:oldFiles){
					try(var source=getClass().getResourceAsStream("/db/migration/"+file)){
						Files.copy(Objects.requireNonNull(source),location.resolve(file));
					}
				}
				Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword())
					.locations("filesystem:"+location.toAbsolutePath()).load().migrate();
				UUID channel=UUID.randomUUID(),user=UUID.randomUUID(),caseId=UUID.randomUUID(),finding=UUID.randomUUID();
				Instant at=Instant.parse("2026-10-20T00:00:00Z");
				try(Connection db=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword())){
					execute(db,"INSERT INTO platform.channels(id,name) VALUES(?,'upgrade')",channel);
					execute(db,"INSERT INTO platform.users(id,username,password_hash,nickname,role) VALUES(?,'upgrade','unused','test','VIEWER')",user);
					execute(db,"INSERT INTO guard.cases(id,channel_id,user_id,snapshot_id,occurred_at,created_at) VALUES(?,?,?,?,?,?)",
						caseId,channel,user,UUID.randomUUID(),Timestamp.from(at),Timestamp.from(at));
					execute(db,"INSERT INTO guard.findings(id,case_id,category,source,verdict,status,policy_version,evidence_reference,evidence_version,created_at) VALUES(?,?,'FLOOD','BACKEND','VIOLATION','REVOKED','v3.1','legacy',1,?)",
						finding,caseId,Timestamp.from(at));
					execute(db,"INSERT INTO guard.finding_events(id,finding_id,event_type,decision_reference,reason,created_at) VALUES(?,?,'CONFIRMED','legacy-confirm','fixture',?),(?,?,'REVOKED','legacy-revoke','fixture',?)",
						UUID.randomUUID(),finding,Timestamp.from(at.plusSeconds(60)),UUID.randomUUID(),finding,Timestamp.from(at.plusSeconds(120)));
				}
				var result=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword())
					.locations("classpath:db/migration").outOfOrder(true).load().migrate();
				assertEquals(3,result.migrationsExecuted);
				try(Connection db=DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword())){
					try(var rows=db.createStatement().executeQuery("SELECT confirmed_at,revoked_at FROM guard.findings")){
						assertTrue(rows.next());
						assertEquals(at.plusSeconds(60),rows.getTimestamp(1).toInstant());
						assertEquals(at.plusSeconds(120),rows.getTimestamp(2).toInstant());
					}
					try(var rows=db.createStatement().executeQuery("SELECT live_id,policy_version,handling_route FROM guard.cases")){
						assertTrue(rows.next());for(int i=1;i<=3;i++)assertNull(rows.getObject(i));
					}
					try(var rows=db.createStatement().executeQuery("SELECT count(*) FROM guard.case_snapshots")){
						assertTrue(rows.next());assertEquals(0,rows.getInt(1));
					}
				}
			} finally {
				admin.createStatement().execute("DROP DATABASE "+database+" WITH (FORCE)");
			}
		} finally {
			for(String file:oldFiles)Files.deleteIfExists(location.resolve(file));
			Files.deleteIfExists(location);
		}
	}

	private void execute(Connection connection,String sql,Object... arguments) throws SQLException {
		try(var statement=connection.prepareStatement(sql)){
			for(int i=0;i<arguments.length;i++)statement.setObject(i+1,arguments[i]);
			statement.executeUpdate();
		}
	}
}
