package dev.vulkanfix.compat;

/**
 * Whether a copy recorded into frame {@code requestSerial} has run. Reforged's beginFrame waits on the fence of the
 * frame slot it reuses, so once {@code framesNum} more frames have begun, that frame has finished on the GPU.
 */
public final class ReadbackSchedule {
    public enum State { NOT_SUBMITTED, SUBMITTED, COMPLETE }

    private ReadbackSchedule() {
    }

    public static State state(long requestSerial, long currentSerial, int framesNum) {
        if (currentSerial <= requestSerial) {
            return State.NOT_SUBMITTED;
        }
        return currentSerial - requestSerial >= framesNum ? State.COMPLETE : State.SUBMITTED;
    }
}
