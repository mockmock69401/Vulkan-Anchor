package net.vulkanmod.visuals.vk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which frames the GPU has finished, following Reforged's own synchronisation: a frame is complete once the fence of
 * the slot it was submitted on has been waited, or the device went idle. A frame that started recording but was never
 * submitted (swapchain recreation) is dropped: its commands never ran.
 */
public final class FrameTracker {
    private static final long FORGET_DROPPED_AFTER = 1024;

    private final Map<Integer, Long> submittedOnSlot = new HashMap<>();
    private final Set<Long> dropped = new HashSet<>();
    private long recording;
    private int recordingSlot = -1;
    private boolean recordingSubmitted = true;
    private long lastSubmitted;
    private long completed;

    /** Reforged began recording a frame on this fence slot (after waiting that slot's fence). Returns the frame's serial. */
    public long recordingStarted(int slot) {
        if (!this.recordingSubmitted) {
            this.dropped.add(this.recording);
        }
        this.recording++;
        this.recordingSlot = slot;
        this.recordingSubmitted = false;
        return this.recording;
    }

    /** The frame being recorded was submitted to the queue with its slot's fence. */
    public void submitted() {
        this.recordingSubmitted = true;
        this.submittedOnSlot.put(this.recordingSlot, this.recording);
        this.lastSubmitted = this.recording;
    }

    /** Reforged waited this slot's fence: whatever was last submitted on it has finished. */
    public void fenceWaited(int slot) {
        Long serial = this.submittedOnSlot.get(slot);
        if (serial != null) {
            this.advance(serial);
        }
    }

    /** The device went idle (swapchain recreation): everything submitted has finished; slot numbering restarts. */
    public void deviceIdle() {
        this.advance(this.lastSubmitted);
        this.submittedOnSlot.clear();
    }

    private void advance(long serial) {
        if (serial > this.completed) {
            this.completed = serial;
            this.dropped.removeIf(s -> s + FORGET_DROPPED_AFTER < this.completed);
        }
    }

    public int recordingSlot() {
        return this.recordingSlot;
    }

    public long recordingSerial() {
        return this.recording;
    }

    public long completedSerial() {
        return this.completed;
    }

    public boolean isDropped(long serial) {
        return this.dropped.contains(serial);
    }
}
