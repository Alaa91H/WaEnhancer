package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * API 102 port of the legacy TypingPrivacy feature.
 *
 * The legacy version hooks WhatsApp's composing-state broadcast, reads the
 * state type and the recipient JID, and suppresses the state when the user
 * asked for typing (or recording) privacy — globally, or per contact through
 * the custom-privacy map.
 *
 * Targets are derived from repository evidence: the method using
 * `HandleMeComposing/sendComposing`, additionally requiring the observed
 * three-parameter shape whose third parameter is an `int`. Zero or several
 * matches, or a different signature, disables only this feature.
 *
 * Existing per-contact overrides live in the target's private WaGlobal
 * SharedPreferences, not in the Manager. Reading them directly avoids IPC,
 * a first-event rule miss, and copying phone numbers across app boundaries.
 *
 * Opt-in: needs a global typing/recording switch or the custom-privacy mode
 * enabled. Reads of options inside an installed hook use current preferences.
 */
object ModernTypingPrivacyFeature {
    const val FEATURE_ID = "typing_privacy"
    const val ANCHOR_COMPOSING = "HandleMeComposing/sendComposing"
    const val PREF_GHOSTMODE = "ghostmode"
    const val PREF_GHOSTMODE_TYPING = "ghostmode_t"
    const val PREF_GHOSTMODE_RECORDING = "ghostmode_r"
    const val STATE_TYPING = 0
    const val STATE_RECORDING = 1
    private const val TAG = "WA-X TypingPrivacy102"

    enum class Outcome {
        DISABLED,
        INSTALLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        UNSAFE_SIGNATURE,
        ERROR,
    }

    /**
     * The per-behaviour state, which is not the same as the hook state (#450).
     *
     * Typing and recording are separate promises to the people you are talking
     * to, so they are reported separately even though one hook serves both:
     * a user who hides recording but not typing has to be able to see which one
     * is actually in force.
     */
    data class BehaviourState(
        val typingRequested: Boolean,
        val recordingRequested: Boolean,
        val typingWithheld: Boolean,
        val recordingWithheld: Boolean,
        val outcome: Outcome,
    ) {
        /** True only when the hook is installed and the behaviour is in force. */
        fun isBehaviourActive(
            requested: Boolean,
            withheld: Boolean,
        ): Boolean = outcome == Outcome.INSTALLED && requested && withheld
    }

    /**
     * Reads the three legacy switches into the two behaviours they control.
     *
     * `ghostmode` is the legacy global that covers both, so it counts towards
     * each behaviour rather than being a third thing.
     */
    @JvmStatic
    fun behaviourState(
        preferences: SharedPreferences,
        outcome: Outcome,
    ): BehaviourState {
        val global = preferences.getBoolean(PREF_GHOSTMODE, false)
        val typing = global || preferences.getBoolean(PREF_GHOSTMODE_TYPING, false)
        val recording = global || preferences.getBoolean(PREF_GHOSTMODE_RECORDING, false)
        // Installed is the ceiling: without it nothing is withheld, and
        // reporting the preference alone is exactly the false claim #450 rules
        // out.
        val installed = outcome == Outcome.INSTALLED
        return BehaviourState(
            typingRequested = typing,
            recordingRequested = recording,
            typingWithheld = typing && installed,
            recordingWithheld = recording && installed,
            outcome = outcome,
        )
    }

    data class PrivacyRule(
        /** Null means no per-contact override: inherit the global switch. */
        val hideTyping: Boolean? = null,
        val hideRecording: Boolean? = null,
    )

    const val PREF_CUSTOM_PRIVACY_MODE = "custom_privacy_type"

    /** Pure decision, so the rule combination is testable without WhatsApp. */
    @JvmStatic
    fun shouldSuppress(
        stateType: Int,
        hideTyping: Boolean,
        hideRecording: Boolean,
    ): Boolean =
        (stateType == STATE_RECORDING && hideRecording) ||
            (stateType == STATE_TYPING && hideTyping)

    /** Explicit false per-contact rules override individual global toggles. */
    @JvmStatic
    fun shouldSuppressWithOverrides(
        stateType: Int,
        globalGhost: Boolean,
        globalTyping: Boolean,
        globalRecording: Boolean,
        rule: PrivacyRule?,
    ): Boolean =
        shouldSuppress(
            stateType,
            globalGhost || (rule?.hideTyping ?: globalTyping),
            globalGhost || (rule?.hideRecording ?: globalRecording),
        )

