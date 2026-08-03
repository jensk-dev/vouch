package dev.jensk.vouch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import me.lucko.fabric.api.permissions.v0.Permissions;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;

public final class VouchCommands {

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, VouchRuntime runtime) {
		dispatcher.register(Commands.literal("invite")
				.requires(Permissions.require(VouchPermissions.INVITE, VouchPermissions.OP_LEVEL))
				.then(Commands.argument("targets", StringArgumentType.greedyString())
						.executes(ctx -> invite(runtime, ctx.getSource(),
								StringArgumentType.getString(ctx, "targets")))));

		dispatcher.register(Commands.literal("vouch")
				.then(Commands.literal("for")
						.requires(Permissions.require(VouchPermissions.VOUCH, VouchPermissions.MEMBER_LEVEL))
						.then(Commands.argument("target", StringArgumentType.word())
								.executes(ctx -> vouchFor(runtime, ctx.getSource(),
										StringArgumentType.getString(ctx, "target")))))
				.then(Commands.literal("approve")
						.requires(Permissions.require(VouchPermissions.APPROVE, VouchPermissions.OP_LEVEL))
						.then(Commands.argument("target", StringArgumentType.word())
								.suggests(pendingSuggestions(runtime))
								.executes(ctx -> approve(runtime, ctx.getSource(),
										StringArgumentType.getString(ctx, "target")))))
				.then(Commands.literal("deny")
						.requires(Permissions.require(VouchPermissions.APPROVE, VouchPermissions.OP_LEVEL))
						.then(Commands.argument("target", StringArgumentType.word())
								.suggests(pendingSuggestions(runtime))
								.executes(ctx -> deny(runtime, ctx.getSource(),
										StringArgumentType.getString(ctx, "target")))))
				.then(Commands.literal("list")
						.requires(Permissions.require(VouchPermissions.APPROVE, VouchPermissions.OP_LEVEL))
						.executes(ctx -> list(runtime, ctx.getSource())))
				.then(Commands.literal("history")
						.requires(Permissions.require(VouchPermissions.AUDIT, VouchPermissions.OP_LEVEL))
						.executes(ctx -> history(runtime, ctx.getSource(), null))
						.then(Commands.argument("player", StringArgumentType.word())
								.executes(ctx -> history(runtime, ctx.getSource(),
										StringArgumentType.getString(ctx, "player"))))));
	}

	private static int invite(VouchRuntime runtime, CommandSourceStack source, String targetsArg) {
		List<String> names = List.of(targetsArg.trim().split("\\s+"));
		List<String> invalid = names.stream().filter(n -> !StringUtil.isValidPlayerName(n)).toList();
		if (!invalid.isEmpty()) {
			source.sendFailure(Component.literal("Not a valid player name: " + String.join(", ", invalid)));
			return 0;
		}

		MinecraftServer server = source.getServer();
		Actor actor = actor(source);
		resolveAll(server, names).thenAcceptAsync(resolved -> {
			List<NameAndId> found = new ArrayList<>();
			for (int i = 0; i < names.size(); i++) {
				if (resolved.get(i).isEmpty()) {
					source.sendFailure(Component.literal("Unknown player: " + names.get(i)));
				} else {
					found.add(resolved.get(i).get());
				}
			}
			if (!found.isEmpty()) {
				VouchFlows.invite(runtime.db(), fx(server), feedback(source), actor, found);
			}
		}, mainThread(server)).exceptionally(reportResolveFailure(server, source, targetsArg));
		return Command.SINGLE_SUCCESS;
	}

	private static int vouchFor(VouchRuntime runtime, CommandSourceStack source, String name) {
		VouchDb db = requireDb(runtime, source);
		if (db == null) {
			return 0;
		}
		if (!StringUtil.isValidPlayerName(name)) {
			source.sendFailure(Component.literal("Not a valid player name: " + name));
			return 0;
		}

		MinecraftServer server = source.getServer();
		Actor actor = actor(source);
		resolve(server, name).thenAcceptAsync(target -> {
			if (target.isEmpty()) {
				source.sendFailure(Component.literal("Unknown player: " + name));
				return;
			}
			VouchFlows.vouchFor(db, fx(server), feedback(source), actor, target.get());
		}, mainThread(server)).exceptionally(reportResolveFailure(server, source, name));
		return Command.SINGLE_SUCCESS;
	}

	private static int approve(VouchRuntime runtime, CommandSourceStack source, String nameOrId) {
		VouchDb db = requireDb(runtime, source);
		if (db == null) {
			return 0;
		}
		MinecraftServer server = source.getServer();
		VouchFlows.approve(db, fx(server), feedback(source), actor(source), nameOrId);
		return Command.SINGLE_SUCCESS;
	}

	private static int deny(VouchRuntime runtime, CommandSourceStack source, String nameOrId) {
		VouchDb db = requireDb(runtime, source);
		if (db == null) {
			return 0;
		}
		MinecraftServer server = source.getServer();
		VouchFlows.deny(db, fx(server), feedback(source), actor(source), nameOrId);
		return Command.SINGLE_SUCCESS;
	}

	private static int list(VouchRuntime runtime, CommandSourceStack source) {
		VouchDb db = requireDb(runtime, source);
		if (db == null) {
			return 0;
		}
		VouchFlows.list(db, fx(source.getServer()), feedback(source));
		return Command.SINGLE_SUCCESS;
	}

	private static int history(VouchRuntime runtime, CommandSourceStack source, String name) {
		VouchDb db = requireDb(runtime, source);
		if (db == null) {
			return 0;
		}
		MinecraftServer server = source.getServer();
		if (name == null) {
			VouchFlows.history(db, fx(server), feedback(source), null, null);
			return Command.SINGLE_SUCCESS;
		}
		if (!StringUtil.isValidPlayerName(name)) {
			source.sendFailure(Component.literal("Not a valid player name: " + name));
			return 0;
		}
		resolve(server, name).thenAcceptAsync(target -> {
			if (target.isEmpty()) {
				source.sendFailure(Component.literal("Unknown player: " + name));
				return;
			}
			VouchFlows.history(db, fx(server), feedback(source), target.get().id(), target.get().name());
		}, mainThread(server)).exceptionally(reportResolveFailure(server, source, name));
		return Command.SINGLE_SUCCESS;
	}

	private static CompletableFuture<Optional<NameAndId>> resolve(MinecraftServer server, String name) {
		return CompletableFuture.supplyAsync(
				() -> server.services().nameToIdCache().get(name), Util.ioPool());
	}

	private static CompletableFuture<List<Optional<NameAndId>>> resolveAll(MinecraftServer server, List<String> names) {
		List<CompletableFuture<Optional<NameAndId>>> futures =
				names.stream().map(name -> resolve(server, name)).toList();
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
				.thenApply(_ -> futures.stream().map(CompletableFuture::join).toList());
	}

	private static Function<Throwable, Void> reportResolveFailure(MinecraftServer server,
			CommandSourceStack source, String names) {
		return ex -> {
			VouchMod.LOGGER.error("Failed to resolve player name(s) '{}'", names, VouchFlows.unwrap(ex));
			try {
				mainThread(server).execute(() -> source.sendFailure(
						Component.literal("Could not look up '" + names + "' (profile lookup failed).")));
			} catch (RejectedExecutionException ignored) {
			}
			return null;
		};
	}

	private static Executor mainThread(MinecraftServer server) {
		return server::executeIfPossible;
	}

	private static VouchFlows.Fx fx(MinecraftServer server) {
		return new VouchFlows.Fx() {
			@Override
			public Executor mainThread() {
				return VouchCommands.mainThread(server);
			}

			@Override
			public boolean isWhitelisted(NameAndId target) {
				return server.getPlayerList().getWhiteList().isWhiteListed(target);
			}

			@Override
			public boolean addToWhitelist(NameAndId target) {
				UserWhiteList whitelist = server.getPlayerList().getWhiteList();
				if (whitelist.isWhiteListed(target)) {
					return false;
				}
				whitelist.add(new UserWhiteListEntry(target));
				return true;
			}

			@Override
			public void broadcastPending(PendingVouch vouch) {
				VouchNotifications.broadcastPending(server, vouch);
			}

			@Override
			public void notifyVoucher(PendingVouch vouch, boolean approved) {
				if (vouch.voucherId() == null) {
					return;
				}
				ServerPlayer voucher = server.getPlayerList().getPlayer(vouch.voucherId());
				if (voucher != null) {
					voucher.sendSystemMessage(Component.literal(
							"Your vouch for " + vouch.targetName()
									+ (approved ? " was approved." : " was denied."))
							.withStyle(approved ? ChatFormatting.GREEN : ChatFormatting.RED));
				}
			}
		};
	}

	private static VouchFlows.Feedback feedback(CommandSourceStack source) {
		return new VouchFlows.Feedback() {
			@Override
			public void success(Component message, boolean notifyOps) {
				source.sendSuccess(() -> message, notifyOps);
			}

			@Override
			public void failure(Component message) {
				source.sendFailure(message);
			}
		};
	}

	private static Actor actor(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return new Actor(player == null ? null : player.getUUID(), source.getTextName());
	}

	private static VouchDb requireDb(VouchRuntime runtime, CommandSourceStack source) {
		VouchDb db = runtime.db();
		if (db == null) {
			source.sendFailure(Component.literal("Vouch storage is unavailable — see the server log."));
		}
		return db;
	}

	private static SuggestionProvider<CommandSourceStack> pendingSuggestions(VouchRuntime runtime) {
		return (ctx, builder) -> {
			VouchDb db = runtime.db();
			if (db == null) {
				return builder.buildFuture();
			}
			MinecraftServer server = ctx.getSource().getServer();
			return db.submit(VouchStore::allPending)
					.thenApplyAsync(pending -> {
						for (PendingVouch v : pending) {
							builder.suggest(v.targetName());
						}
						return builder.build();
					}, mainThread(server))
					.exceptionally(ex -> {
						Throwable cause = VouchFlows.unwrap(ex);
						if (!(cause instanceof RejectedExecutionException)) {
							VouchMod.LOGGER.error("Failed to load vouch suggestions", cause);
						}
						return builder.build();
					});
		};
	}

	private VouchCommands() {}
}
