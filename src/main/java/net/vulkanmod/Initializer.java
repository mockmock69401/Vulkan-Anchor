package net.vulkanmod;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import net.vulkanmod.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//2026-11-02: a dedicated server constructs this class too, so it names no client type (InitializerDistTest); the client half is ClientInitializer
@Mod("vulkanmod")
public class Initializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	private static String VERSION = "0.4.9-dev";
	public static Config CONFIG;

	static {
		// The window mixins reach CONFIG before the mod is constructed; a dedicated server has no GLFW and never reads it
		if (FMLEnvironment.dist.isClient()) CONFIG = ClientInitializer.bootConfig();
	}

	// Forge 47's FMLModContainer only tries (FMLJavaModLoadingContext) and (); NeoForge uses the constructor below.
	public Initializer(FMLJavaModLoadingContext context) {
		this(context.getModEventBus(), ModLoadingContext.get().getActiveContainer());
	}

	public Initializer(IEventBus modEventBus, ModContainer modContainer) {
		VERSION = modContainer.getModInfo().getVersion().toString();
		if (FMLEnvironment.dist.isClient()) {
			ClientInitializer.register(modEventBus, modContainer);
		} else {
			LOGGER.info("VulkanMod renders on the client; on a dedicated server only Distant Horizons' world generation fixes run");
			ServerInitializer.register(modEventBus);
		}
	}

	public static String getVersion() {
		return VERSION;
	}
}
