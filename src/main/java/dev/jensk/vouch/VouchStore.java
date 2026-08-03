package dev.jensk.vouch;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface VouchStore {

	boolean recordVouch(PendingVouch vouch, AuditEvent audit) throws SQLException;

	boolean settlePending(UUID targetId, AuditEvent audit) throws SQLException;

	void recordInvite(UUID targetId, AuditEvent audit) throws SQLException;

	PendingVouch getPending(UUID targetId) throws SQLException;

	List<PendingVouch> findAllPendingByName(String name) throws SQLException;

	List<PendingVouch> allPending() throws SQLException;

	List<AuditEvent> readRecentAudit(int limit, UUID playerFilter) throws SQLException;

	boolean addPending(PendingVouch v) throws SQLException;

	boolean hasPending(UUID targetId) throws SQLException;

	boolean removePending(UUID targetId) throws SQLException;

	void appendAudit(AuditEvent e) throws SQLException;
}
