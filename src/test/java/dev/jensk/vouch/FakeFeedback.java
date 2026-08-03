package dev.jensk.vouch;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

final class FakeFeedback implements VouchFlows.Feedback {

	final List<String> successes = new ArrayList<>();
	final List<String> failures = new ArrayList<>();

	@Override
	public void success(Component message, boolean notifyOps) {
		successes.add(message.getString());
	}

	@Override
	public void failure(Component message) {
		failures.add(message.getString());
	}

	boolean anyFailureContains(String fragment) {
		return failures.stream().anyMatch(f -> f.contains(fragment));
	}

	boolean anySuccessContains(String fragment) {
		return successes.stream().anyMatch(s -> s.contains(fragment));
	}
}
