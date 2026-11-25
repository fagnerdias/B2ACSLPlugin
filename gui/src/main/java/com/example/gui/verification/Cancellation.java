package com.example.gui.verification;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cancellation token shared by a background task and the window that started it: {@link #cancel()}
 * kills every process started under it (with their descendants, e.g. the provers Frama-C runs).
 */
public final class Cancellation {

    private volatile boolean cancelled;
    private final Set<Process> running = ConcurrentHashMap.newKeySet();

    public void cancel() {
        cancelled = true;
        running.forEach(Cancellation::killTree);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    void register(Process process) {
        running.add(process);
        if (cancelled) {
            killTree(process);
        }
    }

    void unregister(Process process) {
        running.remove(process);
    }

    /** Kills the descendants first, so provers started by Frama-C (via why3server) do not survive. */
    static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
