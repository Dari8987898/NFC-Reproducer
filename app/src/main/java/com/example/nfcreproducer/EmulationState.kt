package com.example.nfcreproducer

import android.content.Context
import org.json.JSONObject

object EmulationState {
    private const val PREFS = "hce_badge"
    private const val KEY_ACTIVE_TAG = "active_tag"

    fun setActive(context: Context, tag: TagInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_TAG, tag.toJson().toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_TAG)
            .apply()
    }

    fun getActive(context: Context): TagInfo? {
        val serialized = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_TAG, null)
            ?: return null
        return try {
            TagInfo.fromJson(JSONObject(serialized))
        } catch (_: Exception) {
            null
        }
    }

    fun activeId(context: Context): String? = getActive(context)?.id
}
