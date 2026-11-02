package net.vulkanmod;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.vulkanmod.visuals.dh.DhPresence;
import net.vulkanmod.visuals.dh.DhWorldGenGuard;

/**
 * The dedicated server half of Initializer (2026-11-02): only Distant Horizons' world generation fixes, for a server that generates its
 * players' LODs. The isolation mixins are common mixins; this holds DH's generator mode with Lost Cities (DhWorldGenGuard).
 */
public final class ServerInitializer {
	private ServerInitializer() {}

	static void register(IEventBus modEventBus) {
		modEventBus.addListener(ServerInitializer::onDedicatedServerSetup);
	}

	private static void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
		// DhWorldGenGuard names DH types, so it must not load without DH
		if (DhPresence.current() != DhPresence.State.ABSENT) DhWorldGenGuard.installServer();
	}
}
