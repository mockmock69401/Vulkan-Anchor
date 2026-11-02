package net.vulkanmod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.vulkanmod.visuals.dh.DhPresence;
import net.vulkanmod.visuals.dh.DhWorldGenGuard;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The dedicated server's entry point (2026-11-02): only Distant Horizons' world generation fixes, for a server that generates its
 * players' LODs. The isolation mixins are common mixins; this holds DH's generator mode with Lost Cities (DhWorldGenGuard).
 */
@Mod(value = "vulkanmod", dist = Dist.DEDICATED_SERVER)
public final class ServerInitializer {
	// Not Initializer.LOGGER: naming Initializer would load it, and its static block starts GLFW
	private static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	public ServerInitializer(IEventBus modEventBus) {
		LOGGER.info("VulkanMod renders on the client; on a dedicated server only Distant Horizons' world generation fixes run");
		modEventBus.addListener(ServerInitializer::onDedicatedServerSetup);
	}

	private static void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
		// DhWorldGenGuard names DH types, so it must not load without DH
		if (DhPresence.current() != DhPresence.State.ABSENT) DhWorldGenGuard.installServer();
	}
}
