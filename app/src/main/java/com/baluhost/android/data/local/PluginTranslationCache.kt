package com.baluhost.android.data.local

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory store for the German plugin strings read from the UI manifest.
 *
 * A plugin's menu action answers with a message key rather than a finished
 * sentence, so the key has to be resolved against the translations the same
 * manifest carries. Refilled every time the power dialog opens; nothing is
 * persisted, and a cold start simply falls back to the English message_text.
 */
@Singleton
class PluginTranslationCache @Inject constructor() {

    private val translations = ConcurrentHashMap<String, Map<String, String>>()

    fun put(pluginName: String, strings: Map<String, String>) {
        translations[pluginName] = strings
    }

    /** The translated string for [key], or [fallback] if the key is unknown. */
    fun resolve(pluginName: String, key: String?, fallback: String): String {
        if (key.isNullOrEmpty()) return fallback
        return translations[pluginName]?.get(key) ?: fallback
    }
}