    @JvmStatic
    fun isCustomPrivacyEnabled(mode: String?): Boolean =
        mode == "1" || mode == "2"

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
        jidAccess: ModernJidAccess?,
    ): Outcome {
        val globalGhost = preferences.getBoolean(PREF_GHOSTMODE, false)
        val hideTypingGlobal = globalGhost || preferences.getBoolean(PREF_GHOSTMODE_TYPING, false)
        val hideRecordingGlobal = globalGhost || preferences.getBoolean(PREF_GHOSTMODE_RECORDING, false)
        val customEnabled = isCustomPrivacyEnabled(preferences.getString(PREF_CUSTOM_PRIVACY_MODE, "0"))
        if (!hideTypingGlobal && !hideRecordingGlobal && !customEnabled) return Outcome.DISABLED
        if (jidAccess == null) return Outcome.UNSAFE_SIGNATURE

        val targetRules =
            try {
                // WaGlobal belongs to this exact WhatsApp package/user, unlike
                // the Manager preferences previously queried through IPC.
                ModernTargetPrivacyRuleStore(
                    target.getSharedPreferences("WaGlobal", Context.MODE_PRIVATE),
                )
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Target-local privacy rules unavailable: ${failure.javaClass.simpleName}")
                return Outcome.ERROR
            }

        val composing =
            try {
                DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                    dex.findMethod {
                        matcher {
                            addUsingString(ANCHOR_COMPOSING, StringMatchType.Contains)
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Composing resolver unavailable", failure)
                return Outcome.RESOLVER_MISSING
            }
        when {
            composing.isEmpty() -> return Outcome.RESOLVER_MISSING
            composing.size != 1 -> return Outcome.RESOLVER_AMBIGUOUS
        }
        val method =
            try {
                composing[0].getMethodInstance(target.classLoader)
            } catch (failure: Throwable) {
                Log.w(TAG, "Composing method unresolvable", failure)
                return Outcome.RESOLVER_MISSING
            }
        // The legacy resolver requires the state Int in slot 2.
        // Reject multiple Int slots instead of guessing which one represents composing.
        if (!isSafeComposingSignature(method.parameterTypes)) {
            return Outcome.UNSAFE_SIGNATURE
        }
        val jidClass = jidAccess.jidClass

        try {
            hooks.installFeature(
                FEATURE_ID,
                listOf(
                    ModernHookRegistry.Registration("typing_privacy.composing") {
                        val handle =
                            ModernHookBridge(framework).intercept(
                                method,
                                "wax.modern.typing_privacy.composing",
                            ) { chain ->
                                val stateType = composingState(method.parameterTypes, chain.args.toList())
                                val jid = uniqueJidArgument(chain.args.toList(), jidClass)
                                // Never use numeric LIDs or group IDs as contact numbers:
                                // they could select an unrelated <number>_privacy record.
                                val number = jidAccess.privacyOverridePhoneNumber(jid)
                                // Read target-local rules synchronously from the
                                // already-open SharedPreferences. The very first
                                // typing event must respect the saved override.
                                val rule =
                                    if (isCustomPrivacyEnabled(preferences.getString(PREF_CUSTOM_PRIVACY_MODE, "0"))) {
                                        targetRules.forPhoneNumber(number)
                                    } else {
                                        null
                                    }
                                if (stateType != null &&
                                    shouldSuppressWithOverrides(
                                        stateType,
                                        preferences.getBoolean(PREF_GHOSTMODE, false),
                                        preferences.getBoolean(PREF_GHOSTMODE_TYPING, false),
                                        preferences.getBoolean(PREF_GHOSTMODE_RECORDING, false),
                                        rule,
                                    )
                                ) {
                                    // Legacy semantics: the state callback never fires.
                                    return@intercept null
                                }
                                chain.proceed()
                            }
                        ModernHookRegistry.Handle { handle.unhook() }
                    },
                ),
            )
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Typing privacy hook unavailable", failure)
            return Outcome.ERROR
        }
        return Outcome.INSTALLED
    }

    /**
     * Two JID parameters do not establish which is the recipient. Suppress
     * only through global switches until the target signature is proven,
     * rather than applying a different chat's per-contact rule.
     */
    internal fun uniqueJidArgument(args: List<Any?>, jidClass: Class<*>): Any? {
        var match: Any? = null
        for (candidate in args) {
            if (candidate != null && jidClass.isInstance(candidate)) {
                if (match != null) return null
                match = candidate
            }
        }
        return match
    }

    /** Fail closed when the composing-state signature is missing or ambiguous. */
    internal fun isSafeComposingSignature(parameterTypes: Array<Class<*>>): Boolean =
        parameterTypes.size >= 3 &&
            parameterTypes[2] == Int::class.javaPrimitiveType &&
            parameterTypes.count { it == Int::class.javaPrimitiveType } == 1

    /** Read the observed composing state from the third argument, not the first. */
    internal fun composingState(parameterTypes: Array<Class<*>>, args: List<Any?>): Int? =
        if (isSafeComposingSignature(parameterTypes) && args.size == parameterTypes.size) {
            args[2] as? Int
        } else {
            null
        }

    /**
     * Compatibility entry point used by older tests and settings listeners.
     * The source of truth is now target-local SharedPreferences, so there is
     * no stale number-keyed cache to invalidate after a privacy edit.
     */
    @JvmStatic
    fun clearRuleCache() = Unit
}
