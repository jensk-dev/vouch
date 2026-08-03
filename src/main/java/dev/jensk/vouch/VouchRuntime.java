package dev.jensk.vouch;

import java.nio.file.Path;
import java.sql.SQLException;

final class VouchRuntime {

	private volatile VouchDatabase database;

	VouchDb db() {
		return database;
	}

	void start(Path dir) {
		try {
			database = VouchDatabase.open(dir);
			VouchMod.LOGGER.info("Vouch storage ready at {}", dir.resolve("vouch.db"));
		} catch (SQLException e) {
			database = null;
			VouchMod.LOGGER.error(
					"Failed to open the Vouch database in {}. /vouch commands are unavailable and /invite will not be audited",
					dir, e);
		}
	}

	void stop() {
		VouchDatabase db = database;
		database = null;
		if (db != null) {
			db.close();
		}
	}
}
