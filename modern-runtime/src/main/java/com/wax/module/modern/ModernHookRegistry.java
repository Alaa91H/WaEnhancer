package com.wax.module.modern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-process API-agnostic hook lifecycle registry.
 *
 * Each feature owns its hooks; a failed group is rolled back in reverse order, without
 * touching hooks belonging to another feature. Never share a registry across processes.
 */
public final class ModernHookRegistry {
    @FunctionalInterface
    public interface Handle {
        void unhook();
    }

    @FunctionalInterface
    public interface Installer {
        Handle install() throws Throwable;
    }

    public static final class Registration {
        public final String id;
        public final Installer installer;

        public Registration(String id, Installer installer) {
            this.id = requireId(id);
            this.installer = Objects.requireNonNull(installer, "installer");
        }
    }

    private final Map<String, LinkedHashMap<String, Handle>> installed = new LinkedHashMap<>();
    /** A failed rollback leaves a tracked but NOT healthy handle until cleanup succeeds. */
    private final Set<String> incompleteFeatures = new LinkedHashSet<>();

    private static String requireId(String value) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Hook ID must be nonblank and stable");
        }
        return value;
    }

    private void requireUnclaimedId(String owner, String id) {
        for (Map.Entry<String, LinkedHashMap<String, Handle>> feature : installed.entrySet()) {
            if (!feature.getKey().equals(owner) && feature.getValue().containsKey(id)) {
                throw new IllegalArgumentException("Hook ID already claimed by " + feature.getKey() + ": " + id);
            }
        }
    }

    public synchronized boolean installOnce(String featureId, Registration hook) throws Throwable {
        String feature = requireId(featureId);
        if (incompleteFeatures.contains(feature)) {
            throw new IllegalStateException("Feature has incomplete rollback: " + feature);
        }
        Objects.requireNonNull(hook, "hook");
        requireUnclaimedId(feature, hook.id);
        LinkedHashMap<String, Handle> previous = installed.get(feature);
        if (previous != null && previous.containsKey(hook.id)) {
            return false;
        }
        Handle handle = Objects.requireNonNull(hook.installer.install(), "hook handle");
        installed.computeIfAbsent(feature, ignored -> new LinkedHashMap<>()).put(hook.id, handle);
        return true;
    }

    /**
     * Install an entire feature atomically. Reject collisions before invoking any callbacks.
     * A failure unhooks only the new handles, keeping previously installed features intact.
     */
    public synchronized int installFeature(String featureId, List<Registration> hooks) throws Throwable {
        String feature = requireId(featureId);
        if (incompleteFeatures.contains(feature)) {
            throw new IllegalStateException("Feature has incomplete rollback: " + feature);
        }
        Objects.requireNonNull(hooks, "hooks");
        Set<String> ids = new LinkedHashSet<>();
        LinkedHashMap<String, Handle> existing = installed.get(feature);
        int previouslyInstalled = 0;
        for (Registration reg : hooks) {
            Objects.requireNonNull(reg, "registration");
            requireUnclaimedId(feature, reg.id);
            if (!ids.add(reg.id)) {
                throw new IllegalArgumentException("Duplicate hook ID in feature request: " + reg.id);
            }
            if (existing != null && existing.containsKey(reg.id)) {
                previouslyInstalled++;
            }
        }
        // A repeated, complete registration is an idempotent no-op: never
        // hook a method twice just because the target bootstrap retried.
        // Reject mixed old/new requests before invoking ANY new installer.
        if (previouslyInstalled == ids.size() && previouslyInstalled > 0) {
            return 0;
        }
        if (previouslyInstalled > 0) {
            throw new IllegalArgumentException("Partially installed hook group for " + feature);
        }

        LinkedHashMap<String, Handle> acquired = new LinkedHashMap<>();
        try {
            for (Registration hook : hooks) {
                acquired.put(hook.id, Objects.requireNonNull(hook.installer.install(), "hook handle"));
            }
        } catch (Throwable failure) {
            List<Map.Entry<String, Handle>> reverse = new ArrayList<>(acquired.entrySet());
            Collections.reverse(reverse);
            LinkedHashMap<String, Handle> residual = new LinkedHashMap<>();
            for (Map.Entry<String, Handle> hook : reverse) {
                try {
                    hook.getValue().unhook();
                } catch (Throwable rollbackFailure) {
                    residual.put(hook.getKey(), hook.getValue());
                    failure.addSuppressed(rollbackFailure);
                }
            }
            // Even a failed unhook must remain tracked and retryable, never silently leaked.
            if (!residual.isEmpty()) {
                installed.computeIfAbsent(feature, ignored -> new LinkedHashMap<>()).putAll(residual);
                incompleteFeatures.add(feature);
            }
            throw failure;
        }

        if (!acquired.isEmpty()) {
            installed.computeIfAbsent(feature, ignored -> new LinkedHashMap<>()).putAll(acquired);
        }
        return acquired.size();
    }

    public synchronized int installedCount(String featureId) {
        Map<String, Handle> feature = installed.get(requireId(featureId));
        return feature == null ? 0 : feature.size();
    }

    /** Remove hooks for one feature in reverse install order. A failed unhook stays tracked. */
    public synchronized void removeFeature(String featureId) {
        String feature = requireId(featureId);
        LinkedHashMap<String, Handle> handles = installed.get(feature);
        if (handles == null) return;
        List<String> reverse = new ArrayList<>(handles.keySet());
        Collections.reverse(reverse);
        RuntimeException failed = null;
        for (String key : reverse) {
            try {
                handles.get(key).unhook();
                handles.remove(key);
            } catch (Throwable cause) {
                if (failed == null) failed = new IllegalStateException("Could not remove all hooks from " + feature);
                failed.addSuppressed(cause);
            }
        }
        if (handles.isEmpty()) {
            installed.remove(feature);
            incompleteFeatures.remove(feature);
        }
        if (failed != null) throw failed;
    }
}
