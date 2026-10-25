package net.vulkanmod.visuals.framegen;

/**
 * The frame pacer's run state (FG design 3.1 point 6): STOPPED until the render thread starts it, RUNNING while the pacer thread
 * presents, STOPPING from the stop request until the render thread has joined the thread and drained its GPU work. A start is refused
 * unless STOPPED, so a pacer is never started twice or restarted before the previous run is fully drained. Synchronized: the transitions
 * also publish the render thread's setup to the pacer thread and back.
 */
final class PacerState {
    enum S { STOPPED, RUNNING, STOPPING }

    private S s = S.STOPPED;

    /** STOPPED to RUNNING; false (unchanged) in any other state. */
    synchronized boolean start() {
        if (this.s != S.STOPPED) return false;
        this.s = S.RUNNING;
        return true;
    }

    /** RUNNING to STOPPING; false when it was not running (a no-op). */
    synchronized boolean requestStop() {
        if (this.s != S.RUNNING) return false;
        this.s = S.STOPPING;
        return true;
    }

    /** The run is over and drained: STOPPED. */
    synchronized void stopped() {
        this.s = S.STOPPED;
    }

    synchronized boolean running() {
        return this.s == S.RUNNING;
    }
}
