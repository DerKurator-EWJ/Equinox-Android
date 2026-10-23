package de.equinox.v05

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SaveSlotInfo(
    val slot: Int,
    val exists: Boolean,
    val bytes: Long,
    val modified: Long
)

/** Step 23 save slots archive the indexed store plus both adaptive sensor codebooks. */
class SaveSlotManager(private val context: Context) {
    private val liveStore = File(context.filesDir, IndexedLongTermStore.STORE_DIR)
    private val companionFiles = listOf(
        "equinox_v06_adaptive_quantizer.json",
        "equinox_v06_adaptive_visual_quantizer.json"
    )

    fun info(slot: Int): SaveSlotInfo {
        val f = slotFile(slot)
        return SaveSlotInfo(slot, f.exists(), if (f.exists()) f.length() else 0L, if (f.exists()) f.lastModified() else 0L)
    }

    fun saveToSlot(slot: Int): Boolean {
        if (!liveStore.exists()) return false
        return runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(slotFile(slot)))).use { zip ->
                liveStore.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.relativeTo(context.filesDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                companionFiles.forEach { name ->
                    val file = File(context.filesDir, name)
                    if (!file.exists()) return@forEach
                    zip.putNextEntry(ZipEntry(name))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun loadFromSlot(slot: Int): Boolean {
        val src = slotFile(slot)
        if (!src.exists()) return false
        return runCatching {
            liveStore.deleteRecursively()
            companionFiles.forEach { File(context.filesDir, it).delete() }

            ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(context.filesDir, entry.name)
                    val canonicalRoot = context.filesDir.canonicalFile
                    val canonicalTarget = target.canonicalFile
                    require(canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) { "Unsafe slot path" }
                    if (entry.isDirectory) {
                        canonicalTarget.mkdirs()
                    } else {
                        canonicalTarget.parentFile?.mkdirs()
                        FileOutputStream(canonicalTarget).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            true
        }.getOrDefault(false)
    }

    fun deleteSlot(slot: Int): Boolean {
        val f = slotFile(slot)
        return !f.exists() || f.delete()
    }

    fun exportSummary(slot: Int): String {
        val f = slotFile(slot)
        if (!f.exists()) return "Slot $slot leer"
        return "Slot $slot · Indexed-Archiv · ${f.length()} Bytes"
    }

    private fun slotFile(slot: Int): File =
        File(context.filesDir, "equinox_v06_slot_${slot.coerceIn(1, 3)}.zip")
}
