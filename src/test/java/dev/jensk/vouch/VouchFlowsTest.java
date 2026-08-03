package dev.jensk.vouch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.jensk.vouch.AuditEvent.AuditType;

import net.minecraft.server.players.NameAndId;

class VouchFlowsTest {

	private static final UUID TARGET = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID TARGET2 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
	private static final UUID VOUCHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000009");
	private static final Actor ADMIN = new Actor(UUID.fromString("cccccccc-0000-0000-0000-000000000001"), "Admin");
	private static final Actor MEMBER = new Actor(VOUCHER, "Member");

	private FakeVouchDb db;
	private FakeFx fx;
	private FakeFeedback out;

	@BeforeEach
	void setUp() {
		db = new FakeVouchDb();
		fx = new FakeFx();
		out = new FakeFeedback();
	}

	private void seedPending(UUID id, String name) {
		db.store.pending.put(id, new PendingVouch(id, name, VOUCHER, "Member", 1000));
	}

	@Test
	void approveWhitelistsSettlesAndAudits() {
		seedPending(TARGET, "Newbie");

		VouchFlows.approve(db, fx, out, ADMIN, "Newbie").join();

		assertTrue(fx.whitelist.contains(TARGET));
		assertTrue(db.store.pending.isEmpty());
		assertEquals(List.of(AuditType.APPROVE), db.store.audit.stream().map(AuditEvent::type).toList());
		assertTrue(out.anySuccessContains("Approved Newbie"));
		assertEquals(List.of("Newbie:approved"), fx.voucherNotifications);
	}

	@Test
	void approveByUuidWorks() {
		seedPending(TARGET, "Newbie");

		VouchFlows.approve(db, fx, out, ADMIN, TARGET.toString()).join();

		assertTrue(fx.whitelist.contains(TARGET));
		assertTrue(db.store.pending.isEmpty());
	}

	@Test
	void approveUnknownNameFailsWithoutSideEffects() {
		VouchFlows.approve(db, fx, out, ADMIN, "Nobody").join();

		assertTrue(fx.whitelist.isEmpty());
		assertTrue(db.store.audit.isEmpty());
		assertTrue(out.anyFailureContains("No pending vouch for 'Nobody'"));
	}

	@Test
	void approveAmbiguousNameRefusesAndSettlesNothing() {
		seedPending(TARGET, "Newbie");
		seedPending(TARGET2, "Newbie");

		VouchFlows.approve(db, fx, out, ADMIN, "Newbie").join();

		assertTrue(fx.whitelist.isEmpty(), "nothing is whitelisted on an ambiguous name");
		assertEquals(2, db.store.pending.size());
		assertTrue(db.store.audit.isEmpty());
		assertTrue(out.anyFailureContains("Multiple pending vouches match"));
	}

	@Test
	void racedDoubleApproveAuditsExactlyOnce() {
		seedPending(TARGET, "Newbie");
		db.autoRun = false;
		FakeFeedback outB = new FakeFeedback();

		VouchFlows.approve(db, fx, out, ADMIN, "Newbie");
		VouchFlows.approve(db, fx, outB, ADMIN, "Newbie");

		db.runNext();
		db.runNext();
		db.runNext();
		db.runNext();

		assertEquals(List.of(AuditType.APPROVE), db.store.audit.stream().map(AuditEvent::type).toList());
		assertTrue(out.anySuccessContains("Approved Newbie"));
		assertTrue(outB.anyFailureContains("already handled"));
		assertEquals(List.of("Newbie:approved"), fx.voucherNotifications, "only the winner notifies the voucher");
	}

	@Test
	void approveSettleFailureLeavesVisibleRecoverableState() {
		seedPending(TARGET, "Newbie");
		db.autoRun = false;

		VouchFlows.approve(db, fx, out, ADMIN, "Newbie");
		db.runNext();
		db.store.failNext(new SQLException("disk on fire"));
		db.runNext();

		assertTrue(fx.whitelist.contains(TARGET), "the grant stands");
		assertTrue(db.store.pending.containsKey(TARGET), "the pending row survives for a re-approve");
		assertTrue(db.store.audit.isEmpty());
		assertTrue(out.anyFailureContains("storage error"));
	}

	@Test
	void approveDuringShutdownTouchesNothing() {
		seedPending(TARGET, "Newbie");
		fx.rejectMain = true;

		VouchFlows.approve(db, fx, out, ADMIN, "Newbie").join();

		assertTrue(fx.whitelist.isEmpty(), "no Minecraft state is mutated once the server stopped");
		assertTrue(db.store.pending.containsKey(TARGET));
		assertTrue(db.store.audit.isEmpty());
		assertTrue(out.failures.isEmpty(), "the report hop is dropped, only the log line remains");
	}

	@Test
	void denySettlesWithDenyAudit() {
		seedPending(TARGET, "Newbie");

		VouchFlows.deny(db, fx, out, ADMIN, "Newbie").join();

		assertTrue(fx.whitelist.isEmpty());
		assertTrue(db.store.pending.isEmpty());
		assertEquals(List.of(AuditType.DENY), db.store.audit.stream().map(AuditEvent::type).toList());
		assertEquals(List.of("Newbie:denied"), fx.voucherNotifications);
	}

