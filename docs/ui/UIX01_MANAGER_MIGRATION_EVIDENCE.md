# UIX-01 Manager migration: implementation evidence and remaining gates

**Status:** incremental implementation in PR #481; **not** a declaration that issue #371 is complete.
The approved package remains authoritative: `APPROVED_MANAGER_UI_UX_UNIFIED_PACKAGE.md`.

## Current screen architecture

| Visible destination | Source | Existing subsystem reused |
| --- | --- | --- |
| Home | `HomeFragment` (legacy view) | existing authenticated API102 runtime status, Diagnostics and update logic |
| Features | `FeatureHubFragment` (Compose) | `FeatureCatalog`, `SettingKeyRegistry`, `SharedPreferencesSettingsStore`, `EffectiveSettingsResolver` |
| Customization | `CustomizationDashboardFragment` (Compose) | existing `hidetabs`, `channels`, `primary_color`, `changecolor`, `floating_bottom_bar`, typed per-target key prefix |
| Tools | `ToolsHubFragment` (Compose) | existing diagnostics, Home backup/export/import, target settings, search, update settings, About |
| Manager settings (header) | `ManagerSettingsActivity` (Compose) | existing `thememode`, language, logging and update preferences |

No new Xposed/DexKit runtime entry point has been introduced by UIX-01.

The adapter retains the original six page identities and adds three new ones. The
old search/deep-link positions are **0 Home -> 0; 1 General -> 4; 2 Privacy ->
5; 3 Media -> 6; 4 Customization editor -> 8; 5 Recordings -> 7**.
New browser is at page 1, dashboard at 2, tools at 3.
FragmentStateAdapter resolves restored fragments using stable item IDs, **not page positions**.
Old preference keys are not renamed and all legacy preference fragments remain reachable.

## Meaning of controls

- The feature switch writes only a **selected configuration value**, through
  the existing typed store for Global/WhatsApp/Business. UI explicitly states
  that a selected switch does **not** prove a working runtime hook.
- For per-target Boolean settings, `Use Global` removes the target override;
  it does not copy a Global value into the target.
- Customization preview changes are staged until Apply. The repository checks
  for concurrent preference modifications and uses one commit transaction to
  write changed existing typed values; Cancel never writes anything.
- Legacy `hidetabs=300` hides the **entire Updates tab**. It must never be
  presented as independent Hide Status Section. That separate option is
  explicitly labeled unavailable; this acceptance gate remains open.
- The simulated preview is not the WhatsApp view hierarchy. API102
  compatibility/effectiveness must still be tested on an actual supported
  WhatsApp/Business build. No fabricated status, resolver or restart is shown.
- App theme choices preserve existing values 0/1/2 and add 3 for AMOLED.
  Compose shares that preference rather than relying only on system theme.

## New Home/Tools safety work (2026-10-11)

- Tools now presents the six requested destinations with an adaptive two/three-column layout:
  Diagnostics, Compatibility, Backups, Updates, Sanitized Reports, and Safe Mode & Recovery.
  Compatibility, report export and recovery **reuse** the existing Diagnostics Activity,
  not a new health/compatibility engine.
- The Compatibility tile explicitly states that current diagnostics evidence does not imply
  signed exact-version compatibility and warns before showing existing tests.
  Safe Mode & Recovery offers a read-only explanation and a route to diagnostics,
  **not** an unverified automatic reset or unsupported switch.
- Home manual checks report a request or an observed snapshot refresh independently
  from runtime success. Automatic modern status refresh now reuses a single in-flight job,
  disables repeat Check actions while active, records the local refresh time, surfaces a
  passive inspection failure as unverified, and cancels the job when Home stops.
  A valid target reply is acknowledged without implying a successful hook.
- This is **UI progress/feedback only**. It does not implement missing M01/M02/A05
  evidence stages, signed per-exact-build compatibility or runtime repair.

## Local validation

Verified on an authorized Windows Android SDK/JDK builder:

- `:app:compileDebugKotlin` succeeded at multiple UIX-01 development stages.
- Scoped JUnit tests `LegacyNavigationMapTest`,
  `CustomizationPreviewStateTest` and `ManagerAppearanceTest` succeeded
  prior to the final 16-combination test expansion.
- `:app:assembleDebug :app:testDebugUnitTest` with those filters completed
  successfully on the UIX-01 working tree (77 Gradle tasks).
- The diagnostic report/export engine, Feature registry, preference target
  isolation and audio native libraries are not reimplemented by these changes.
- Git diff whitespace gate was checked.

**Local tests are not evidence of WhatsApp hooks operating, device UI accuracy,
all-locale translations, screenshot goldens, TalkBack behavior or a signed
release/rollback drill.**

## Blocking work before #371 closure

1. Finish the typed Manager ViewModel/UDF and the controlled Compose migration
   per architectural prerequisites #318, #333, #344 and #345. Main still mixes
   legacy fragments and Compose by design.
2. Real Home single-step health journey and exact-target-version feature
   compatibility model sharing from #370; avoid green by inference.
3. Full independent Hide Status section functionality only when a supported
   hook exists; verify behavior on both target apps with 16 combination states.
4. All shipped-language translations, RTL bidirectional strings, accessibility,
   font-scale, light/dark/AMOLED visual regression and keyboard/rotation tests.
5. Screenshots and instrumentation proof for every migrated navigation/deep-link
   and backup/restore action; test staged Apply/Cancel/conflict recovery on
   both targets, and verify zero data loss on upgrade/rollback.
6. #296 wider Manager Theme Studio, integrated rather than parallel to UIX-01.
7. Release-grade CI, signing/rollback and sanitized real-device evidence where
   WhatsApp runtime behavior is claimed. PR must remain **draft** until gates pass.

## Scope limitation

New prose is currently maintained in Arabic and English resource catalogs;
other shipped locales use Android's default English fallback until explicitly
translated and reviewed. Never advertise full localization coverage prematurely.
