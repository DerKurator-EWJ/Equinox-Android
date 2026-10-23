package de.equinox.v05

import android.content.Context
import org.json.JSONObject
import java.io.File

data class SaveSlotInfo(
    val slot: Int,
    val exists: Boolean,
    val bytes: Long,
    val modified: Long
)

class SaveSlotManager(private val context: Context) {
    private val liveFile = File(context.filesDir, "equinox_v06_memory.json")

    fun info(slot: Int): SaveSlotInfo {
        val f = slotFile(slot)
        return SaveSlotInfo(
            slot = slot,
            exists = f.exists(),
            bytes = if (f.exists()) f.length() else 0L,
            modified = if (f.exists()) f.lastModified() else 0L
        )
    }

    fun saveToSlot(slot: Int): Boolean {
        if (!liveFile.exists()) return false
        return try {
            liveFile.copyTo(slotFile(slot), overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun loadFromSlot(slot: Int): Boolean {
        val src = slotFile(slot)
        if (!src.exists()) return false
        return try {
            src.copyTo(liveFile, overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deleteSlot(slot: Int): Boolean {
        val f = slotFile(slot)
        return !f.exists() || f.delete()
    }

    fun exportSummary(slot: Int): String {
        val f = slotFile(slot)
        if (!f.exists()) return "Slot $slot leer"
        return try {
            val root = JSONObject(f.readText())
            val quants = root.optJSONArray("quants")?.length() ?: 0
            val macros = root.optJSONArray("macros")?.length() ?: 0
            val relations = root.optJSONArray("macroRelations")?.length() ?: 0
            "Slot $slot · $quants Quanten · $macros Makros · $relations Relationen · ${f.length()} Bytes"
        } catch (_: Exception) {
            "Slot $slot · Datei vorhanden · ${f.length()} Bytes"
        }
    }

    private fun slotFile(slot: Int): File =
        File(context.filesDir, "equinox_v06_slot_${slot.coerceIn(1, 3)}.json")
}
