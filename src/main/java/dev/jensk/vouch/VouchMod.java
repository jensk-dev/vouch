package dev.jensk.vouch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public class VouchMod implements DedicatedServerModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger("vouch");

	private static volatile VouchRuntime runtime;

	public static VouchDb db() {
		VouchRuntime rt = runtime;
		return rt == null ? null : rt.db();
	}

	@Override
	public void onInitializeServer() {
		VouchRuntime rt = new VouchRuntime();
		runtime = rt;

		ServerLifecycleEvents.SERVER_STARTING.register(_ ->
				rt.start(FabricLoader.getInstance().getConfigDir().resolve("vouch")));

		ServerLifecycleEvents.SERVER_STOPPED.register(_ -> rt.stop());

		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) ->
				VouchCommands.register(dispatcher, rt));
	}
}
