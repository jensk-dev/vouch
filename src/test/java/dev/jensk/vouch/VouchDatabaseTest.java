package dev.jensk.vouch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jensk.vouch.AuditEvent.AuditType;

class VouchDatabaseTest {

	private static final UUID BOB = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CAROL = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID ALICE = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID DAVE = UUID.fromString("44444444-4444-4444-4444-444444444444");

	@Test
	void addPendingDedupesByTarget(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			assertTrue(db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 1000))).join());
			assertFalse(db.submit(s -> s.addPending(pending(BOB, "Bob", ALICE, "Alice", 2000))).join(), "duplicate target is rejected");
			assertTrue(db.submit(s -> s.hasPending(BOB)).join());
			assertEquals("Carol", db.submit(s -> s.getPending(BOB)).join().voucherName(), "first voucher is kept");
			assertEquals(1, db.submit(VouchStore::allPending).join().size());
		}
	}

	@Test
	void findAllPendingByNameIsCaseInsensitive(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 1000))).join();
			assertEquals(1, db.submit(s -> s.findAllPendingByName("bob")).join().size());
			assertEquals(1, db.submit(s -> s.findAllPendingByName("BOB")).join().size());
			assertEquals(BOB, db.submit(s -> s.findAllPendingByName("bOb")).join().getFirst().targetId());
			assertTrue(db.submit(s -> s.findAllPendingByName("nobody")).join().isEmpty());
		}
	}

	@Test
	void findAllPendingByNameReturnsEveryNameCollision(@TempDir Path dir) throws Exception {
		UUID otherBob = UUID.fromString("55555555-5555-5555-5555-555555555555");
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 1000))).join();
			db.submit(s -> s.addPending(pending(otherBob, "Bob", ALICE, "Alice", 2000))).join();
			List<PendingVouch> matches = db.submit(s -> s.findAllPendingByName("bob")).join();
			assertEquals(2, matches.size(), "both same-name entries surface for disambiguation");
			assertEquals(BOB, matches.getFirst().targetId(), "oldest entry first");
		}
	}

	@Test
	void removePendingReportsWhetherAnythingWasRemoved(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 1000))).join();
			assertTrue(db.submit(s -> s.removePending(BOB)).join());
			assertFalse(db.submit(s -> s.removePending(BOB)).join(), "second remove is a no-op");
			assertFalse(db.submit(s -> s.hasPending(BOB)).join());
		}
	}

	@Test
	void allPendingIsOrderedByCreatedAt(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 3000))).join();
			db.submit(s -> s.addPending(pending(ALICE, "Alice", CAROL, "Carol", 1000))).join();
			db.submit(s -> s.addPending(pending(DAVE, "Dave", CAROL, "Carol", 2000))).join();
			assertEquals(List.of("Alice", "Dave", "Bob"),
					db.submit(VouchStore::allPending).join().stream().map(PendingVouch::targetName).toList());
		}
	}

	@Test
	void pendingWithConsoleVoucherRoundTripsNull(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", null, "Server", 1000))).join();
			PendingVouch got = db.submit(s -> s.getPending(BOB)).join();
			assertNull(got.voucherId());
			assertEquals("Server", got.voucherName());
		}
	}

	@Test
	void recordVouchWritesAuditOnlyWhenCreated(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			assertTrue(db.submit(s -> s.recordVouch(
					pending(BOB, "Bob", CAROL, "Carol", 1000),
					audit(1000, AuditType.VOUCH, CAROL, "Carol", BOB, "Bob", null, null))).join());
			assertFalse(db.submit(s -> s.recordVouch(
					pending(BOB, "Bob", ALICE, "Alice", 2000),
					audit(2000, AuditType.VOUCH, ALICE, "Alice", BOB, "Bob", null, null))).join());
			assertEquals(1, db.submit(s -> s.readRecentAudit(10, null)).join().size(),
					"the rejected duplicate must not leave an orphan audit row");
		}
	}

	@Test
	void settlePendingRemovesAndAuditsExactlyOnce(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 1000))).join();
			assertTrue(db.submit(s -> s.settlePending(BOB,
					audit(2000, AuditType.APPROVE, ALICE, "Alice", BOB, "Bob", CAROL, "Carol"))).join());
			assertFalse(db.submit(s -> s.settlePending(BOB,
					audit(3000, AuditType.APPROVE, DAVE, "Dave", BOB, "Bob", CAROL, "Carol"))).join(),
					"second settle loses the race");
			List<AuditEvent> events = db.submit(s -> s.readRecentAudit(10, null)).join();
			assertEquals(1, events.size(), "exactly one APPROVE audit row");
			assertEquals("Alice", events.getFirst().actorName());
		}
	}

	@Test
	void recordInviteAuditsEvenWithoutPending(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.<Void>submit(s -> {
				s.recordInvite(BOB, audit(1000, AuditType.INVITE, ALICE, "Alice", BOB, "Bob", null, null));
				return null;
			}).join();
			assertEquals(1, db.submit(s -> s.readRecentAudit(10, null)).join().size());
			assertFalse(db.submit(s -> s.hasPending(BOB)).join());
		}
	}

	@Test
	void settleRollsBackWhenTheAuditInsertFails(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 1000))).join();
			var thrown = db.submit(s -> s.settlePending(BOB,
					audit(2000, AuditType.APPROVE, ALICE, null, BOB, "Bob", CAROL, "Carol")));
			assertThrows(Exception.class, thrown::join);
			assertTrue(db.submit(s -> s.hasPending(BOB)).join(), "failed settle leaves the pending row intact");
			assertTrue(db.submit(s -> s.readRecentAudit(10, null)).join().isEmpty());
		}
	}

	@Test
	void auditIsReturnedNewestFirstAndHonoursLimit(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			appendAudit(db, audit(1000, AuditType.INVITE, ALICE, "Alice", BOB, "Bob", null, null));
			appendAudit(db, audit(2000, AuditType.VOUCH, CAROL, "Carol", DAVE, "Dave", null, null));
			appendAudit(db, audit(3000, AuditType.APPROVE, ALICE, "Alice", DAVE, "Dave", CAROL, "Carol"));
			List<AuditEvent> recent = db.submit(s -> s.readRecentAudit(2, null)).join();
			assertEquals(2, recent.size());
			assertEquals(AuditType.APPROVE, recent.get(0).type(), "newest first");
			assertEquals(AuditType.VOUCH, recent.get(1).type());
		}
	}

	@Test
	void auditFilterMatchesActorTargetOrVoucher(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			appendAudit(db, audit(1000, AuditType.INVITE, ALICE, "Alice", BOB, "Bob", null, null));
			appendAudit(db, audit(2000, AuditType.VOUCH, CAROL, "Carol", DAVE, "Dave", null, null));
			appendAudit(db, audit(3000, AuditType.APPROVE, ALICE, "Alice", DAVE, "Dave", CAROL, "Carol"));
			appendAudit(db, audit(4000, AuditType.INVITE, ALICE, "Alice", CAROL, "Carol", null, null));

			List<AuditEvent> forCarol = db.submit(s -> s.readRecentAudit(20, CAROL)).join();
			assertEquals(3, forCarol.size(), "matches actor, target, or voucher");
			assertFalse(
					forCarol.stream().anyMatch(e -> e.type() == AuditType.INVITE && "Bob".equals(e.targetName())),
					"the Bob-only row is excluded");
		}
	}

	@Test
	void auditWithConsoleActorRoundTripsNull(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			appendAudit(db, audit(1000, AuditType.INVITE, null, "Server", BOB, "Bob", null, null));
			AuditEvent e = db.submit(s -> s.readRecentAudit(1, null)).join().getFirst();
			assertNull(e.actorId());
			assertEquals("Server", e.actorName());
		}
	}

	@Test
	void schemaIsIdempotentAndDataPersists(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 1000))).join();
		}
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			assertTrue(db.submit(s -> s.hasPending(BOB)).join());
		}
	}

	@Test
	void freshDatabaseIsStampedWithTheSchemaVersion(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.hasPending(BOB)).join();
		}
		try (Connection raw = rawConnection(dir); Statement st = raw.createStatement()) {
			assertEquals(1, st.executeQuery("PRAGMA user_version").getInt(1));
		}
	}

	@Test
	void refusesToOpenANewerSchema(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.hasPending(BOB)).join();
		}
		try (Connection raw = rawConnection(dir); Statement st = raw.createStatement()) {
			st.execute("PRAGMA user_version = 99");
		}
		assertThrows(SQLException.class, () -> VouchDatabase.open(dir).close());
	}

	@Test
	void adoptsALegacyUnversionedDatabase(@TempDir Path dir) throws Exception {
		try (Connection raw = rawConnection(dir); Statement st = raw.createStatement()) {
			st.execute("CREATE TABLE pending_vouches (target_id TEXT PRIMARY KEY, target_name TEXT NOT NULL,"
					+ " voucher_id TEXT, voucher_name TEXT NOT NULL, created_at INTEGER NOT NULL)");
			st.execute("INSERT INTO pending_vouches VALUES ('" + BOB + "', 'Bob', null, 'Server', 1000)");
		}
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			assertTrue(db.submit(s -> s.hasPending(BOB)).join(), "pre-versioning data is adopted");
		}
	}

	@Test
	void corruptRowsAreSkippedInsteadOfFailingTheRead(@TempDir Path dir) throws Exception {
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			db.submit(s -> s.addPending(pending(BOB, "Bob", CAROL, "Carol", 1000))).join();
			appendAudit(db, audit(1000, AuditType.INVITE, ALICE, "Alice", BOB, "Bob", null, null));
		}
		try (Connection raw = rawConnection(dir); Statement st = raw.createStatement()) {
			st.execute("INSERT INTO pending_vouches VALUES ('not-a-uuid', 'Mallory', null, 'Server', 2000)");
			st.execute("INSERT INTO audit_events (ts, type, actor_id, actor_name, target_id, target_name)"
					+ " VALUES (3000, 'NO_SUCH_TYPE', null, 'Server', 'garbage', 'Mallory')");
		}
		try (VouchDatabase db = VouchDatabase.open(dir)) {
			List<PendingVouch> pending = db.submit(VouchStore::allPending).join();
			assertEquals(List.of("Bob"), pending.stream().map(PendingVouch::targetName).toList());
			List<AuditEvent> events = db.submit(s -> s.readRecentAudit(10, null)).join();
			assertEquals(List.of(AuditType.INVITE), events.stream().map(AuditEvent::type).toList());
		}
	}

	private static Connection rawConnection(Path dir) throws SQLException {
		Connection c = new org.sqlite.JDBC().connect(
				"jdbc:sqlite:" + dir.resolve("vouch.db").toAbsolutePath(), new Properties());
		if (c == null) {
			throw new SQLException("no raw connection");
		}
		return c;
	}

	private static void appendAudit(VouchDatabase db, AuditEvent event) {
		db.<Void>submit(s -> {
			s.appendAudit(event);
			return null;
		}).join();
	}

	private static PendingVouch pending(UUID target, String name, UUID voucher, String voucherName, long at) {
		return new PendingVouch(target, name, voucher, voucherName, at);
	}

	private static AuditEvent audit(long ts, AuditType type, UUID actorId, String actorName,
			UUID targetId, String targetName, UUID voucherId, String voucherName) {
		return new AuditEvent(ts, type, actorId, actorName, targetId, targetName, voucherId, voucherName);
	}
}
