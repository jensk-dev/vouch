package dev.jensk.vouch;

import java.util.UUID;

public record AuditEvent(
		long timestamp,
		AuditType type,
		UUID actorId,
		String actorName,
		UUID targetId,
		String targetName,
		UUID voucherId,
		String voucherName) {

	public enum AuditType { INVITE, VOUCH, APPROVE, DENY }

	public static AuditEvent invite(Actor actor, UUID targetId, String targetName) {
		return of(AuditType.INVITE, actor, targetId, targetName, null, null);
	}

	public static AuditEvent vouch(Actor actor, PendingVouch entry) {
		return of(AuditType.VOUCH, actor, entry.targetId(), entry.targetName(), null, null);
	}

	public static AuditEvent approve(Actor actor, PendingVouch entry) {
		return of(AuditType.APPROVE, actor, entry.targetId(), entry.targetName(),
				entry.voucherId(), entry.voucherName());
	}

	public static AuditEvent deny(Actor actor, PendingVouch entry) {
		return of(AuditType.DENY, actor, entry.targetId(), entry.targetName(),
				entry.voucherId(), entry.voucherName());
	}

	private static AuditEvent of(AuditType type, Actor actor,
			UUID targetId, String targetName, UUID voucherId, String voucherName) {
		return new AuditEvent(
				System.currentTimeMillis(),
				type,
				actor.id(),
				actor.name(),
				targetId, targetName,
				voucherId, voucherName);
	}
}
