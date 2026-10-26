package net.vulkanmod;

import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsSettingsPage;
import net.vulkanmod.visuals.dev.VisualsPilot;
import net.vulkanmod.visuals.dh.DhPresence;
import net.vulkanmod.visuals.dh.DhRegistration;
import net.vulkanmod.compat.CompatBootstrap;
import net.vulkanmod.compat.CompatReport;
import net.vulkanmod.compat.RuntimeOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

@Mod("vulkanmod")
public class Initializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	private static String VERSION = "0.4.9-dev";
	public static Config CONFIG;

	static {

		try {
			Platform.init();
			VideoModeManager.init();
			Path configPath = Path.of("config", "vulkanmod_settings.json");
			CONFIG = loadConfig(configPath);
		} catch (Exception e) {
			CONFIG = new Config();
		}
		net.vulkanmod.render.texture.SpriteActivity.setEnabled(CONFIG.animateOnlyVisibleTextures);
	}

	// Forge 47's FMLModContainer only tries (FMLJavaModLoadingContext) and (); NeoForge uses the constructor below.
	public Initializer(FMLJavaModLoadingContext context) {
		this(context.getModEventBus(), ModLoadingContext.get().getActiveContainer());
	}

	public Initializer(IEventBus modEventBus, ModContainer modContainer) {
		VERSION = modContainer.getModInfo().getVersion().toString();
		modEventBus.addListener(this::onInitializeClient);
		modContainer.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory(
						(minecraft, parent) -> new VOptionScreen(Component.literal("VulkanMod Settings"), parent)));
	}

	private void onInitializeClient(FMLClientSetupEvent event) {
		LOGGER.info("== VulkanMod ==");
		VisualsConfig.load();
		VisualsSettingsPage.register();
		VisualsPilot.install();
		net.vulkanmod.render.profiling.FpsOverlay.install();
		// DhRegistration names DH types, so it must not load without DH
		switch (DhPresence.current()) {
			case RENDER_API -> DhRegistration.install();
			case LEGACY_RENDER_DEF -> DhRegistration.installLegacy();
			default -> { }
		}
		UpdateChecker.checkForUpdates();
		CompatBootstrap.init();
		if (RuntimeOptions.diagnosticsEnabled()) {
			CompatReport.logReport();
		}
		CompatReport.logRuntimeHints();
	}

	private static Config loadConfig(Path path) {
		Config config = Config.load(path);

		if(config == null) {
			config = new Config();
			config.write();
		}

		return config;
	}

	public static String getVersion() {
		return VERSION;
	}
}

