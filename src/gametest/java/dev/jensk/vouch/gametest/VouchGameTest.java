package dev.jensk.vouch.gametest;

import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

import dev.jensk.vouch.AuditEvent;
import dev.jensk.vouch.PendingVouch;
import dev.jensk.vouch.VouchDb;
import dev.jensk.vouch.VouchMod;

public class VouchGameTest {

	private static final UUID APPROVE_TARGET = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID DENY_TARGET = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
	private static final UUID VOUCHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000009");

	@GameTest
	public void approveWhitelistsAndAudits(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		VouchDb db = requireDb();
		NameAndId target = new NameAndId(APPROVE_TARGET, "Approvee");
		clear(server, db, target);
		db.submit(s -> s.addPending(new PendingVouch(APPROVE_TARGET, "Approvee", VOUCHER, "Voucher", 1L))).join();

		runCommand(server, "vouch approve Approvee");

		helper.succeedWhen(() -> {
			helper.assertTrue(server.getPlayerList().getWhiteList().isWhiteListed(target),
					"approved player should be whitelisted");
			helper.assertFalse(db.submit(s -> s.hasPending(APPROVE_TARGET)).join(),
					"pending entry should be cleared after approval");
			helper.assertTrue(db.submit(s -> s.readRecentAudit(10, APPROVE_TARGET)).join().stream()
					.anyMatch(e -> e.type() == AuditEvent.AuditType.APPROVE),
					"an APPROVE audit row is expected");
		});
	}

	@GameTest
	public void denyRemovesPendingWithoutWhitelisting(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		VouchDb db = requireDb();
		NameAndId target = new NameAndId(DENY_TARGET, "Denyee");
		clear(server, db, target);
		db.submit(s -> s.addPending(new PendingVouch(DENY_TARGET, "Denyee", VOUCHER, "Voucher", 1L))).join();

		runCommand(server, "vouch deny Denyee");

		helper.succeedWhen(() -> {
			helper.assertFalse(server.getPlayerList().getWhiteList().isWhiteListed(target),
					"denied player must not be whitelisted");
			helper.assertFalse(db.submit(s -> s.hasPending(DENY_TARGET)).join(),
					"pending entry should be cleared after denial");
			helper.assertTrue(db.submit(s -> s.readRecentAudit(10, DENY_TARGET)).join().stream()
					.anyMatch(e -> e.type() == AuditEvent.AuditType.DENY),
					"a DENY audit row is expected");
		});
	}

	@GameTest
	public void listAndHistoryExecuteWithoutError(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		VouchDb db = requireDb();
		runCommand(server, "vouch list");
		runCommand(server, "vouch history");

		helper.succeedWhen(() -> helper.assertTrue(
				db.submit(s -> {
					s.allPending();
					s.readRecentAudit(20, null);
					return true;
				}).join(),
				"list/history reads should succeed"));
	}

	private static VouchDb requireDb() {
		VouchDb db = VouchMod.db();
		if (db == null) {
			throw new AssertionError("VouchDatabase was not initialised by the server");
		}
		return db;
	}

	private static void runCommand(MinecraftServer server, String command) {
		CommandSourceStack source = server.createCommandSourceStack();
		server.getCommands().performPrefixedCommand(source, command);
	}

	private static void clear(MinecraftServer server, VouchDb db, NameAndId target) {
		server.getPlayerList().getWhiteList().remove(target);
		db.submit(s -> s.removePending(target.id())).join();
	}
}
