# Embedded WA X Control Center — delivery and verification

Issue: [#458](https://github.com/Alaa91H/WA-X/issues/458). PR: [#478](https://github.com/Alaa91H/WA-X/pull/478).

## Implemented in this PR

- Bounded Android framework dialog hosted in WhatsApp, with category/favourites tabs and live search.
- Compact controls with one favourite star, clear requested/effective states, grey OFF and green ON switches.
- Arabic and shipped language labels reused from Manager resource files through a checked-in data-only generator.
- Initial authenticated settings snapshot fetched **off the WhatsApp main thread**. The dialog shows a loading/error state until readback completes; it observes settings only while visible.
- Manager-owned multi-profile repository with schema/version validation and global desired-preference snapshots. Default profile migrates existing current settings without deleting them. Current Manager values remain authoritative.
- Profile create/duplicate/rename/delete/select in a private Manager Activity; quick-select in embedded WhatsApp UI through the existing UID-authenticated telemetry provider.
- Profile icons can be customized from a bounded allowlist in Manager and are displayed in the embedded selector. Older schema-v1 documents without an icon retain all preferences and safely use a default; invalid icons are rejected.
- Profiles persist only allowlisted feature flags, anti-revoke/archive string modes and favourite IDs. Contacts, messages, media, Tasker auth tokens and credentials are not stored in profiles.
- Bad/corrupt, oversized and wrong-type profile documents fail closed rather than being overwritten. Failed profile switches report an error and attempt rollback.
- The Manager profile screen listens to SharedPreferences changes while mounted, unregistering on disposal; the embedded panel listens to the existing content-observer notification.
- No direct Xposed, DexKit, root or WhatsApp message/database access from Manager UI.

## Current safety boundaries

**Important:** Modern pilot preferences currently use shared *global* Manager keys. Thus profiles affect both WhatsApp and WhatsApp Business; they are **not** per-target/account/user independent. Both UIs display this limitation. Do not describe global snapshots as account-isolated profiles. A target-aware settings repository and independent, acknowledged per-target relay must precede that claim (#340, #342, #458).

The active profile and desired settings are committed together; this is persistence success, **not** proof that a Hook accepted the revision. Do not show `BEHAVIOR_VERIFIED` without a real invocation and behavioural evidence. Existing API102 settings relay is best effort, and live-apply/restart policies have not been validated for every feature.

## Verified locally

- `:modern-runtime:testDebugUnitTest`
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- Windows development computer, Android SDK and pinned Ogg/Opus/Opusenc submodules.
- New pure JVM regression tests cover default migration, profile CRUD, favourites/flag/mode round trips, malformed schemas, invalid profile names and rejected commits.

Always record exact HEAD and final Gradle results before release. Build success is not WhatsApp hook-efficacy proof.

## Still required for full #458 acceptance

- Verify WhatsApp and WhatsApp Business on real hardware, Arabic/English and other shipped locales, dark/light, keyboard/font scaling, TalkBack and process death.
- Verify Manager ⇄ embedded panel syncing on both targets and after target restart, including lost/out-of-order acknowledgements and failed writes.
- Implement and test per-target/account user-isolated profiles and a revision-specific host-applied ACK path; no silent cross-account propagation.
- Audit each feature's runtime activation policy and display pending/failed/restart state without guessing. Do not force restart on every toggle.
- Screenshot and performance instrumentation (startup latency, jank, memory/ANR) with measured evidence.
- Complete final UIX-01 Manager program (#370/#371) without duplicating the existing Manager navigation/registry.
- Review CI, CodeQL and release/signing gates before main merge.
