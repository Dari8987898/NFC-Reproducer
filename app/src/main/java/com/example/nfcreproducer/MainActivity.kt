package com.example.nfcreproducer

import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {
    private var adapter: NfcAdapter? = null
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var saveButton: Button
    private lateinit var readerToggleButton: Button
    private lateinit var activeBadgeText: TextView
    private lateinit var emptySavedText: TextView
    private lateinit var savedContainer: LinearLayout

    private var current: TagInfo? = null
    private var readerEnabled = false
    private var activityResumed = false
    private val scanInProgress = AtomicBoolean(false)
    private val saved = mutableListOf<TagInfo>()
    private val prefs by lazy { getSharedPreferences("tags", MODE_PRIVATE) }
    private val hceSupported by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        details = findViewById(R.id.tagDetails)
        saveButton = findViewById(R.id.saveButton)
        readerToggleButton = findViewById(R.id.readerToggleButton)
        activeBadgeText = findViewById(R.id.activeBadgeText)
        emptySavedText = findViewById(R.id.emptySavedText)
        savedContainer = findViewById(R.id.savedContainer)

        readerEnabled = state?.getBoolean(STATE_READER_ENABLED) ?: false
        adapter = NfcAdapter.getDefaultAdapter(this)
        loadSaved()

        readerToggleButton.setOnClickListener { toggleReader() }
        saveButton.setOnClickListener { saveCurrentTag() }

        renderSaved()
        renderReaderControls()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_READER_ENABLED, readerEnabled)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        applyReaderMode()
        renderReaderControls()
        renderSaved()
    }

    override fun onPause() {
        activityResumed = false
        adapter?.disableReaderMode(this)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!readerEnabled) return
        intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)?.let(::onTagDiscovered)
    }

    override fun onTagDiscovered(tag: Tag) {
        if (!readerEnabled || !scanInProgress.compareAndSet(false, true)) return

        try {
            val info = readTag(tag)
            runOnUiThread {
                current = info
                details.text = formatTag(info)
                saveButton.isEnabled = true
                status.text = buildReadStatus(info)
            }
        } catch (error: Exception) {
            runOnUiThread {
                status.text = getString(
                    R.string.read_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
            }
        } finally {
            scanInProgress.set(false)
        }
    }

    private fun toggleReader() {
        val nfcAdapter = adapter
        if (nfcAdapter == null) {
            status.text = getString(R.string.no_nfc_support)
            return
        }
        if (!nfcAdapter.isEnabled) {
            status.text = getString(R.string.nfc_disabled)
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            return
        }

        readerEnabled = !readerEnabled
        if (readerEnabled) {
            // Android reader mode and HCE are mutually exclusive while this
            // activity is in the foreground, so scanning explicitly stops HCE.
            EmulationState.clear(this)
            status.text = getString(R.string.reader_on_status)
        } else {
            status.text = getString(R.string.reader_off_status)
        }
        applyReaderMode()
        renderReaderControls()
        renderSaved()
    }

    private fun applyReaderMode() {
        val nfcAdapter = adapter ?: return
        if (!activityResumed || !readerEnabled || !nfcAdapter.isEnabled) {
            nfcAdapter.disableReaderMode(this)
            return
        }

        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        nfcAdapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            options
        )
    }

    private fun renderReaderControls() {
        val nfcAdapter = adapter
        when {
            nfcAdapter == null -> {
                readerToggleButton.isEnabled = false
                readerToggleButton.text = getString(R.string.reader_unavailable_button)
                status.text = getString(R.string.no_nfc_support)
            }
            !nfcAdapter.isEnabled -> {
                readerToggleButton.isEnabled = true
                readerToggleButton.text = getString(R.string.enable_nfc_button)
                status.text = getString(R.string.nfc_disabled)
            }
            readerEnabled -> {
                readerToggleButton.isEnabled = true
                readerToggleButton.text = getString(R.string.stop_reading_button)
            }
            else -> {
                readerToggleButton.isEnabled = true
                readerToggleButton.text = getString(R.string.start_reading_button)
            }
        }
    }

    private fun readTag(tag: Tag): TagInfo {
        val technologies = tag.techList.map { it.substringAfterLast('.') }
        val records = mutableListOf<String>()
        var type = technologies.firstOrNull() ?: getString(R.string.unknown_tag)
        var rawNdef: ByteArray? = null

        Ndef.get(tag)?.let { ndef ->
            try {
                ndef.connect()
                type = ndef.type ?: "NDEF"
                ndef.ndefMessage?.let { message ->
                    rawNdef = message.toByteArray()
                    message.records.forEach { records += decodeRecord(it) }
                }
            } catch (_: Exception) {
                // A MIFARE dump is still attempted below when NDEF is absent.
            } finally {
                try {
                    ndef.close()
                } catch (_: Exception) {
                    // Already closed or never connected.
                }
            }
        }

        val mifareDump = if (technologies.contains("MifareClassic")) {
            readMifareClassic(tag)
        } else {
            null
        }

        if (mifareDump != null) {
            type = when (mifareDump.sizeBytes) {
                MifareClassic.SIZE_MINI -> "MIFARE Classic Mini"
                MifareClassic.SIZE_1K -> "MIFARE Classic 1K"
                MifareClassic.SIZE_2K -> "MIFARE Classic 2K"
                MifareClassic.SIZE_4K -> "MIFARE Classic 4K"
                else -> "MIFARE Classic"
            }

            if (rawNdef == null) {
                extractNdefFromTlv(mifareDump)?.let { recovered ->
                    try {
                        val message = NdefMessage(recovered)
                        rawNdef = recovered
                        records.clear()
                        message.records.forEach { records += decodeRecord(it) }
                    } catch (_: Exception) {
                        // Arbitrary Classic data is kept even when it is not NDEF.
                    }
                }
            }
        }

        return TagInfo(
            id = tag.id.joinToString("") { "%02X".format(it.toInt() and 0xFF) },
            technologies = technologies,
            type = type,
            records = records,
            rawNdef = rawNdef,
            mifareDump = mifareDump
        )
    }

    private fun readMifareClassic(tag: Tag): MifareDump? {
        val mifare = MifareClassic.get(tag) ?: return null
        val blocks = mutableListOf<MifareBlock>()
        val unreadableSectors = mutableListOf<Int>()
        var sizeBytes = 0
        var sectorCount = 0

        try {
            mifare.connect()
            sizeBytes = mifare.size
            sectorCount = mifare.sectorCount

            for (sector in 0 until sectorCount) {
                if (!authenticateWithDefaultKeys(mifare, sector)) {
                    unreadableSectors += sector
                    continue
                }

                val firstBlock = mifare.sectorToBlock(sector)
                val dataBlockCount = mifare.getBlockCountInSector(sector) - 1
                var sectorRead = false
                for (offset in 0 until dataBlockCount) {
                    val absoluteBlock = firstBlock + offset
                    try {
                        blocks += MifareBlock(
                            sector = sector,
                            block = absoluteBlock,
                            data = mifare.readBlock(absoluteBlock)
                        )
                        sectorRead = true
                    } catch (_: Exception) {
                        // Keep every readable block; a single failed block must
                        // not discard the rest of the badge snapshot.
                    }
                }
                if (!sectorRead) unreadableSectors += sector
            }
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {
                // Nothing to close.
            }
        }

        return MifareDump(
            sizeBytes = sizeBytes,
            sectorCount = sectorCount,
            blocks = blocks,
            unreadableSectors = unreadableSectors.distinct()
        )
    }

    private fun authenticateWithDefaultKeys(mifare: MifareClassic, sector: Int): Boolean {
        DEFAULT_KEYS.forEach { key ->
            try {
                if (mifare.authenticateSectorWithKeyA(sector, key)) return true
            } catch (_: Exception) {
                // Try the remaining standard keys and Key B.
            }
            try {
                if (mifare.authenticateSectorWithKeyB(sector, key)) return true
            } catch (_: Exception) {
                // Try the next key.
            }
        }
        return false
    }

    /** Parses a standard NDEF TLV (type 0x03) if one is present in captured data blocks. */
    private fun extractNdefFromTlv(dump: MifareDump): ByteArray? {
        val memory = dump.blocks
            .filter { it.sector > 0 }
            .sortedBy { it.block }
            .fold(ByteArray(0)) { accumulated, block -> accumulated + block.data }

        var index = 0
        while (index < memory.size) {
            val type = memory[index].toInt() and 0xFF
            if (type == 0x00) {
                index++
                continue
            }
            if (type == 0xFE) return null
            if (index + 1 >= memory.size) return null

            var length = memory[index + 1].toInt() and 0xFF
            var valueStart = index + 2
            if (length == 0xFF) {
                if (index + 3 >= memory.size) return null
                length = ((memory[index + 2].toInt() and 0xFF) shl 8) or
                    (memory[index + 3].toInt() and 0xFF)
                valueStart = index + 4
            }
            if (valueStart + length > memory.size) return null
            if (type == 0x03) return memory.copyOfRange(valueStart, valueStart + length)
            index = valueStart + length
        }
        return null
    }

    private fun decodeRecord(record: NdefRecord): String {
        return try {
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT) &&
                record.payload.isNotEmpty()
            ) {
                val statusByte = record.payload[0].toInt() and 0xFF
                val languageLength = statusByte and 0x3F
                val charset = if ((statusByte and 0x80) == 0) {
                    StandardCharsets.UTF_8
                } else {
                    StandardCharsets.UTF_16
                }
                val textStart = 1 + languageLength
                String(record.payload, textStart, record.payload.size - textStart, charset)
            } else {
                String(record.payload, Charset.forName("UTF-8"))
            }
        } catch (_: Exception) {
            getString(R.string.binary_record, record.payload.size)
        }
    }

    private fun saveCurrentTag() {
        val scanned = current ?: return
        val existingIndex = saved.indexOfFirst { it.id == scanned.id }
        val item = if (existingIndex >= 0) {
            scanned.copy(displayName = saved[existingIndex].displayName)
        } else {
            scanned
        }

        if (existingIndex >= 0) saved[existingIndex] = item else saved += item
        if (EmulationState.activeId(this) == item.id) EmulationState.setActive(this, item)
        persist()
        renderSaved()
        status.text = if (existingIndex >= 0) {
            getString(R.string.badge_updated)
        } else {
            getString(R.string.badge_saved)
        }
    }

    private fun renderSaved() {
        savedContainer.removeAllViews()
        emptySavedText.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE

        val activeId = EmulationState.activeId(this)
        val active = saved.firstOrNull { it.id == activeId }
        activeBadgeText.text = active?.let {
            getString(R.string.active_badge, displayName(it))
        } ?: getString(R.string.no_active_badge)

        saved.forEach { info ->
            val view = LayoutInflater.from(this)
                .inflate(R.layout.item_saved_tag, savedContainer, false)
            val isActive = info.id == activeId
            val dump = info.mifareDump

            view.findViewById<TextView>(R.id.nameText).text = displayName(info)
            view.findViewById<TextView>(R.id.summaryText).text = when {
                dump != null -> getString(
                    R.string.mifare_card_summary,
                    dump.readableSectorCount,
                    dump.sectorCount,
                    dump.blocks.size
                )
                info.rawNdef != null -> getString(R.string.ndef_card_summary, info.rawNdef.size)
                else -> getString(R.string.generic_card_summary, info.type)
            }

            view.findViewById<Button>(R.id.usePhoneButton).apply {
                isEnabled = hceSupported
                text = getString(if (isActive) R.string.deactivate_button else R.string.use_phone_button)
                setOnClickListener {
                    if (isActive) {
                        EmulationState.clear(this@MainActivity)
                        status.text = getString(R.string.hce_stopped)
                    } else {
                        readerEnabled = false
                        applyReaderMode()
                        EmulationState.setActive(this@MainActivity, info)
                        status.text = getString(R.string.hce_started, displayName(info))
                    }
                    renderReaderControls()
                    renderSaved()
                }
            }

            view.findViewById<Button>(R.id.renameButton).setOnClickListener {
                showRenameDialog(info)
            }
            view.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                if (EmulationState.activeId(this) == info.id) EmulationState.clear(this)
                saved.removeAll { it.id == info.id }
                persist()
                renderSaved()
                status.text = getString(R.string.badge_deleted)
            }

            savedContainer.addView(view)
        }
    }

    private fun showRenameDialog(info: TagInfo) {
        val input = EditText(this).apply {
            setText(displayName(info))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.badge_name_hint)
        }
        val container = FrameLayout(this).apply {
            val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.rename_dialog_title)
            .setView(container)
            .setNegativeButton(R.string.cancel_button, null)
            .setPositiveButton(R.string.save_name_button) { _, _ ->
                val index = saved.indexOfFirst { it.id == info.id }
                if (index >= 0) {
                    val renamed = saved[index].copy(displayName = input.text.toString().trim())
                    saved[index] = renamed
                    if (EmulationState.activeId(this) == renamed.id) EmulationState.setActive(this, renamed)
                    persist()
                    renderSaved()
                    status.text = getString(R.string.name_updated)
                }
            }
            .show()
    }

    private fun formatTag(tag: TagInfo): String {
        val lines = mutableListOf(
            getString(R.string.detail_uid, tag.id),
            getString(R.string.detail_type, tag.type),
            getString(R.string.detail_technologies, tag.technologies.joinToString(", "))
        )
        tag.mifareDump?.let { dump ->
            lines += getString(
                R.string.detail_mifare,
                dump.readableSectorCount,
                dump.sectorCount,
                dump.blocks.size
            )
            if (dump.unreadableSectors.isNotEmpty()) {
                lines += getString(
                    R.string.detail_unreadable_sectors,
                    dump.unreadableSectors.joinToString(", ")
                )
            }
        }
        lines += if (tag.rawNdef != null) {
            getString(R.string.detail_ndef, tag.rawNdef.size)
        } else {
            getString(R.string.detail_no_ndef)
        }
        if (tag.records.isNotEmpty()) {
            lines += getString(R.string.detail_records, tag.records.joinToString("\n"))
        }
        return lines.joinToString("\n")
    }

    private fun buildReadStatus(tag: TagInfo): String {
        val dump = tag.mifareDump
        return if (dump != null) {
            getString(
                R.string.mifare_read_success,
                dump.readableSectorCount,
                dump.sectorCount,
                dump.blocks.size
            )
        } else {
            getString(R.string.tag_read_success)
        }
    }

    private fun displayName(info: TagInfo): String =
        info.displayName.ifBlank { getString(R.string.default_badge_name, info.id) }

    private fun persist() {
        val serialized = JSONArray().apply { saved.forEach { put(it.toJson()) } }
        prefs.edit().putString(PREF_ITEMS, serialized.toString()).apply()
    }

    private fun loadSaved() {
        try {
            val serialized = JSONArray(prefs.getString(PREF_ITEMS, "[]"))
            for (index in 0 until serialized.length()) {
                saved += TagInfo.fromJson(serialized.getJSONObject(index))
            }
        } catch (_: Exception) {
            saved.clear()
        }
    }

    companion object {
        private const val PREF_ITEMS = "items"
        private const val STATE_READER_ENABLED = "reader_enabled"

        private val DEFAULT_KEYS = listOf(
            MifareClassic.KEY_DEFAULT,
            MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY,
            MifareClassic.KEY_NFC_FORUM
        )
    }
}
