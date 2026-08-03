package dev.jensk.vouch;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;

public final class VouchFlows {

	static final int HISTORY_LIMIT = 20;

	public interface Fx {
		Executor mainThread();

		boolean isWhitelisted(NameAndId target);

		boolean addToWhitelist(NameAndId target);

		void broadcastPending(PendingVouch vouch);

		void notifyVoucher(PendingVouch vouch, boolean approved);
	}

	public interface Feedback {
		void success(Component message, boolean notifyOps);

		void failure(Component message);
	}

	public static void invite(VouchDb db, Fx fx, Feedback out, Actor actor, List<NameAndId> targets) {
		int added = 0;
		for (NameAndId target : targets) {
			if (!fx.addToWhitelist(target)) {
				out.failure(Component.literal(target.name() + " is already whitelisted."));
				continue;
			}
			out.success(Component.literal("Invited " + target.name() + " — they can now join."), true);
			added++;

			if (db == null) {
				out.failure(Component.literal("Vouch storage is unavailable — this invite was not audited."));
				continue;
			}
			AuditEvent audit = AuditEvent.invite(actor, target.id(), target.name());
			UUID targetId = target.id();
			db.<Void>submit(store -> {
				store.recordInvite(targetId, audit);
				return null;
			}).exceptionally(ex -> {
				VouchMod.LOGGER.error("Failed to record invite for {}", target.name(), unwrap(ex));
				reportLater(fx, out, "The invite of " + target.name() + " could not be audited (storage error).");
				return null;
			});
		}
		if (added == 0) {
			out.failure(Component.literal("No players were invited."));
		}
	}

	public static CompletableFuture<Void> vouchFor(VouchDb db, Fx fx, Feedback out, Actor actor, NameAndId target) {
		if (fx.isWhitelisted(target)) {
			out.failure(Component.literal(target.name() + " is already whitelisted."));
			return CompletableFuture.completedFuture(null);
		}
		PendingVouch vouch = PendingVouch.of(target.id(), target.name(), actor);
		AuditEvent audit = AuditEvent.vouch(actor, vouch);
		return dispatch(db, fx, out, "Could not record the vouch (storage error).",
				store -> store.recordVouch(vouch, audit),
				created -> {
					if (created) {
						fx.broadcastPending(vouch);
						out.success(Component.literal(
								"Vouched for " + target.name() + " — a trusted player must approve it.")
								.withStyle(ChatFormatting.GREEN), false);
					} else {
						out.failure(Component.literal(target.name() + " already has a pending vouch."));
					}
				});
	}

	public static CompletableFuture<Void> approve(VouchDb db, Fx fx, Feedback out, Actor actor, String nameOrId) {
		return db.submit(store -> lookup(store, nameOrId))
				.thenComposeAsync(matches -> {
					PendingVouch entry = single(out, nameOrId, matches);
					if (entry == null) {
						return CompletableFuture.<Void>completedFuture(null);
					}
					fx.addToWhitelist(new NameAndId(entry.targetId(), entry.targetName()));
					AuditEvent audit = AuditEvent.approve(actor, entry);
					return db.submit(store -> store.settlePending(entry.targetId(), audit))
							.thenAcceptAsync(settled -> {
								if (settled) {
									out.success(Component.literal(
											"Approved " + entry.targetName() + ". They can now join.")
											.withStyle(ChatFormatting.GREEN), true);
									fx.notifyVoucher(entry, true);
								} else {
									out.failure(Component.literal(
											"The vouch for " + entry.targetName() + " was already handled."));
								}
							}, fx.mainThread());
				}, fx.mainThread())
				.exceptionally(logAndReport(fx, out, "Could not approve the vouch (storage error)."));
	}

	public static CompletableFuture<Void> deny(VouchDb db, Fx fx, Feedback out, Actor actor, String nameOrId) {
		return db.submit(store -> {
					List<PendingVouch> matches = lookup(store, nameOrId);
					if (matches.size() == 1) {
						PendingVouch entry = matches.getFirst();
						store.settlePending(entry.targetId(), AuditEvent.deny(actor, entry));
					}
					return matches;
				})
				.thenAcceptAsync(matches -> {
					PendingVouch entry = single(out, nameOrId, matches);
					if (entry == null) {
						return;
					}
					out.success(Component.literal("Denied the vouch for " + entry.targetName() + ".")
							.withStyle(ChatFormatting.RED), true);
					fx.notifyVoucher(entry, false);
				}, fx.mainThread())
				.exceptionally(logAndReport(fx, out, "Could not deny the vouch (storage error)."));
	}

