package net.vulkanmod;

import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.ModContainer;
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

import java.nio.file.Path;

import static net.vulkanmod.Initializer.LOGGER;

/** The client half of Initializer: GLFW, the config and every client listener. Initializer calls it only on the client. */
public final class ClientInitializer {
	private ClientInitializer() {}

	static Config bootConfig() {
		Config config;
		try {
			Platform.init();
			VideoModeManager.init();
			Path configPath = Path.of("config", "vulkanmod_settings.json");
			config = loadConfig(configPath);
		} catch (Exception e) {
			config = new Config();
		}
		net.vulkanmod.render.texture.SpriteActivity.setEnabled(config.animateOnlyVisibleTextures);
		return config;
	}

	static void register(IEventBus modEventBus, ModContainer modContainer) {
		modEventBus.addListener(ClientInitializer::onInitializeClient);
		//2026-10-29: Refueled's road markers off the road's depth (RoadMarkerLift)
		modEventBus.addListener(net.vulkanmod.compat.render.RoadMarkerLift::onModifyBakingResult);
		modContainer.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory(
						(minecraft, parent) -> new VOptionScreen(Component.literal("VulkanMod Settings"), parent)));
	}

	private static void onInitializeClient(FMLClientSetupEvent event) {
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
		// With Lost Cities, DH's distant generator mode held at INTERNAL_SERVER (names DH types: only with DH)
		if (DhPresence.current() != DhPresence.State.ABSENT) net.vulkanmod.visuals.dh.DhWorldGenGuard.install();
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
}
