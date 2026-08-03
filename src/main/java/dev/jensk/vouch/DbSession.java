package dev.jensk.vouch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DbSession implements VouchStore {

	private static final Logger LOG = LoggerFactory.getLogger(DbSession.class);

	private static final int SCHEMA_VERSION = 1;

	private final Connection connection;
	private final Thread owner;

	private DbSession(Connection connection) {
		this.connection = connection;
		this.owner = Thread.currentThread();
	}

	static DbSession open(Path vouchDir) throws SQLException {
		try {
			Files.createDirectories(vouchDir);
		} catch (IOException e) {
			throw new SQLException("Could not create vouch data directory " + vouchDir, e);
		}

		Path dbFile = vouchDir.resolve("vouch.db").toAbsolutePath();
		Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + dbFile, new Properties());

		if (c == null) {
			throw new SQLException("SQLite driver returned no connection for " + dbFile);
		}

		try {
			DbSession session = new DbSession(c);
			session.applyPragmas();
			session.migrate();
			return session;
		} catch (SQLException e) {
			try {
				c.close();
			} catch (SQLException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw e;
		}
	}

	void closeConnection() throws SQLException {
		connection.close();
	}

	private void assertOwnerThread() {
		Thread current = Thread.currentThread();
		if (current != owner) {
			throw new IllegalStateException(
					"DbSession is confined to '" + owner.getName()
							+ "' but was accessed from '" + current.getName() + "'");
		}
	}

	private void applyPragmas() throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("PRAGMA synchronous=NORMAL");
			st.execute("PRAGMA busy_timeout=5000");
		}
	}

	private void migrate() throws SQLException {
		int version = readUserVersion();
		if (version > SCHEMA_VERSION) {
			throw new SQLException("vouch.db has schema version " + version + " but this build supports at most "
					+ SCHEMA_VERSION + " — was the mod downgraded?");
		}
		if (version == SCHEMA_VERSION) {
			return;
		}
		inTransaction(() -> {
			if (version < 1) {
				createSchemaV1();
			}
			exec("PRAGMA user_version = " + SCHEMA_VERSION);
			return null;
		});
	}

	private int readUserVersion() throws SQLException {
		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("PRAGMA user_version")) {
			return rs.next() ? rs.getInt(1) : 0;
		}
	}

	private void createSchemaV1() throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.execute("""
					CREATE TABLE IF NOT EXISTS pending_vouches (
						target_id    TEXT PRIMARY KEY,
						target_name  TEXT NOT NULL,
						voucher_id   TEXT,
						voucher_name TEXT NOT NULL,
						created_at   INTEGER NOT NULL
					)""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS audit_events (
						id           INTEGER PRIMARY KEY,
						ts           INTEGER NOT NULL,
						type         TEXT NOT NULL,
						actor_id     TEXT,
						actor_name   TEXT NOT NULL,
						target_id    TEXT NOT NULL,
						target_name  TEXT NOT NULL,
						voucher_id   TEXT,
						voucher_name TEXT
					)""");
			st.execute("CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_events (ts)");
		}
	}

	private void exec(String sql) throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.execute(sql);
		}
	}

	@FunctionalInterface
	private interface TxBody<T> {
		T run() throws SQLException;
	}

	private <T> T inTransaction(TxBody<T> body) throws SQLException {
		exec("BEGIN IMMEDIATE");
		try {
			T result = body.run();
			exec("COMMIT");
			return result;
		} catch (Throwable t) {
			try {
				exec("ROLLBACK");
			} catch (SQLException rollback) {
				t.addSuppressed(rollback);
			}
			throw t;
		}
	}

	@Override
	public boolean recordVouch(PendingVouch vouch, AuditEvent audit) throws SQLException {
		assertOwnerThread();
		return inTransaction(() -> {
			if (!insertPendingIfAbsent(vouch)) {
				return false;
			}
			insertAudit(audit);
			return true;
		});
	}

	@Override
	public boolean settlePending(UUID targetId, AuditEvent audit) throws SQLException {
		assertOwnerThread();
		return inTransaction(() -> {
			if (!deletePending(targetId)) {
				return false;
			}
			insertAudit(audit);
			return true;
		});
	}

	@Override
	public void recordInvite(UUID targetId, AuditEvent audit) throws SQLException {
		assertOwnerThread();
		inTransaction(() -> {
			deletePending(targetId);
			insertAudit(audit);
			return null;
		});
	}

	@Override
	public boolean addPending(PendingVouch v) throws SQLException {
		assertOwnerThread();
		return insertPendingIfAbsent(v);
	}

	private boolean insertPendingIfAbsent(PendingVouch v) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO pending_vouches (target_id, target_name, voucher_id, voucher_name, created_at)"
						+ " VALUES (?, ?, ?, ?, ?) ON CONFLICT (target_id) DO NOTHING")) {
			ps.setString(1, v.targetId().toString());
			ps.setString(2, v.targetName());
			ps.setString(3, v.voucherId() == null ? null : v.voucherId().toString());
			ps.setString(4, v.voucherName());
			ps.setLong(5, v.createdAt());
			return ps.executeUpdate() > 0;
		}
	}

	@Override
	public boolean hasPending(UUID targetId) throws SQLException {
		assertOwnerThread();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT 1 FROM pending_vouches WHERE target_id = ?")) {
			ps.setString(1, targetId.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	@Override
	public PendingVouch getPending(UUID targetId) throws SQLException {
		assertOwnerThread();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT target_id, target_name, voucher_id, voucher_name, created_at"
						+ " FROM pending_vouches WHERE target_id = ?")) {
			ps.setString(1, targetId.toString());
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? readPendingOrNull(rs) : null;
			}
		}
	}

	@Override
	public List<PendingVouch> findAllPendingByName(String name) throws SQLException {
		assertOwnerThread();
		List<PendingVouch> out = new ArrayList<>();

		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT target_id, target_name, voucher_id, voucher_name, created_at"
						+ " FROM pending_vouches WHERE target_name = ? COLLATE NOCASE ORDER BY created_at ASC")) {
			ps.setString(1, name);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					PendingVouch v = readPendingOrNull(rs);
					if (v != null) {
						out.add(v);
					}
				}
			}
		}

		return out;
	}

	@Override
	public boolean removePending(UUID targetId) throws SQLException {
		assertOwnerThread();
		return deletePending(targetId);
	}

	private boolean deletePending(UUID targetId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"DELETE FROM pending_vouches WHERE target_id = ?")) {
			ps.setString(1, targetId.toString());
			return ps.executeUpdate() > 0;
		}
	}

	@Override
	public List<PendingVouch> allPending() throws SQLException {
		assertOwnerThread();
		List<PendingVouch> out = new ArrayList<>();

		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT target_id, target_name, voucher_id, voucher_name, created_at"
						+ " FROM pending_vouches ORDER BY created_at ASC");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				PendingVouch v = readPendingOrNull(rs);
				if (v != null) {
					out.add(v);
				}
			}
		}

		return out;
	}

	private static PendingVouch readPendingOrNull(ResultSet rs) throws SQLException {
		String targetId = rs.getString("target_id");
		try {
			String voucherId = rs.getString("voucher_id");
			return new PendingVouch(
					UUID.fromString(targetId),
					rs.getString("target_name"),
					voucherId == null ? null : UUID.fromString(voucherId),
					rs.getString("voucher_name"),
					rs.getLong("created_at"));
		} catch (RuntimeException e) {
			LOG.warn("Skipping unreadable pending vouch row (target_id={})", targetId, e);
			return null;
		}
	}

	@Override
	public void appendAudit(AuditEvent e) throws SQLException {
		assertOwnerThread();
		insertAudit(e);
	}

	private void insertAudit(AuditEvent e) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO audit_events (ts, type, actor_id, actor_name, target_id, target_name, voucher_id, voucher_name)"
						+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
			ps.setLong(1, e.timestamp());
			ps.setString(2, e.type().name());
			ps.setString(3, e.actorId() == null ? null : e.actorId().toString());
			ps.setString(4, e.actorName());
			ps.setString(5, e.targetId().toString());
			ps.setString(6, e.targetName());
			ps.setString(7, e.voucherId() == null ? null : e.voucherId().toString());
			ps.setString(8, e.voucherName());
			ps.executeUpdate();
		}
	}

	@Override
	public List<AuditEvent> readRecentAudit(int limit, UUID playerFilter) throws SQLException {
		assertOwnerThread();
		StringBuilder sql = new StringBuilder(
				"SELECT id, ts, type, actor_id, actor_name, target_id, target_name, voucher_id, voucher_name FROM audit_events");

		if (playerFilter != null) {
			sql.append(" WHERE actor_id = ? OR target_id = ? OR voucher_id = ?");
		}

		sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");

		try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
			int i = 1;
			if (playerFilter != null) {
				String f = playerFilter.toString();
				ps.setString(i++, f);
				ps.setString(i++, f);
				ps.setString(i++, f);
			}
			ps.setInt(i, limit);
			List<AuditEvent> out = new ArrayList<>();
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					AuditEvent e = readAuditOrNull(rs);
					if (e != null) {
						out.add(e);
					}
				}
			}
			return out;
		}
	}

	private static AuditEvent readAuditOrNull(ResultSet rs) throws SQLException {
		long id = rs.getLong("id");
		try {
			String actorId = rs.getString("actor_id");
			String voucherId = rs.getString("voucher_id");
			return new AuditEvent(
					rs.getLong("ts"),
					AuditEvent.AuditType.valueOf(rs.getString("type")),
					actorId == null ? null : UUID.fromString(actorId),
					rs.getString("actor_name"),
					UUID.fromString(rs.getString("target_id")),
					rs.getString("target_name"),
					voucherId == null ? null : UUID.fromString(voucherId),
					rs.getString("voucher_name"));
		} catch (RuntimeException e) {
			LOG.warn("Skipping unreadable audit row (id={})", id, e);
			return null;
		}
	}
}