	public static CompletableFuture<Void> list(VouchDb db, Fx fx, Feedback out) {
		return dispatch(db, fx, out, "Could not list vouches (storage error).",
				VouchStore::allPending,
				pending -> {
					if (pending.isEmpty()) {
						out.success(Component.literal("No pending vouches.")
								.withStyle(ChatFormatting.GRAY), false);
						return;
					}
					out.success(Component.literal("Pending vouches (" + pending.size() + "):")
							.withStyle(ChatFormatting.GOLD), false);
					for (PendingVouch v : pending) {
						var line = VouchNotifications.pendingLine(v);
						if (fx.isWhitelisted(new NameAndId(v.targetId(), v.targetName()))) {
							line.append(Component.literal(" (already whitelisted)")
									.withStyle(ChatFormatting.DARK_GRAY));
						}
						out.success(line, false);
					}
				});
	}

	public static CompletableFuture<Void> history(VouchDb db, Fx fx, Feedback out, UUID playerFilter, String whoName) {
		return dispatch(db, fx, out, "Could not read history (storage error).",
				store -> store.readRecentAudit(HISTORY_LIMIT, playerFilter),
				events -> {
					if (events.isEmpty()) {
						out.success(Component.literal(
								whoName == null ? "No vouch history yet." : "No vouch history for " + whoName + ".")
								.withStyle(ChatFormatting.GRAY), false);
						return;
					}
					out.success(Component.literal(
							(whoName == null ? "Recent vouch history" : "Vouch history for " + whoName)
									+ " (" + events.size() + "):").withStyle(ChatFormatting.GOLD), false);
					for (AuditEvent e : events) {
						out.success(VouchNotifications.auditLine(e), false);
					}
				});
	}

	private static List<PendingVouch> lookup(VouchStore store, String nameOrId) throws SQLException {
		UUID id = tryParseUuid(nameOrId);
		if (id != null) {
			PendingVouch v = store.getPending(id);
			return v == null ? List.of() : List.of(v);
		}
		return store.findAllPendingByName(nameOrId);
	}

	private static PendingVouch single(Feedback out, String nameOrId, List<PendingVouch> matches) {
		if (matches.isEmpty()) {
			out.failure(Component.literal("No pending vouch for '" + nameOrId + "'."));
			return null;
		}
		if (matches.size() > 1) {
			out.failure(Component.literal(
					"Multiple pending vouches match '" + nameOrId + "' — use their buttons to pick one:"));
			for (PendingVouch v : matches) {
				out.failure(VouchNotifications.pendingLine(v));
			}
			return null;
		}
		return matches.getFirst();
	}

	private static UUID tryParseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static <T> CompletableFuture<Void> dispatch(VouchDb db, Fx fx, Feedback out, String failure,
			VouchDb.DbTask<T> task, Consumer<T> effects) {
		return db.submit(task)
				.thenAcceptAsync(effects, fx.mainThread())
				.exceptionally(logAndReport(fx, out, failure));
	}

	private static Function<Throwable, Void> logAndReport(Fx fx, Feedback out, String failure) {
		return ex -> {
			Throwable cause = unwrap(ex);
			if (cause instanceof RejectedExecutionException) {
				VouchMod.LOGGER.warn("{} ({})", failure, cause.getMessage());
			} else {
				VouchMod.LOGGER.error(failure, cause);
			}
			reportLater(fx, out, failure);
			return null;
		};
	}

	private static void reportLater(Fx fx, Feedback out, String message) {
		try {
			fx.mainThread().execute(() -> out.failure(Component.literal(message)));
		} catch (RejectedExecutionException ignored) {
		}
	}

	static Throwable unwrap(Throwable ex) {
		return (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
	}

	private VouchFlows() {}
}
