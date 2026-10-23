package de.equinox.v05

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Profile-scoped provenance sidecar.
 *
 * It is deliberately separate from IndexedLongTermStore v1, so every Step 23-44 profile stays
 * readable without migration. Missing sidecar => legacy macros are PERSONAL.
 */
class KnowledgeLayerStore(context: Context) {
    data class Snapshot(
        val requiredBasePackId: String = "",
        val requiredBaseVersion: Int = 0,
        val entries: Map<Int, KnowledgeProvenance> = emptyMap()
    )

    private val file = File(context.filesDir, FILE_NAME)

    fun exists(): Boolean = file.exists()

    fun delete() { file.delete() }

    fun read(): Snapshot {
        if (!file.exists()) return Snapshot()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == MAGIC) { "Bad Equinox knowledge-layer magic" }
                val version = input.readInt()
                require(version in 1..FORMAT_VERSION) { "Unsupported knowledge-layer version $version" }
                val packId = input.readUTF()
                val packVersion = input.readInt()
                val count = input.readInt().coerceAtLeast(0)
                val map = LinkedHashMap<Int, KnowledgeProvenance>(count)
                repeat(count) {
                    val id = input.readInt()
                    val origin = KnowledgeOrigin.fromCode(input.readUnsignedByte())
                    val sourceCount = input.readInt().coerceIn(0, MAX_SOURCE_IDS)
                    val sources = ArrayList<Int>(sourceCount)
                    repeat(sourceCount) { sources += input.readInt() }
                    // BASE is global and should never be owned by the profile sidecar.
                    if (origin != KnowledgeOrigin.BASE) map[id] = KnowledgeProvenance(origin, sources)
                }
                Snapshot(packId, packVersion, map)
            }
        }.getOrElse { Snapshot() }
    }

    fun write(snapshot: Snapshot): Boolean = runCatching {
        val temp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(snapshot.requiredBasePackId.take(128))
            out.writeInt(snapshot.requiredBaseVersion.coerceAtLeast(0))
            val entries = snapshot.entries.entries.filter { it.value.origin != KnowledgeOrigin.BASE }
            out.writeInt(entries.size)
            entries.forEach { (id, provenance) ->
                out.writeInt(id)
                out.writeByte(provenance.origin.code)
                val sources = provenance.sourceMacroIds.distinct().take(MAX_SOURCE_IDS)
                out.writeInt(sources.size)
                sources.forEach(out::writeInt)
            }
        }
        if (file.exists()) file.delete()
        require(temp.renameTo(file)) { "Could not commit Equinox knowledge-layer sidecar" }
        true
    }.getOrDefault(false)

    companion object {
        const val FILE_NAME = "equinox_v06_knowledge_layers.bin"
        private const val MAGIC = 0x45514B4C // EQKL
        private const val FORMAT_VERSION = 1
        private const val MAX_SOURCE_IDS = 64
    }
}
