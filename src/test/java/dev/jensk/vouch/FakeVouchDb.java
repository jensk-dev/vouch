package dev.jensk.vouch;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

final class FakeVouchDb implements VouchDb {

	final InMemoryStore store = new InMemoryStore();
	private final Deque<Runnable> queued = new ArrayDeque<>();
	boolean autoRun = true;

	@Override
	public <T> CompletableFuture<T> submit(DbTask<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		Runnable run = () -> {
			try {
				future.complete(task.run(store));
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		};
		if (autoRun) {
			run.run();
		} else {
			queued.add(run);
		}
		return future;
	}

	void runNext() {
		queued.removeFirst().run();
	}

	int queuedTasks() {
		return queued.size();
	}
}
