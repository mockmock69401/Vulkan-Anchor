package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What DH's terrain callback hands over for one frame, kept until the visuals frame draws it (ruling R15: the callback runs
 * before the frame has recorded DH's staged copies, so nothing is drawn from it). DH calls twice a frame, opaque then
 * transparent, in its section-layer hook; the visuals frame takes the frame by its serial afterwards. A frame whose MC or DH
 * projection holds a NaN (the spike saw one on the first frame) is dropped whole. Render thread.
 */
public final class DhFrameCapture {
    public static final DhFrameCapture INSTANCE = new DhFrameCapture();

    private final DhCasterFrustum sections;

    /**
     * One pass's terrain containers as DH hands them over, whatever DH's version (DhTerrain's view of the render API's list,
     * LegacyDhTerrain's of DH 3.3.0 to 3.3.4's set). An entry may be null; its uniform and buffers count only when DH
     * created them through the fork (anything else is skipped).
     */
    public interface Containers {
        int size();

        /** DH's container itself, which DhInternals reads the corner of. */
        Object container(int i);

        Object uniform(int i);

        /** Its opaque or transparent vertex buffers; null for none. */
        Object[] buffers(int i, boolean opaquePass);
    }

    /**
     * One DH vertex buffer and the minimum corner (blocks) its u16 positions are relative to; whether DH's camera frustum sees its
     * section (DhCasterFrustum; in view when unknown). The buffer's own bounds are read when it is drawn (DhCulling).
     */
    public record Draw(DhVertexBuffer buffer, long originX, long originY, long originZ, boolean inView) {
        public Draw(DhVertexBuffer buffer, long originX, long originY, long originZ) {
            this(buffer, originX, originY, originZ, true);
        }
    }

    /** DH's projection already in Vulkan's 0..1 depth; near and far are DH's clip distances in blocks. */
    public record Frame(long serial, Matrix4f dhProjectionVk, Matrix4f dhModelView, float near, float far, int worldYOffset, List<Draw> opaque,
                        List<Draw> transparent) {}

    private Frame current;
    /** The current frame's lists behind its read-only views, and the last frame's sizes. */
    private ArrayList<Draw> opaqueOut, transparentOut;
    private int lastOpaque, lastTransparent;
    private long droppedSerial = Long.MIN_VALUE;
    /** The last serial take handed out; a call for it or an earlier one comes after its frame was drawn. */
    private long takenSerial = Long.MIN_VALUE;
    private long lateCalls;

    DhFrameCapture() {
        this(DhCasterFrustum.INSTANCE);
    }

    /** sections: what DH's culling frustum saw of each section this frame. */
    DhFrameCapture(DhCasterFrustum sections) {
        this.sections = sections;
    }

    /**
     * DH's callback: the draws of one pass for the frame being recorded. Records no command. Ruling R21: a call for a serial already
     * taken (Iris's deferred transparent pass runs after the visuals frame) is dropped and counted.
     */
    public synchronized void store(long serial, DhApiRenderParam param, boolean opaquePass, Containers containers) {
        if (param == null) return;
        if (serial <= this.takenSerial) {
            this.lateCalls++;
            return;
        }
        if (serial == this.droppedSerial) return;
        if (!DhDepth.finite(param.mcProjectionMatrix) || !DhDepth.finite(param.dhProjectionMatrix)) {
            this.droppedSerial = serial;
            if (this.current != null && this.current.serial() == serial) this.current = null;
            return;
        }
        if (this.current == null || this.current.serial() != serial) {
            //Sized as the last frame's lists, so they are not grown and copied on the way (2026-11-21: JFR, the 1% lows)
            ArrayList<Draw> opaque = new ArrayList<>(this.lastOpaque + 16), transparent = new ArrayList<>(this.lastTransparent + 16);
            this.current = new Frame(serial, DhDepth.toVulkan(DhDepth.fromDh(param.dhProjectionMatrix)), DhDepth.fromDh(param.dhModelViewMatrix),
                    param.nearClipPlane, param.farClipPlane, param.worldYOffset, Collections.unmodifiableList(opaque),
                    Collections.unmodifiableList(transparent));
            this.opaqueOut = opaque;
            this.transparentOut = transparent;
        }
        if (containers != null) collect(serial, containers, opaquePass, opaquePass ? this.opaqueOut : this.transparentOut);
        this.lastOpaque = this.opaqueOut.size();
        this.lastTransparent = this.transparentOut.size();
    }

    /**
     * The frame stored for serial, handed out once; null when there is none (another serial, dropped, or taken). 2026-11-21 (the user's
     * 1% lows): handed out as stored, without copying its lists: nothing here touches them again (a later call for the serial is
     * dropped, a new serial starts new lists), and the lists are read-only views.
     */
    public synchronized Frame take(long serial) {
        Frame f = this.current;
        if (f == null || f.serial() != serial) return null;
        this.current = null;
        this.takenSerial = serial;
        return f;
    }

    /** Calls dropped because their frame had already been taken (ruling R21). */
    public synchronized long lateCalls() {
        return this.lateCalls;
    }

    /**
     * DH core never fills a container's uniform wrapper (its GL renderer does, before a container's first draw), so the corner
     * is read here; a container whose corner cannot be read is skipped, and the first such failure turns DH drawing off.
     */
    private void collect(long serial, Containers containers, boolean opaquePass, List<Draw> out) {
        for (int i = 0; i < containers.size(); i++) {
            Object c = containers.container(i);
            if (c == null) continue;
            if (!(containers.uniform(i) instanceof DhContainerUniform corner)) continue;
            corner.tryUpload(c);
            if (!corner.valid()) {
                if (DhRegistration.disabled()) return;
                continue;
            }
            Object[] buffers = containers.buffers(i, opaquePass);
            if (buffers == null) continue;
            boolean inView = this.sections.find(corner.x(), corner.z(), corner.detailLevel(), serial) != DhCasterFrustum.OUT_OF_VIEW;
            for (Object w : buffers) {
                if (w instanceof DhVertexBuffer b) out.add(b.draw(corner.x(), corner.y(), corner.z(), inView));
            }
        }
    }
}
