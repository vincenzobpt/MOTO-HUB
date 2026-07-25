package io.motohub.android.feature.controls

import android.content.Context

/**
 * Destinations for one-press navigation from a handlebar button.
 * Three slots, each with a name ("Home") and a query (address / place / lat,lng).
 */
object SavedPlaces {
    const val COUNT = 3
    private const val PREF = "motohub_saved_places"

    fun name(context: Context, slot: Int): String =
        prefs(context).getString("name$slot", "") ?: ""

    fun query(context: Context, slot: Int): String =
        prefs(context).getString("query$slot", "") ?: ""

    fun set(context: Context, slot: Int, name: String, query: String) {
        prefs(context).edit()
            .putString("name$slot", name.trim())
            .putString("query$slot", query.trim())
            .apply()
    }

    fun isSet(context: Context, slot: Int): Boolean = query(context, slot).isNotBlank()

    fun actionLabel(context: Context, slot: Int): String {
        if (!isSet(context, slot)) return "Navigate to place ${slot + 1} (not set)"
        val n = name(context, slot)
        return "Navigate: ${n.ifBlank { query(context, slot) }}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
