package com.wax.module.modern;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Per-process failure budget tests. Registration != externally verified behavior. */
public final class ModernHookRegistryCircuitBreakerTest {
    private static ModernHookRegistry.Registration failing(String id, AtomicInteger attempts) {
        return new ModernHookRegistry.Registration(id, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("synthetic install failure");
        });
    }

    @Test public void thirdConsecutiveFailedInstallQuarantinesOnlyThatFeature() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger attempts = new AtomicInteger();
        for (int n = 1; n <= 3; n++) {
            assertThrows(IllegalStateException.class,
                    () -> registry.installOnce("broken", failing("broken.hook", attempts)));
            assertEquals(n, registry.consecutiveInstallFailures("broken"));
        }
        assertEquals(3, attempts.get());
        assertEquals(ModernHookRegistry.InstallState.QUARANTINED, registry.installState("broken"));
        for (int i = 0; i < 10; i++) {
            assertThrows(IllegalStateException.class,
                    () -> registry.installOnce("broken", failing("broken.hook", attempts)));
        }
        assertEquals(3, attempts.get());
        assertEquals(3, registry.consecutiveInstallFailures("broken"));

        // A different feature in the SAME process is still allowed to start.
        assertTrue(registry.installOnce("healthy",
                new ModernHookRegistry.Registration("healthy.hook", () -> () -> {})));
        assertEquals(ModernHookRegistry.InstallState.INSTALLED, registry.installState("healthy"));
        assertEquals(ModernHookRegistry.InstallState.QUARANTINED, registry.installState("broken"));
        assertEquals(0, registry.consecutiveInstallFailures("healthy"));
    }

    @Test public void explicitResetDoesNotSilentlyChangeSavedUserPreference() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger attempted = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalStateException.class,
                    () -> registry.installFeature("privacy",
                            Collections.singletonList(failing("hook", attempted))));
        }
        assertEquals(3, attempted.get());
        registry.resetSessionQuarantine("privacy");
        assertEquals(0, registry.consecutiveInstallFailures("privacy"));
        assertEquals(ModernHookRegistry.InstallState.UNKNOWN, registry.installState("privacy"));
        assertEquals(1, registry.installFeature("privacy",
                Collections.singletonList(
                        new ModernHookRegistry.Registration("hook", () -> () -> {}))));
        assertEquals(ModernHookRegistry.InstallState.INSTALLED, registry.installState("privacy"));
    }

    @Test public void successResetsConsecutiveBudgetAndOnlyActualInstallerFailuresCount() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> registry.installOnce("privacy", failing("valid", attempts)));
        assertEquals(ModernHookRegistry.InstallState.FAILED, registry.installState("privacy"));
        // Preflight duplicate IDs must not consume the failure budget.
        ModernHookRegistry.Registration noop =
                new ModernHookRegistry.Registration("duplicate", () -> () -> {});
        assertThrows(IllegalArgumentException.class,
                () -> registry.installFeature("privacy", Arrays.asList(noop, noop)));
        assertEquals(1, registry.consecutiveInstallFailures("privacy"));

        registry.installOnce("privacy",
                new ModernHookRegistry.Registration("valid", () -> () -> {}));
        assertEquals(0, registry.consecutiveInstallFailures("privacy"));
        registry.removeFeature("privacy");
        assertEquals(ModernHookRegistry.InstallState.UNKNOWN, registry.installState("privacy"));

        assertThrows(IllegalStateException.class,
                () -> registry.installOnce("privacy", failing("valid", attempts)));
        assertEquals(1, registry.consecutiveInstallFailures("privacy"));
        assertEquals(2, attempts.get());
    }

    @Test public void incompleteRollbackTakesPrecedenceAndNeedsCleanup() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger unhooks = new AtomicInteger();
        try {
            registry.installFeature("media", Arrays.asList(
                    new ModernHookRegistry.Registration("h1", () -> () -> {
                        if (unhooks.incrementAndGet() == 1)
                            throw new IllegalStateException("synthetic unhook failure");
                    }),
                    new ModernHookRegistry.Registration("h2", () -> {
                        throw new IllegalStateException("synthetic second install failure");
                    })
            ));
            fail("group install must throw");
        } catch (IllegalStateException expected) {
            assertEquals(1, expected.getSuppressed().length);
        }
        assertEquals(ModernHookRegistry.InstallState.INCOMPLETE_ROLLBACK,
                registry.installState("media"));
        assertThrows(IllegalStateException.class,
                () -> registry.resetSessionQuarantine("media"));
        assertThrows(IllegalStateException.class,
                () -> registry.installFeature("media", Collections.singletonList(
                        new ModernHookRegistry.Registration("h1", () -> () -> {}))));
        registry.removeFeature("media");
        assertEquals(2, unhooks.get());
        assertEquals(ModernHookRegistry.InstallState.FAILED, registry.installState("media"));
        registry.resetSessionQuarantine("media");
        assertEquals(ModernHookRegistry.InstallState.UNKNOWN, registry.installState("media"));
    }

    @Test public void concurrentFailuresCannotOvershootOneSessionBudget() throws Exception {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            @SuppressWarnings("unchecked")
            Future<Boolean>[] futures = new Future[36];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(() -> {
                    gate.await();
                    try {
                        registry.installOnce("unstable", failing("unstable.hook", attempts));
                        return false;
                    } catch (Throwable expected) {
                        return expected instanceof IllegalStateException;
                    }
                });
            }
            gate.countDown();
            for (Future<Boolean> future : futures) {
                assertTrue(future.get(15, TimeUnit.SECONDS));
            }
            assertEquals(3, attempts.get());
            assertEquals(ModernHookRegistry.InstallState.QUARANTINED,
                    registry.installState("unstable"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void independentTargetProcessesNeverShareTheirSessionBudget() throws Throwable {
        ModernHookRegistry whatsapp = new ModernHookRegistry();
        ModernHookRegistry business = new ModernHookRegistry();
        AtomicInteger tries = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalStateException.class,
                    () -> whatsapp.installOnce("privacy", failing("hook", tries)));
        }
        assertEquals(ModernHookRegistry.InstallState.QUARANTINED,
                whatsapp.installState("privacy"));
        assertEquals(ModernHookRegistry.InstallState.UNKNOWN,
                business.installState("privacy"));
        assertTrue(business.installOnce("privacy",
                new ModernHookRegistry.Registration("hook", () -> () -> {})));
        assertEquals(ModernHookRegistry.InstallState.INSTALLED, business.installState("privacy"));
    }
}
