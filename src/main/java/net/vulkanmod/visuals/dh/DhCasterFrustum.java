package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.vk.VkFrames;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The camera frustum DH culls its render list by (bound through DH's override API). DH's own frustum keeps only what the camera
 * sees, but a mountain behind the camera still shades the ground in front of it, and the sun's cascades reach past the view: so
 * this frustum keeps every section in DH's list and remembers, per section, whether DH's own frustum (its core binding) sees it.
 * A section is its minimum corner and its detail level (ruling R26a: a parent and its first child share their corner). The capture
 * tags each draw with that; the main view skips the ones out of view (DhCulling, which also culls by each buffer's own box).
 * Render thread: DH builds its list there, in its hook, before the terrain callback.
 */
final class DhCasterFrustum implements IDhApiCullingFrustum {
    static final DhCasterFrustum INSTANCE = new DhCasterFrustum(DhCasterFrustum::coreFrustum, VkFrames::serial);
    /** find's answers. */
    static final int UNKNOWN = -1, OUT_OF_VIEW = 0, IN_VIEW = 1;
    /** Detail levels the answers are kept for: two bits each (asked, in view) in a long. DH's sections run from 6 to its root's. */
    static final int DETAIL_LEVELS = 32;

    private final Supplier<IDhApiCullingFrustum> core;
    private final LongSupplier serial;
    /** The frame the answers below are from: DH asks only while its frustum culling is on (its config can turn it off). */
    private long updatedSerial = Long.MIN_VALUE;
    /** This frame's sections: key (min x, min z) to two bits per detail level (asked, in view). */
    private final Long2LongOpenHashMap seen = new Long2LongOpenHashMap();
    private IDhApiCullingFrustum delegate;

    DhCasterFrustum(Supplier<IDhApiCullingFrustum> core, LongSupplier serial) {
        this.core = core;
        this.serial = serial;
    }

    private static IDhApiCullingFrustum coreFrustum() {
        return DhApi.overrides.get(IDhApiCullingFrustum.class, IOverrideInjector.CORE_PRIORITY);
    }

    /** At activation: DH asks this frustum from now on. A refusal leaves DH's own (shadows then come from the camera's sections only). */
    static void bind() {
        try {
            DhApi.overrides.bind(IDhApiCullingFrustum.class, INSTANCE);
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Distant Horizons: the VulkanMod culling frustum was not bound (" + e + "); LODs out of the camera's view cast no sun shadows");
        }
    }

    static long key(long minX, long minZ) {
        return (minX << 32) ^ (minZ & 0xFFFFFFFFL);
    }

    /** The two bits (asked, in view) of a detail level in a key's value; 0 for a level past DETAIL_LEVELS. */
    static long bits(int detailLevel, boolean inView) {
        if (detailLevel < 0 || detailLevel >= DETAIL_LEVELS) return 0;
        return (inView ? 3L : 1L) << (2 * detailLevel);
    }

    /** Once a frame, before DH asks about its sections: the last frame's are forgotten, DH's own frustum is updated too. */
    @Override
    public void update(int worldMinBlockY, int worldMaxBlockY, DhApiMat4f worldViewProjection) {
        this.seen.clear();
        this.updatedSerial = this.serial.getAsLong();
        IDhApiCullingFrustum d;
        try {
            d = this.core.get();
        } catch (RuntimeException | LinkageError e) {
            d = null;
        }
        this.delegate = d == this ? null : d;
        if (this.delegate != null) {
            try {
                this.delegate.update(worldMinBlockY, worldMaxBlockY, worldViewProjection);
            } catch (RuntimeException e) {
                this.delegate = null;
            }
        }
    }

    /** Keeps every section; notes whether DH's own frustum sees it (without one, or when it throws, it counts as seen). */
    @Override
    public boolean intersects(int lodBlockPosMinX, int lodBlockPosMinZ, int lodBlockWidth, int lodDetailLevel) {
        boolean inView = true;
        if (this.delegate != null) {
            try {
                inView = this.delegate.intersects(lodBlockPosMinX, lodBlockPosMinZ, lodBlockWidth, lodDetailLevel);
            } catch (RuntimeException e) {
                inView = true;
            }
        }
        long b = bits(lodDetailLevel, inView);
        if (b != 0) {
            long k = key(lodBlockPosMinX, lodBlockPosMinZ);
            long shift = 2L * lodDetailLevel;
            this.seen.put(k, this.seen.get(k) & ~(3L << shift) | b);
        }
        return true;
    }

    /**
     * IN_VIEW or OUT_OF_VIEW for the section with that minimum corner and detail level in frame serial; UNKNOWN when DH did not ask
     * about it then (or did not ask this frustum at all that frame, or the level is unknown, -1), and the draw then counts as in view.
     */
    int find(long minX, long minZ, int detailLevel, long serial) {
        if (serial != this.updatedSerial || detailLevel < 0 || detailLevel >= DETAIL_LEVELS) return UNKNOWN;
        long v = this.seen.get(key(minX, minZ)) >>> (2 * detailLevel);
        if ((v & 1) == 0) return UNKNOWN;
        return (v & 2) != 0 ? IN_VIEW : OUT_OF_VIEW;
    }
}
