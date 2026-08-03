package dev.jensk.vouch;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VouchDatabase implements VouchDb, AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(VouchDatabase.class);

	private static final long CLOSE_TIMEOUT_SECONDS = 10;
	private static final long FORCED_CLOSE_TIMEOUT_SECONDS = 5;

	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "vouch-db");
		t.setDaemon(true);
		return t;
	});

	private volatile DbSession session;

	private VouchDatabase() {
	}

	@Override
	public <T> CompletableFuture<T> submit(DbTask<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		try {
			executor.execute(() -> {
				try {
					future.complete(task.run(session));
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			});
		} catch (RejectedExecutionException e) {
			future.completeExceptionally(e);
		}
		return future;
	}

	public static VouchDatabase open(Path vouchDir) throws SQLException {
		VouchDatabase db = new VouchDatabase();
		CompletableFuture<Void> bootstrap = new CompletableFuture<>();
		try {
			db.executor.execute(() -> {
				try {
					db.session = DbSession.open(vouchDir);
					bootstrap.complete(null);
				} catch (Throwable t) {
					bootstrap.completeExceptionally(t);
				}
			});
			bootstrap.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			db.executor.shutdownNow();
			throw new SQLException("Interrupted while opening Vouch database", e);
		} catch (ExecutionException e) {
			db.executor.shutdownNow();
			Throwable cause = e.getCause();
			if (cause instanceof SQLException sql) {
				throw sql;
			}
			throw new SQLException("Failed to open Vouch database", cause);
		} catch (RejectedExecutionException e) {
			db.executor.shutdownNow();
			throw new SQLException("Failed to schedule Vouch database bootstrap", e);
		}
		return db;
	}

	@Override
	public void close() {
		executor.shutdown();
		boolean drained = false;

		try {
			drained = executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (!drained) {
			List<Runnable> dropped = executor.shutdownNow();
			LOG.warn("Vouch DB executor did not drain within {}s; {} queued task(s) discarded",
					CLOSE_TIMEOUT_SECONDS, dropped.size());

			boolean terminated = false;
			try {
				terminated = executor.awaitTermination(FORCED_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (!terminated) {
				LOG.warn("Vouch DB thread is still busy; leaving the connection open for process exit");
				return;
			}
		}

		DbSession s = session;

		if (s != null) {
			try {
				s.closeConnection();
			} catch (SQLException e) {
				LOG.warn("Error closing Vouch database", e);
			}
		}
	}
}
