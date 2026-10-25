package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.api.VisualsExtensions;

/**
 * Hands DH the fork's render definition once DH has started, and frees DH's buffers when DH leaves the world. With DH 3.3.0 to
 * 3.3.4 (no render API) there is nothing to hand over: the legacy mixins take DH's own GL render definition over as DH binds it,
 * and DH's start activates the fork's side (installLegacy). This class loads with either DH (the shared DH classes ask it whether
 * drawing is off), so it names no render-API type: DhRenderDefinition does the handing over. While DH is not active the
 * section-layer guard keeps DH's hook from running and the world looks as it does without DH.
 * <p>
 * No level unload frees anything: DH unloads a client level 30 s after it was left (every dimension change, from its inactivity
 * cleanup on its own timer thread) and closes that level's buffers itself when the level's renderer stops; freeing every buffer
 * there would take the new dimension's LODs too.
 */
public final class DhRegistration {
    /** How a listener reaches DH (DhApi.events.bind); apart so the bindings are testable without DH running. */
    @SuppressWarnings("rawtypes")
    interface Binder {
        void bind(Class<? extends IDhApiEvent> type, IDhApiEvent listener);
    }

    private DhRegistration() {
    }

    /** A failure (a changed DH event API) leaves DH off with one warning; the game continues. */
    public static void install() {
        install((type, listener) -> DhApi.events.bind(type, listener));
    }

    static void install(Binder binder) {
        install(binder, DhRegistration::register);
    }

    /** DH 3.3.0 to 3.3.4: DH's start activates the fork's side directly (its render definition is already taken over). */
    public static void installLegacy() {
        install((type, listener) -> DhApi.events.bind(type, listener), DhRegistration::acceptLegacy);
    }

    private static void install(Binder binder, Runnable afterInit) {
        try {
            bindEvents(binder, afterInit);
        } catch (RuntimeException | LinkageError e) {
            onResult(false, e.toString());
        }
    }

    /** The world-exit free is bound first: when it cannot be bound, the init listener is not either and DH stays off. */
    private static void bindEvents(Binder binder, Runnable afterInit) {
        binder.bind(DhApiWorldUnloadEvent.class, new DhApiWorldUnloadEvent() {
            @Override
            public void onWorldUnload(DhApiEventParam<EventParam> input) {
                //A safety net for buffers DH did not close; any thread, the closes run on the render thread
                DhVertexBuffer.freeAll();
            }
        });
        binder.bind(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> input) {
                afterInit.run();
            }
        });
    }

    /** DH's worker thread, after DH started: the definition is set asynchronously, its result arrives on the render thread. */
    static void register() {
        try {
            DhRenderDefinition.offerToDh().whenComplete((result, error) -> {
                boolean accepted = error == null && result != null ? onResult(result.success, result.message)
                        : onResult(false, error != null ? error.toString() : "no result");
                if (accepted) activate(DhRegistration::activateSteps);
            });
        } catch (RuntimeException | LinkageError e) {
            onResult(false, e.toString());
        }
    }

    /** DH's worker thread, after DH 3.3.0 to 3.3.4 started: DH's hook runs from now on, through the taken-over definition. */
    static void acceptLegacy() {
        DhPresence.markActive(true);
        VisualsLog.info("Distant Horizons started with its render definition taken over by VulkanMod; DH's hook runs from now on");
        activate(DhRegistration::activateSteps);
    }

    /** Whether DH draws through the fork from now on; a refusal leaves DH off with one warning. */
    static boolean onResult(boolean success, String message) {
        if (success) {
            DhPresence.markActive(true);
            VisualsLog.info("Distant Horizons accepted the VulkanMod render definition; DH's hook runs from now on");
            return true;
        }
        VisualsLog.warn("Distant Horizons refused the VulkanMod render definition (" + message + "); the world is drawn without DH LODs");
        return false;
    }

    /**
     * The staging follows the frames and its copies are recorded in the visuals frame. A failure switches DH drawing off and gives
     * the section layer back to VulkanMod alone (the guard draws vanilla and keeps DH's hook from running).
     */
    static void activate(Runnable steps) {
        try {
            steps.run();
        } catch (RuntimeException | LinkageError e) {
            DhPresence.markActive(false);
            DhInternals.disable("activation failed (" + e + ")");
        }
    }

    /**
     * The frees freeAll hands over are run when a frame starts recording, and one line names what is read of DH outside its API.
     * Stage 3: DH's vanilla-graphics override is held off (ruling R22: it would turn the vanilla clouds off at DH's first frame) and
     * the fork's culling frustum is bound (the shadow casters need the sections out of the camera's view); both only warn when they fail.
     */
    private static void activateSteps() {
        DhVertexBuffer.installHandOver();
        DhInternals.logReads();
        DhStaging.INSTANCE.install();
        VisualsExtensions.register(DhExtension.INSTANCE);
        DhInternals.holdOffVanillaGraphicsOverride();
        DhCasterFrustum.bind();
        DhStats.start();
    }

    /** One source of truth: DH draws nothing once the internals it needs could not be read. */
    public static boolean disabled() {
        return !DhInternals.ok();
    }
}
