package dev.jensk.vouch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import net.minecraft.server.players.NameAndId;

final class FakeFx implements VouchFlows.Fx {

	final Set<UUID> whitelist = new HashSet<>();
	final List<PendingVouch> broadcasts = new ArrayList<>();
	final List<String> voucherNotifications = new ArrayList<>();
	boolean rejectMain = false;

	@Override
	public Executor mainThread() {
		return task -> {
			if (rejectMain) {
				throw new RejectedExecutionException("Server already shutting down");
			}
			task.run();
		};
	}

	@Override
	public boolean isWhitelisted(NameAndId target) {
		return whitelist.contains(target.id());
	}

	@Override
	public boolean addToWhitelist(NameAndId target) {
		return whitelist.add(target.id());
	}

	@Override
	public void broadcastPending(PendingVouch vouch) {
		broadcasts.add(vouch);
	}

	@Override
	public void notifyVoucher(PendingVouch vouch, boolean approved) {
		voucherNotifications.add(vouch.targetName() + ":" + (approved ? "approved" : "denied"));
	}
}
