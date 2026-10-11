package com.wax.module.ui.customization

import com.wax.module.settings.SettingsKeys
import com.wax.module.settings.SettingsScope
import java.security.MessageDigest

/**
 * Non-secret optimistic stamp for the five editable preview keys.
 *
 * Include both the selected target's raw overrides and their Global parents:
 * the effective preview alone cannot reveal a changed override that happens
 * to resolve to the same visual value. No WhatsApp data or credentials are read.
 * This provides conflict detection, not a cross-process CAS transaction.
 */
internal object CustomizationSourceStamp {
    private val keys = listOf("changecolor", "primary_color", "channels", "hidetabs", "floating_bottom_bar")

    fun fromValues(
        scope: SettingsScope,
        values: Map<String, *>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val selected =
            keys
                .flatMap { name ->
                    listOf(
                        SettingsKeys.physicalKey(SettingsScope.Global, name),
                        SettingsKeys.physicalKey(scope, name),
                    )
                }.distinct()
                .sorted()
        for (key in selected) {
            val raw = values[key]
            val encoded =
                when {
                    !values.containsKey(key) -> "MISSING"
                    raw == null -> "NULL"
                    raw is Set<*> -> "SET:" + raw.map { it.toString() }.sorted().joinToString("|")
                    else -> raw.javaClass.name + ":" + raw.toString()
                }
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(encoded.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
