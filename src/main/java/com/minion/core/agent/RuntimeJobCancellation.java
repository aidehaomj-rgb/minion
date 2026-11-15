package com.minion.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Event-driven cancellation of a wait, NOT cancellation/resubmission of an external job. */
public final class RuntimeJobCancellation {
    public interface Registration extends AutoCloseable { @Override void close(); }
    private final Set<Runnable> listeners = new LinkedHashSet<Runnable>();
    private volatile boolean cancelled;

    public boolean isCancelled() { return cancelled; }

    public void cancel() {
        ArrayList<Runnable> callbacks;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            callbacks = new ArrayList<Runnable>(listeners);
            listeners.clear();
        }
        // Never invoke under the token monitor: the listener may acquire a registry monitor.
        for (Runnable callback : callbacks) try { callback.run(); } catch (RuntimeException ignored) { /* Wake the remaining independent waiters too. */ }
    }

    public Registration onCancel(final Runnable callback) {
        if (callback == null) throw new NullPointerException("callback");
        boolean invoke;
        synchronized (this) { invoke = cancelled; if (!invoke) listeners.add(callback); }
        if (invoke) callback.run();
        return () -> { synchronized (RuntimeJobCancellation.this) { listeners.remove(callback); } };
    }
}
