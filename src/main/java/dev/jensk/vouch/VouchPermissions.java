package dev.jensk.vouch;

import net.minecraft.server.permissions.PermissionLevel;

public final class VouchPermissions {

	public static final String INVITE = "vouch.invite";
	public static final String VOUCH = "vouch.vouch";
	public static final String APPROVE = "vouch.approve";
	public static final String AUDIT = "vouch.audit";

	public static final PermissionLevel OP_LEVEL = PermissionLevel.GAMEMASTERS;
	public static final PermissionLevel MEMBER_LEVEL = PermissionLevel.ALL;

	private VouchPermissions() {}
}
