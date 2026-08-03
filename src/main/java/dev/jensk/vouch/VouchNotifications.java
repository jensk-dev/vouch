package dev.jensk.vouch;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import me.lucko.fabric.api.permissions.v0.Permissions;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


public final class VouchNotifications {

	private static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	public static void broadcastPending(MinecraftServer server, PendingVouch v) {
		MutableComponent message = Component.literal(v.voucherName() + " vouched for ")
				.withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(v.targetName()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" — ").withStyle(ChatFormatting.GRAY))
				.append(approveButton(v))
				.append(Component.literal(" "))
				.append(denyButton(v));

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (Permissions.check(player, VouchPermissions.APPROVE, VouchPermissions.OP_LEVEL)) {
				player.sendSystemMessage(message);
			}
		}
		server.sendSystemMessage(message);
	}

	public static MutableComponent pendingLine(PendingVouch v) {
		return Component.literal("• ")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal(v.targetName()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" (by " + v.voucherName() + ") ").withStyle(ChatFormatting.GRAY))
				.append(approveButton(v))
				.append(Component.literal(" "))
				.append(denyButton(v));
	}

	public static MutableComponent auditLine(AuditEvent e) {
		ChatFormatting color = switch (e.type()) {
			case INVITE -> ChatFormatting.AQUA;
			case VOUCH -> ChatFormatting.YELLOW;
			case APPROVE -> ChatFormatting.GREEN;
			case DENY -> ChatFormatting.RED;
		};
		MutableComponent line = Component.literal("[" + TIMESTAMP.format(Instant.ofEpochMilli(e.timestamp())) + "] ")
				.withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(String.format("%-8s", e.type().name())).withStyle(color))
				.append(Component.literal(e.actorName() + " → " + e.targetName()).withStyle(ChatFormatting.WHITE));
		if (e.voucherName() != null) {
			line.append(Component.literal(" (vouched by " + e.voucherName() + ")").withStyle(ChatFormatting.GRAY));
		}
		return line;
	}

	private static MutableComponent approveButton(PendingVouch v) {
		return Component.literal("[Approve]").withStyle(style -> style
				.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.RunCommand("/vouch approve " + v.targetId()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Whitelist " + v.targetName()))));
	}

	private static MutableComponent denyButton(PendingVouch v) {
		return Component.literal("[Deny]").withStyle(style -> style
				.withColor(ChatFormatting.RED)
				.withClickEvent(new ClickEvent.RunCommand("/vouch deny " + v.targetId()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Reject " + v.targetName()))));
	}

	private VouchNotifications() {}
}
