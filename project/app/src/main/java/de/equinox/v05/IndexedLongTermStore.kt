package de.equinox.v05

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Step 23: compact indexed on-device store.
 *
 * The persistent graph is split into small typed files instead of one monolithic JSON blob.
 * Macro headers are indexed at startup, while their token payload stays in tokens.bin and is
 * fetched lazily through [DiskTokenList]. No external database/library is required.
 *
 * This is intentionally a storage concern only: it does not alter learning, rank, recall,
 * prediction, DREAM, cross-modal semantics or overexchange math.
 */
class IndexedLongTermStore(context: Context, storeDirName: String = STORE_DIR) {
    data class Meta(
        val observations: Long,
        val nextMacroId: Int,
        val macroFrozen: Boolean
    )

    data class StoredQuant(
        val id: Int,
        val weight: Long,
        val hz: Double,
        val modality: String,
        val displayColor: Int
    )

    data class StoredMacro(
        val id: Int,
        val level: Int,
        val modality: String,
        val tokens: List<Int>,
        val weight: Long,
        val hz: Double,
        val firstObs: Long,
        val lastObs: Long,
        val signature: String
    )

    data class MacroHeader(
        val id: Int,
        val level: Int,
        val modality: String,
        val weight: Long,
        val hz: Double,
        val firstObs: Long,
        val lastObs: Long,
        val tokenOffsetBytes: Long,
        val tokenCount: Int,
        val signature: String
    )

    data class StoredRelation(val key: Long, val weight: Long)
    data class StoredExchange(val key: Long, val ema: Double, val samples: Long)
    data class MacroLocation(val offsetBytes: Long, val tokenCount: Int)
    data class WriteResult(val macroLocations: Map<Int, MacroLocation>, val bytes: Long)
    data class StoreStats(
        val exists: Boolean,
        val bytes: Long,
        val macroHeaders: Int,
        val tokenReads: Long
    )

    private val root = File(context.filesDir, storeDirName)
    private val metaFile get() = File(root, META_FILE)
    private val quantFile get() = File(root, QUANT_FILE)
    private val macroFile get() = File(root, MACRO_FILE)
    private val tokenFile get() = File(root, TOKEN_FILE)
    private val relationFile get() = File(root, RELATION_FILE)
    private val exchangeFile get() = File(root, EXCHANGE_FILE)
    private val tokenReadCounter = AtomicLong(0L)

    fun exists(): Boolean = metaFile.exists() && macroFile.exists() && tokenFile.exists()

    fun rootDirectory(): File = root

    fun stats(): StoreStats = StoreStats(
        exists = exists(),
        bytes = directoryBytes(root),
        macroHeaders = runCatching { macroCountOnly() }.getOrDefault(0),
        tokenReads = tokenReadCounter.get()
    )

    fun deleteAll() {
        root.deleteRecursively()
    }

