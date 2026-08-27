package com.example.nfcreproducer

import android.app.Activity
import android.content.Intent
import android.nfc.*
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.nio.charset.Charset

class MainActivity : AppCompatActivity() {
    private var adapter: NfcAdapter? = null
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var save: Button
    private lateinit var savedContainer: LinearLayout
    private var current: TagInfo? = null
    private var writeTarget: TagInfo? = null
    private val saved = mutableListOf<TagInfo>()
    private val prefs by lazy { getSharedPreferences("tags", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        
        status = findViewById(R.id.statusText)
        details = findViewById(R.id.tagDetails)
        save = findViewById(R.id.saveButton)
        savedContainer = findViewById(R.id.savedContainer)
        
        adapter = NfcAdapter.getDefaultAdapter(this)
        loadSaved()
        renderSaved()
        
        save.setOnClickListener {
            current?.let {
                if (saved.none { s -> s.id == it.id }) {
                    saved += it
                    persist()
                    renderSaved()
                    status.text = "Tag saved"
                } else {
                    status.text = "This tag is already saved"
                }
            }
        }
        
        if (adapter == null) {
            status.text = getString(R.string.no_nfc_support)
            save.isEnabled = false
        }
    }
    override fun onResume() {
        super.onResume()
        val a = adapter ?: return
        
        if (!a.isEnabled) {
            status.text = getString(R.string.nfc_disabled)
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            return
        }
        
        a.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or 
            NfcAdapter.FLAG_READER_NFC_B or 
            NfcAdapter.FLAG_READER_NFC_F or 
            NfcAdapter.FLAG_READER_NFC_V, 
            null
        )
    }

    override fun onPause() {
        adapter?.disableReaderMode(this)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG))?.let { 
            onTagDiscovered(it) 
        }
    }

    fun onTagDiscovered(tag: Tag) {
        val target = writeTarget
        if (target != null) {
            writeTag(tag, target)
            return
        }
        
        val info = readTag(tag)
        runOnUiThread {
            current = info
            details.text = format(info)
            save.isEnabled = true
            status.text = "Tag scanned successfully"
        }
    }
    private fun readTag(tag: Tag): TagInfo {
        val tech = tag.techList.map { it.substringAfterLast('.') }
        var type = "Unknown"
        val texts = mutableListOf<String>()
        var raw: ByteArray? = null
        
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                type = ndef.type ?: "NDEF"
                val msg = ndef.ndefMessage
                raw = msg?.toByteArray()
                msg?.records?.forEach { r -> 
                    texts += decodeRecord(r)
                }
                ndef.close()
            } catch (_: Exception) {
                try { 
                    ndef.close() 
                } catch (_: Exception) {}
            }
        }
        
        if (tech.any { it == "MifareClassic" }) {
            type = "MIFARE Classic (read/write requires sector keys)"
        } else if (type == "Unknown" && tech.isNotEmpty()) {
            type = tech.first()
        }
        
        return TagInfo(
            id = tag.id.joinToString("") { "%02X".format(it) },
            technologies = tech,
            type = type,
            records = texts,
            rawNdef = raw
        )
    }
    private fun decodeRecord(r: NdefRecord): String {
        return try {
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT)) {
                val p = r.payload
                String(p, 1, p.size - 1, Charset.forName("UTF-8"))
            } else {
                String(r.payload, Charset.forName("UTF-8"))
            }
        } catch (_: Exception) {
            "Binary record (${r.payload.size} bytes)"
        }
    }
    
    private fun format(t: TagInfo): String {
        return "ID: ${t.id}\nType: ${t.type}\nTechnology: ${t.technologies.joinToString(", ")}\nRecords:\n${if (t.records.isEmpty()) "(none)" else t.records.joinToString("\n")}"
    }

    private fun writeTag(tag: Tag, info: TagInfo) {
        writeTarget = null
        
        val bytes = info.rawNdef
        if (bytes == null) {
            runOnUiThread { 
                status.text = "This saved tag has no NDEF data; proprietary tags cannot be cloned." 
            }
            return
        }
        
        runOnUiThread { 
            status.text = "Writing tag..." 
        }
        
        try {
            val msg = NdefMessage(bytes)
            val n = Ndef.get(tag)
            
            if (n != null) {
                n.connect()
                if (!n.isWritable) throw Exception("Tag is read-only")
                if (n.maxSize < bytes.size) throw Exception("Not enough space")
                n.writeNdefMessage(msg)
                n.close()
            } else {
                val f = NdefFormatable.get(tag) ?: throw Exception("Tag is not NDEF writable")
                f.connect()
                f.format(msg)
                f.close()
            }
            
            runOnUiThread { 
                status.text = "Tag written successfully" 
            }
        } catch (e: Exception) {
            runOnUiThread { 
                status.text = "Write failed: ${e.message ?: "unsupported tag"}" 
            }
        }
    }
    private fun renderSaved() {
        savedContainer.removeAllViews()
        
        saved.forEach { info ->
            val v = LayoutInflater.from(this).inflate(R.layout.item_saved_tag, savedContainer, false)
            
            v.findViewById<TextView>(R.id.nameText).text = "Tag ${info.id}"
            v.findViewById<TextView>(R.id.summaryText).text = "${info.type} · ${info.technologies.joinToString()}"
            
            v.findViewById<Button>(R.id.writeButton).setOnClickListener {
                writeTarget = info
                status.text = "Hold a blank writable NDEF tag near the phone"
            }
            
            v.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                saved.remove(info)
                persist()
                renderSaved()
            }
            
            savedContainer.addView(v)
        }
    }

    private fun persist() {
        val a = JSONArray()
        saved.forEach { a.put(it.toJson()) }
        prefs.edit().putString("items", a.toString()).apply()
    }

    private fun loadSaved() {
        try {
            val a = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until a.length()) {
                saved += TagInfo.fromJson(a.getJSONObject(i))
            }
        } catch (_: Exception) {}
    }
}