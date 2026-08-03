package dev.jensk.vouch;

import java.util.UUID;

public record PendingVouch(
		UUID targetId,
		String targetName,
		UUID voucherId,
		String voucherName,
		long createdAt) {

	public static PendingVouch of(UUID targetId, String targetName, Actor voucher) {
		return new PendingVouch(targetId, targetName, voucher.id(), voucher.name(), System.currentTimeMillis());
	}
}
