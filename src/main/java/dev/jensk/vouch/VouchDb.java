package dev.jensk.vouch;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

public interface VouchDb {

	<T> CompletableFuture<T> submit(DbTask<T> task);

	@FunctionalInterface
	interface DbTask<T> {
		T run(VouchStore store) throws SQLException;
	}
}
