package dev.jensk.vouch;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class InMemoryStore implements VouchStore {

	final Map<UUID, PendingVouch> pending = new LinkedHashMap<>();
	final List<AuditEvent> audit = new ArrayList<>();
	private SQLException nextFailure;

	void failNext(SQLException e) {
		nextFailure = e;
	}

	private void maybeFail() throws SQLException {
		if (nextFailure != null) {
			SQLException e = nextFailure;
			nextFailure = null;
			throw e;
		}
	}

	@Override
	public boolean recordVouch(PendingVouch vouch, AuditEvent auditEvent) throws SQLException {
		maybeFail();
		if (pending.containsKey(vouch.targetId())) {
			return false;
		}
		pending.put(vouch.targetId(), vouch);
		audit.add(auditEvent);
		return true;
	}

	@Override
	public boolean settlePending(UUID targetId, AuditEvent auditEvent) throws SQLException {
		maybeFail();
		if (pending.remove(targetId) == null) {
			return false;
		}
		audit.add(auditEvent);
		return true;
	}

	@Override
	public void recordInvite(UUID targetId, AuditEvent auditEvent) throws SQLException {
		maybeFail();
		pending.remove(targetId);
		audit.add(auditEvent);
	}

	@Override
	public PendingVouch getPending(UUID targetId) throws SQLException {
		maybeFail();
		return pending.get(targetId);
	}

	@Override
	public List<PendingVouch> findAllPendingByName(String name) throws SQLException {
		maybeFail();
		return pending.values().stream().filter(v -> v.targetName().equalsIgnoreCase(name)).toList();
	}

	@Override
	public List<PendingVouch> allPending() throws SQLException {
		maybeFail();
		return List.copyOf(pending.values());
	}

	@Override
	public List<AuditEvent> readRecentAudit(int limit, UUID playerFilter) throws SQLException {
		maybeFail();
		return audit.reversed().stream()
				.filter(e -> playerFilter == null
						|| playerFilter.equals(e.actorId())
						|| playerFilter.equals(e.targetId())
						|| playerFilter.equals(e.voucherId()))
				.limit(limit)
				.toList();
	}

	@Override
	public boolean addPending(PendingVouch v) throws SQLException {
		maybeFail();
		return pending.putIfAbsent(v.targetId(), v) == null;
	}

	@Override
	public boolean hasPending(UUID targetId) throws SQLException {
		maybeFail();
		return pending.containsKey(targetId);
	}

	@Override
	public boolean removePending(UUID targetId) throws SQLException {
		maybeFail();
		return pending.remove(targetId) != null;
	}

	@Override
	public void appendAudit(AuditEvent e) throws SQLException {
		maybeFail();
		audit.add(e);
	}
}
