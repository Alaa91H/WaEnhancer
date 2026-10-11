# M06 Batch 2 — TypingPrivacy (first migrated consumer)

The first feature ported **onto** the new accessor layers rather than beside
them. Source- and CI-verified; on-device confirmation is
`PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `TypingPrivacy` hooks WhatsApp's composing-state broadcast, reads the
state type and the recipient JID, and suppresses the state when the user asked
for typing or recording privacy — globally or per contact.

`ModernTypingPrivacyFeature` keeps that behaviour and replaces every legacy
dependency:

- **Hook target from repo evidence**: the method using
  `HandleMeComposing/sendComposing`, requiring the observed three-parameter
  shape whose third parameter is an `int` — the same guard the legacy resolver
  applies. Zero/several matches or a different signature disable only this
  feature (`RESOLVER_MISSING`, `RESOLVER_AMBIGUOUS`, `UNSAFE_SIGNATURE`).
- **Target-aware JID resolution** instead of `FMessageWpp.UserJid`: exactly
  one argument may match `jidClass`. Two candidate JIDs cannot prove the
  recipient, so no per-contact override is selected in that case.
  `ModernJidAccess.privacyOverridePhoneNumber` accepts only a verified
  numeric `@s.whatsapp.net` JID (including valid linked-device suffixes).
  Numeric `@lid`, group, broadcast and unknown-domain identifiers cannot
  select another contact's rule. The legacy `phoneNumber` projection remains
  unchanged for all other consumers. Global switches still apply.
- **Raw-JID method reflection** excludes the Java standard `toString()`
  from eligible no-argument `String` accessors. Otherwise the inherited or
  overridden display method causes false ambiguity beside the real raw
  accessor, or is falsely selected when no raw accessor exists. Zero or
  multiple remaining candidates still fail closed. JVM fixtures verify both
  `toString` cases plus genuine resolver ambiguity.
- **Suppression decision is pure and tested**: `shouldSuppress(state, typing,
  recording)` pins the legacy semantics, including that recording is governed
  by its own rule and that unrelated state values are never suppressed.

## P0 correction: actual storage of per-contact rules (2026-10-11)

A source audit of `ModuleRuntime.getPrivPrefs()` and
`CustomPrivacy.savePreferences()` confirmed that legacy per-contact JSON
overrides live under `<number>_privacy` in **the target WhatsApp process's own
private `WaGlobal` SharedPreferences**. They do NOT live in the Manager's
default preferences, contrary to the previous version of this document.

The corrected API 102 hook reads that already-open target-local store without
sending phone numbers across Binder or waiting for an asynchronous Manager
lookup. The first composing event now sees an existing override immediately.
Messenger, Business and independent Android users/profiles have separate
storage, under the Android application sandbox.

An absent JSON property inherits the corresponding global switch; explicit
`false` overrides that switch, while global `ghostmode` still takes
precedence. Corrupt nonempty records fail closed. The target-local reader
rejects invalid phone identifiers and does not log contact values. No
number-keyed cache is retained, so same-process edits become visible at the
next event. Custom-privacy mode is a valid reason to install this hook even
when both global activity toggles are off.

`read-target-privacy-v1` remains an older compatibility API, but the
corrected modern typing hook does not call it. This avoids a false assumption
about the Manager owning target-local per-contact data.
## Settings and controls

The relay forwards the three global switches (`ghostmode`, `ghostmode_t`,
`ghostmode_r`) **and** `custom_privacy_type` as a typed 0/1/2 mode; the
contract checker asserts the settings survive Manager-to-runtime mirroring. The Control Center shows
"Hide Typing" as a real Privacy-category toggle, and the write allowlist
accepts exactly those three keys.

## Verification

Offline tests cover the parser for real legacy JSON booleans, the first-event
read, per-contact `false` vs missing properties, live same-process preference
edits, Messenger/Business store separation, invalid identifiers, corruption,
the global-ghost override, typing/recording independence, and the exact third
argument composing-state contract.

**Field acceptance is deferred until a built APK can be tested.** Verify
target-context access, changes from the actual editor, live preferences and
external sender-visible ON/OFF using consenting test accounts and exact target
builds. An installed hook does not prove external behavior. If the feature
was entirely disabled at process startup, later enabling it may require a
WhatsApp restart until hot-install is supported.