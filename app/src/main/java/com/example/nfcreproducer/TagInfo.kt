package com.example.nfcreproducer

import android.util.Base64
import org.json.JSONObject

data class TagInfo(
    val id: String,
    val technologies: List<String>,
    val type: String,
    val records: List<String>,
    val rawNdef: ByteArray? = null,
    val scannedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("technologies", technologies.joinToString("|")); put("type", type)
        put("records", records.joinToString("\u001f")); put("raw", rawNdef?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
        put("scannedAt", scannedAt)
    }
    companion object {
        fun fromJson(o: JSONObject) = TagInfo(o.optString("id"), o.optString("technologies").split("|").filter { it.isNotBlank() }, o.optString("type"), o.optString("records").split("\u001f").filter { it.isNotEmpty() }, o.optString("raw").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) }, o.optLong("scannedAt"))
    }
}