	@Test
	void denyUnknownNameFails() {
		VouchFlows.deny(db, fx, out, ADMIN, "Nobody").join();

		assertTrue(out.anyFailureContains("No pending vouch for 'Nobody'"));
		assertTrue(db.store.audit.isEmpty());
	}

	@Test
	void denyAmbiguousNameRefuses() {
		seedPending(TARGET, "Newbie");
		seedPending(TARGET2, "Newbie");

		VouchFlows.deny(db, fx, out, ADMIN, "Newbie").join();

		assertEquals(2, db.store.pending.size(), "nothing settled on an ambiguous name");
		assertTrue(db.store.audit.isEmpty());
		assertTrue(out.anyFailureContains("Multiple pending vouches match"));
	}

	@Test
	void vouchForCreatesPendingAndBroadcasts() {
		VouchFlows.vouchFor(db, fx, out, MEMBER, new NameAndId(TARGET, "Newbie")).join();

		assertTrue(db.store.pending.containsKey(TARGET));
		assertEquals(List.of(AuditType.VOUCH), db.store.audit.stream().map(AuditEvent::type).toList());
		assertEquals(1, fx.broadcasts.size());
		assertTrue(out.anySuccessContains("Vouched for Newbie"));
	}

	@Test
	void vouchForDuplicateKeepsFirstVouchAndAuditsOnce() {
		VouchFlows.vouchFor(db, fx, out, MEMBER, new NameAndId(TARGET, "Newbie")).join();
		VouchFlows.vouchFor(db, fx, out, ADMIN, new NameAndId(TARGET, "Newbie")).join();

		assertEquals("Member", db.store.pending.get(TARGET).voucherName());
		assertEquals(1, db.store.audit.size());
		assertTrue(out.anyFailureContains("already has a pending vouch"));
	}

	@Test
	void vouchForAlreadyWhitelistedFails() {
		fx.whitelist.add(TARGET);

		VouchFlows.vouchFor(db, fx, out, MEMBER, new NameAndId(TARGET, "Newbie")).join();

		assertTrue(db.store.pending.isEmpty());
		assertTrue(db.store.audit.isEmpty());
		assertTrue(out.anyFailureContains("already whitelisted"));
	}

	@Test
	void inviteWhitelistsClearsPendingAndAudits() {
		seedPending(TARGET, "Friend");

		VouchFlows.invite(db, fx, out, ADMIN, List.of(new NameAndId(TARGET, "Friend")));

		assertTrue(fx.whitelist.contains(TARGET));
		assertFalse(db.store.pending.containsKey(TARGET), "a pending vouch is superseded by the invite");
		assertEquals(List.of(AuditType.INVITE), db.store.audit.stream().map(AuditEvent::type).toList());
		assertTrue(out.anySuccessContains("Invited Friend"));
	}

	@Test
	void inviteWithoutDbStillWhitelistsButDisclosesTheAuditGap() {
		VouchFlows.invite(null, fx, out, ADMIN, List.of(new NameAndId(TARGET, "Friend")));

		assertTrue(fx.whitelist.contains(TARGET));
		assertTrue(out.anyFailureContains("not audited"));
	}

	@Test
	void inviteAlreadyWhitelistedReportsNothingInvited() {
		fx.whitelist.add(TARGET);

		VouchFlows.invite(db, fx, out, ADMIN, List.of(new NameAndId(TARGET, "Friend")));

		assertTrue(out.anyFailureContains("already whitelisted"));
		assertTrue(out.anyFailureContains("No players were invited"));
		assertTrue(db.store.audit.isEmpty());
	}

	@Test
	void inviteAuditFailureIsDisclosedToTheActor() {
		db.store.failNext(new SQLException("disk on fire"));

		VouchFlows.invite(db, fx, out, ADMIN, List.of(new NameAndId(TARGET, "Friend")));

		assertTrue(fx.whitelist.contains(TARGET), "the invite itself stands");
		assertTrue(out.anyFailureContains("could not be audited"));
	}

	@Test
	void listMarksAlreadyWhitelistedEntries() {
		seedPending(TARGET, "Newbie");
		seedPending(TARGET2, "Stale");
		fx.whitelist.add(TARGET2);

		VouchFlows.list(db, fx, out).join();

		assertTrue(out.anySuccessContains("Pending vouches (2)"));
		assertTrue(out.successes.stream().anyMatch(s -> s.contains("Stale") && s.contains("already whitelisted")));
		assertTrue(out.successes.stream().noneMatch(s -> s.contains("Newbie") && s.contains("already whitelisted")));
	}

	@Test
	void historyReportsStorageErrorsToTheActor() {
		db.store.failNext(new SQLException("disk on fire"));

		VouchFlows.history(db, fx, out, null, null).join();

		assertTrue(out.anyFailureContains("Could not read history"));
	}
}
