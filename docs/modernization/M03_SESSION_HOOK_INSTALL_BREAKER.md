# M03/A05: API 102 per-session hook-install failure budget

## Scope and purpose

This is a **bounded install-stage circuit breaker**, not the complete feature
health pipeline in #339, crash-loop Safe Mode (#358/F157), a signed kill switch
(M12) or verified WhatsApp behavior. Its source of truth is
`ModernHookRegistry`, one registry per injected target process.

An installed `Handle` means **hook registration only**. It never implies that a
WhatsApp receiver has observed delivery/read/typing privacy or that a resolver
is compatible with a newly updated target APK.

## Invariants

- After **three consecutive thrown installer failures** for the same feature
  within one registry/session, further install attempts for that feature throw
  `IllegalStateException` **before invoking any installer**.
- Validation failures (duplicate IDs, cross-feature collisions and invalid
  registrations) do **not** consume the failure budget. A successful hook
  install clears the consecutive failure count.
- All methods are synchronized on the registry, so parallel callbacks cannot
  race past the failure limit. A different feature ID retains its own budget.
- A failed reverse rollback with residual handles is always reported as
  `INCOMPLETE_ROLLBACK`. Quarantine and idempotent registration cannot mask
  this state. `removeFeature` must successfully remove the residual handles
  before `resetSessionQuarantine` is allowed.
- Quarantine never writes Manager or WhatsApp settings and never changes the
  desired/persisted user choice. An explicit `resetSessionQuarantine` allows a
  local retry after cleanup. A fresh process has a fresh independent registry.
- `InstallState` is installation-only: `UNKNOWN`, `INSTALLED`, `FAILED`,
  `QUARANTINED`, `INCOMPLETE_ROLLBACK`. None is called `VERIFIED` or
  `HEALTHY`, because registration alone does not prove actual behavior.

## Explicit remaining A05/M12 work

- Route **all** optional installs through the one FeatureInstaller V2 pipeline,
  carrying desired setting, exact target identity, capability/resolver evidence,
  compatible signed kill switch, configuration revision and observation state.
- Capture real runtime callback error rate, install timings, safe sanitized
  health evidence and crash-loop Safe Mode across process restarts. This
  session-only install budget is intentionally not a persistent crash detector.
- Add host-side duplicate/process-restart and real-device Messenger/Business
  tests (including secondary profiles); confirm unrelated functionality stays
  installed and stock WhatsApp behavior remains intact after a failed hook.
- Provide user-visible recovery and reliable explicit reset with audited
  permissions. Current in-process Java reset API does not constitute a UI.

## Offline regression

`ModernHookRegistryCircuitBreakerTest` covers threshold, fail-stop,
independent features and process registries, live success budget reset,
preflight errors, concurrent attempts, failed rollback cleanup and explicit
reset. The existing `ModernHookRegistryTest` remains the regression gate for
atomic install/rollback and idempotent registration.
