package com.example.nfcreproducer

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

data class MifareBlock(
    val sector: Int,
    val block: Int,
    val data: ByteArray
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sector", sector)
        put("block", block)
        put("data", Base64.encodeToString(data, Base64.NO_WRAP))
    }

    companion object {
        fun fromJson(json: JSONObject) = MifareBlock(
            sector = json.getInt("sector"),
            block = json.getInt("block"),
            data = Base64.decode(json.getString("data"), Base64.DEFAULT)
        )
    }
}

data class MifareDump(
    val sizeBytes: Int,
    val sectorCount: Int,
    val blocks: List<MifareBlock>,
    val unreadableSectors: List<Int>
) {
    val readableSectorCount: Int
        get() = blocks.map { it.sector }.distinct().size

    fun toJson(): JSONObject = JSONObject().apply {
        put("sizeBytes", sizeBytes)
        put("sectorCount", sectorCount)
        put("blocks", JSONArray().apply { blocks.forEach { put(it.toJson()) } })
        put("unreadableSectors", JSONArray().apply { unreadableSectors.forEach { put(it) } })
    }

    companion object {
        fun fromJson(json: JSONObject): MifareDump {
            val blocksJson = json.optJSONArray("blocks") ?: JSONArray()
            val blocks = buildList {
                for (index in 0 until blocksJson.length()) {
                    add(MifareBlock.fromJson(blocksJson.getJSONObject(index)))
                }
            }
            val unreadableJson = json.optJSONArray("unreadableSectors") ?: JSONArray()
            val unreadable = buildList {
                for (index in 0 until unreadableJson.length()) {
                    add(unreadableJson.getInt(index))
                }
            }
            return MifareDump(
                sizeBytes = json.optInt("sizeBytes"),
                sectorCount = json.optInt("sectorCount"),
                blocks = blocks,
                unreadableSectors = unreadable
            )
        }
    }
}

data class TagInfo(
    val id: String,
    val displayName: String = "",
    val technologies: List<String>,
    val type: String,
    val records: List<String>,
    val rawNdef: ByteArray? = null,
    val mifareDump: MifareDump? = null,
    val scannedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("technologies", JSONArray(technologies))
        put("type", type)
        put("records", JSONArray(records))
        put("raw", rawNdef?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
        put("mifareDump", mifareDump?.toJson())
        put("scannedAt", scannedAt)
    }

    fun uidBytes(): ByteArray = id.hexToBytes()

    /**
     * Creates the payload exposed by the custom HCE application.
     *
     * Binary format (big endian):
     * NFR1 | flags | uidLength | uid | blockCount (u16) |
     * [sector (u8), absoluteBlock (u16), length (u8), data]... |
     * ndefLength (u16) | ndef
     */
    fun toHcePayload(): ByteArray {
        val uid = uidBytes()
        val blocks = mifareDump?.blocks.orEmpty().sortedWith(compareBy({ it.sector }, { it.block }))
        val ndef = rawNdef?.let { it.copyOfRange(0, it.size.coerceAtMost(0xFFFF)) }
            ?: ByteArray(0)
        val flags = (if (blocks.isNotEmpty()) FLAG_MIFARE else 0) or
            (if (ndef.isNotEmpty()) FLAG_NDEF else 0)

        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(byteArrayOf('N'.code.toByte(), 'F'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte()))
                output.writeByte(flags)
                output.writeByte(uid.size.coerceAtMost(0xFF))
                output.write(uid, 0, uid.size.coerceAtMost(0xFF))
                output.writeShort(blocks.size.coerceAtMost(0xFFFF))
                blocks.take(0xFFFF).forEach { captured ->
                    output.writeByte(captured.sector)
                    output.writeShort(captured.block)
                    output.writeByte(captured.data.size.coerceAtMost(0xFF))
                    output.write(captured.data, 0, captured.data.size.coerceAtMost(0xFF))
                }
                output.writeShort(ndef.size)
                output.write(ndef)
            }
            bytes.toByteArray()
        }
    }

    companion object {
        const val FLAG_MIFARE = 0x01
        const val FLAG_NDEF = 0x02

        fun fromJson(json: JSONObject): TagInfo {
            val technologies = json.optJSONArray("technologies")?.toStringList()
                ?: json.optString("technologies").split('|').filter { it.isNotBlank() }
            val records = json.optJSONArray("records")?.toStringList()
                ?: json.optString("records").split("\u001f").filter { it.isNotEmpty() }
            return TagInfo(
                id = json.optString("id"),
                displayName = json.optString("displayName"),
                technologies = technologies,
                type = json.optString("type"),
                records = records,
                rawNdef = json.optString("raw").takeIf { it.isNotEmpty() }
                    ?.let { Base64.decode(it, Base64.DEFAULT) },
                mifareDump = json.optJSONObject("mifareDump")?.let(MifareDump::fromJson),
                scannedAt = json.optLong("scannedAt", System.currentTimeMillis())
            )
        }

        private fun JSONArray.toStringList(): List<String> = buildList {
            for (index in 0 until length()) add(optString(index))
        }

        private fun String.hexToBytes(): ByteArray {
            val normalized = filter { !it.isWhitespace() }
            if (normalized.length % 2 != 0) return ByteArray(0)
            return try {
                normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: NumberFormatException) {
                ByteArray(0)
            }
        }
    }
}