    fun readMeta(): Meta? {
        if (!metaFile.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(metaFile))).use { input ->
                require(input.readInt() == MAGIC_META) { "Bad Equinox meta magic" }
                val version = input.readInt()
                require(version in 1..STORE_VERSION) { "Unsupported Equinox store version $version" }
                Meta(
                    observations = input.readLong(),
                    nextMacroId = input.readInt(),
                    macroFrozen = input.readBoolean()
                )
            }
        }.getOrNull()
    }

    fun readQuants(): List<StoredQuant> {
        if (!quantFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(quantFile))).use { input ->
                require(input.readInt() == MAGIC_QUANT)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredQuant>(count).apply {
                    repeat(count) {
                        add(
                            StoredQuant(
                                id = input.readInt(),
                                weight = input.readLong(),
                                hz = input.readDouble(),
                                modality = input.readUTF(),
                                displayColor = input.readInt()
                            )
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readMacroHeaders(): List<MacroHeader> {
        if (!macroFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(macroFile))).use { input ->
                require(input.readInt() == MAGIC_MACRO)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<MacroHeader>(count).apply {
                    repeat(count) {
                        add(
                            MacroHeader(
                                id = input.readInt(),
                                level = input.readInt(),
                                modality = input.readUTF(),
                                weight = input.readLong(),
                                hz = input.readDouble(),
                                firstObs = input.readLong(),
                                lastObs = input.readLong(),
                                tokenOffsetBytes = input.readLong(),
                                tokenCount = input.readInt(),
                                signature = input.readUTF()
                            )
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readRelations(): List<StoredRelation> {
        if (!relationFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(relationFile))).use { input ->
                require(input.readInt() == MAGIC_RELATION)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredRelation>(count).apply {
                    repeat(count) { add(StoredRelation(input.readLong(), input.readLong())) }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun readExchanges(): List<StoredExchange> {
        if (!exchangeFile.exists()) return emptyList()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(exchangeFile))).use { input ->
                require(input.readInt() == MAGIC_EXCHANGE)
                val count = input.readInt().coerceAtLeast(0)
                ArrayList<StoredExchange>(count).apply {
                    repeat(count) {
                        add(StoredExchange(input.readLong(), input.readDouble(), input.readLong()))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    /** A disk-backed immutable list. Size checks do not touch disk; individual values do. */
    fun tokenList(offsetBytes: Long, count: Int): List<Int> = DiskTokenList(this, offsetBytes, count)

    internal fun readToken(offsetBytes: Long, index: Int): Int {
        tokenReadCounter.incrementAndGet()
        RandomAccessFile(tokenFile, "r").use { raf ->
            raf.seek(offsetBytes + index.toLong() * 4L)
            return raf.readInt()
        }
    }

    /**
     * Writes a complete consistent checkpoint into a sibling temp directory and swaps it in.
     * Existing disk-backed token lists keep reading the old store while the new checkpoint is
     * being produced. The caller can then rebind them using the returned offsets.
     */
    fun writeSnapshot(
        meta: Meta,
        quants: Collection<StoredQuant>,
        macros: Collection<StoredMacro>,
        relations: Collection<StoredRelation>,
        exchanges: Collection<StoredExchange>
    ): WriteResult {
        val parent = root.parentFile ?: error("No filesDir parent")
        val temp = File(parent, "${STORE_DIR}_tmp")
        val backup = File(parent, "${STORE_DIR}_bak")
        temp.deleteRecursively()
        temp.mkdirs()

        val locations = linkedMapOf<Int, MacroLocation>()

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, META_FILE)))).use { out ->
            out.writeInt(MAGIC_META)
            out.writeInt(STORE_VERSION)
            out.writeLong(meta.observations)
            out.writeInt(meta.nextMacroId)
            out.writeBoolean(meta.macroFrozen)
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, QUANT_FILE)))).use { out ->
            out.writeInt(MAGIC_QUANT)
            out.writeInt(quants.size)
            quants.forEach { q ->
                out.writeInt(q.id)
                out.writeLong(q.weight)
                out.writeDouble(q.hz)
                out.writeUTF(q.modality)
                out.writeInt(q.displayColor)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, TOKEN_FILE)))).use { tokenOut ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, MACRO_FILE)))).use { macroOut ->
                macroOut.writeInt(MAGIC_MACRO)
                macroOut.writeInt(macros.size)
                var tokenOffset = 0L
                macros.forEach { m ->
                    val count = m.tokens.size
                    val location = MacroLocation(tokenOffset, count)
                    locations[m.id] = location

                    macroOut.writeInt(m.id)
                    macroOut.writeInt(m.level)
                    macroOut.writeUTF(m.modality)
                    macroOut.writeLong(m.weight)
                    macroOut.writeDouble(m.hz)
                    macroOut.writeLong(m.firstObs)
                    macroOut.writeLong(m.lastObs)
                    macroOut.writeLong(location.offsetBytes)
                    macroOut.writeInt(count)
                    macroOut.writeUTF(m.signature)

                    for (i in 0 until count) tokenOut.writeInt(m.tokens[i])
                    tokenOffset += count.toLong() * 4L
                }
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, RELATION_FILE)))).use { out ->
            out.writeInt(MAGIC_RELATION)
            out.writeInt(relations.size)
            relations.forEach { r ->
                out.writeLong(r.key)
                out.writeLong(r.weight)
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(File(temp, EXCHANGE_FILE)))).use { out ->
            out.writeInt(MAGIC_EXCHANGE)
            out.writeInt(exchanges.size)
            exchanges.forEach { e ->
                out.writeLong(e.key)
                out.writeDouble(e.ema)
                out.writeLong(e.samples)
            }
        }

        backup.deleteRecursively()
        if (root.exists() && !root.renameTo(backup)) {
            temp.deleteRecursively()
            error("Could not rotate Equinox long-term store")
        }
        if (!temp.renameTo(root)) {
            if (backup.exists()) backup.renameTo(root)
            error("Could not install Equinox long-term store")
        }
        backup.deleteRecursively()

        return WriteResult(locations, directoryBytes(root))
    }

    private fun macroCountOnly(): Int {
        if (!macroFile.exists()) return 0
        DataInputStream(BufferedInputStream(FileInputStream(macroFile))).use { input ->
            require(input.readInt() == MAGIC_MACRO)
            return input.readInt().coerceAtLeast(0)
        }
    }

    private fun directoryBytes(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private class DiskTokenList(
        private val store: IndexedLongTermStore,
        private val offsetBytes: Long,
        override val size: Int
    ) : AbstractList<Int>() {
        override fun get(index: Int): Int {
            if (index !in 0 until size) throw IndexOutOfBoundsException("$index / $size")
            return store.readToken(offsetBytes, index)
        }
    }

    companion object {
        const val STORE_DIR = "equinox_v06_store"
        private const val STORE_VERSION = 1
        private const val META_FILE = "meta.bin"
        private const val QUANT_FILE = "quants.bin"
        private const val MACRO_FILE = "macros.idx"
        private const val TOKEN_FILE = "tokens.bin"
        private const val RELATION_FILE = "relations.bin"
        private const val EXCHANGE_FILE = "exchange.bin"

        private const val MAGIC_META = 0x45514D54 // EQMT
        private const val MAGIC_QUANT = 0x45515154 // EQQT
        private const val MAGIC_MACRO = 0x45514D43 // EQMC
        private const val MAGIC_RELATION = 0x4551524C // EQRL
        private const val MAGIC_EXCHANGE = 0x45514558 // EQEX
    }
}
