package net.vulkanmod;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
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
import net.vulkanmod.compat.sable.flywheel.VulkanFlywheelBootstrap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

@Mod("vulkanmod")
public class Initializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	private static String VERSION = "0.3.5-ALPHA+1.21.1";
	public static Config CONFIG;

	static {

		try {
			Path configPath = Path.of("config", "vulkanmod_settings.json");
			Platform.init(Config.readWaylandPreference(configPath));
			VideoModeManager.init();
			CONFIG = loadConfig(configPath);
		} catch (Exception e) {
			CONFIG = new Config();
		}
		net.vulkanmod.render.texture.SpriteActivity.setEnabled(CONFIG.animateOnlyVisibleTextures);
	}

	public Initializer(IEventBus modEventBus, ModContainer modContainer) {
		VERSION = modContainer.getModInfo().getVersion().toString();

		VulkanFlywheelBootstrap.registerIfPresent();
		modEventBus.addListener(this::onInitializeClient);
		modContainer.registerExtensionPoint(IConfigScreenFactory.class,
				(java.util.function.Supplier<IConfigScreenFactory>) () ->
						(container, parent) -> new VOptionScreen(Component.literal("VulkanMod Settings"), parent));
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
		// With Lost Cities, DH's distant generator mode held at INTERNAL_SERVER (names DH types: only with DH)
		if (DhPresence.current() != DhPresence.State.ABSENT) net.vulkanmod.visuals.dh.DhWorldGenGuard.install();
		UpdateChecker.checkForUpdates();
		event.enqueueWork(() -> {
			CompatBootstrap.init();
			if (RuntimeOptions.diagnosticsEnabled()) {
				CompatReport.logReport();
			}
			CompatReport.logRuntimeHints();
		});
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
