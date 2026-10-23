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
 * Global immutable base layer. It is intentionally outside MemoryProfileManager archives.
 *
 * Step 46 adds a staged installer for externally converted knowledge. Conversion is performed
 * before this repository is touched; only a validated [CompiledBasePack] can reach install().
 */
class BaseKnowledgeRepository(private val context: Context) {
    data class Manifest(
        val packId: String = "",
        val version: Int = 0,
        val stableIds: Boolean = true,
        val sourceId: String = "",
        val sourceVersion: String = "",
        val macroCount: Int = 0,
        val relationCount: Int = 0,
        val importedAtMs: Long = 0L
    ) {
        val installed: Boolean get() = packId.isNotBlank() && version > 0
    }

    data class InstallResult(
        val success: Boolean,
        val manifest: Manifest = Manifest(),
        val message: String = ""
    )

    val store = IndexedLongTermStore(context, STORE_DIR)
    private val root = File(context.filesDir, ROOT_DIR).apply { mkdirs() }
    private val manifestFile = File(root, MANIFEST_FILE)
    private val disabledFlag = File(root, DISABLED_FLAG)

    fun isEnabled(): Boolean = !disabledFlag.exists()

    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (enabled) disabledFlag.delete() else disabledFlag.writeText("blank-mode")
        true
    }.getOrDefault(false)

    fun manifest(): Manifest {
        if (!manifestFile.exists()) return Manifest()
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(manifestFile))).use { input ->
                require(input.readInt() == MAGIC)
                val format = input.readInt()
                require(format in 1..FORMAT_VERSION)
                val packId = input.readUTF()
                val version = input.readInt()
                val stableIds = input.readBoolean()
                if (format >= 2) {
                    Manifest(
                        packId = packId,
                        version = version,
                        stableIds = stableIds,
                        sourceId = input.readUTF(),
                        sourceVersion = input.readUTF(),
                        macroCount = input.readInt().coerceAtLeast(0),
                        relationCount = input.readInt().coerceAtLeast(0),
                        importedAtMs = input.readLong().coerceAtLeast(0L)
                    )
                } else Manifest(packId, version, stableIds)
            }
        }.getOrElse { Manifest() }
    }

    fun writeManifest(
        packId: String,
        version: Int,
        stableIds: Boolean = true,
        sourceId: String = "",
        sourceVersion: String = "",
        macroCount: Int = 0,
        relationCount: Int = 0,
        importedAtMs: Long = System.currentTimeMillis()
    ): Boolean = runCatching {
        require(packId.isNotBlank())
        require(version > 0)
        root.mkdirs()
        val temp = File(root, "$MANIFEST_FILE.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeUTF(packId.take(128))
            out.writeInt(version)
            out.writeBoolean(stableIds)
            out.writeUTF(sourceId.take(256))
            out.writeUTF(sourceVersion.take(128))
            out.writeInt(macroCount.coerceAtLeast(0))
            out.writeInt(relationCount.coerceAtLeast(0))
            out.writeLong(importedAtMs.coerceAtLeast(0L))
        }
        if (manifestFile.exists()) manifestFile.delete()
        require(temp.renameTo(manifestFile))
        true
    }.getOrDefault(false)

    /**
     * Transaction-like staged install. The active base store is never partially rewritten.
     * On any failure, the previous store + manifest are restored.
     */
    @Synchronized
    fun install(pack: CompiledBasePack): InstallResult {
        if (pack.packId.isBlank() || pack.version <= 0) return InstallResult(false, message = "Ungültige Base-Metadaten")
        if (pack.macros.isEmpty()) return InstallResult(false, message = "Base-Pack enthält keine Makros")
        val current = manifest()
        if (current.installed && current.packId == pack.packId && pack.version < current.version) {
            return InstallResult(false, current, "Downgrade blockiert: installiert ist ${current.packId} v${current.version}")
        }
        if (pack.macros.any { !KnowledgeIdSpace.isBaseMacroId(it.id) }) {
            return InstallResult(false, message = "Base-Pack enthält IDs außerhalb des reservierten BASE-Bereichs")
        }
        if (pack.macros.map { it.id }.toSet().size != pack.macros.size) {
            return InstallResult(false, message = "Base-Pack enthält doppelte Makro-IDs")
        }
        val ids = pack.macros.mapTo(HashSet()) { it.id }
        if (pack.macros.any { macro -> macro.level > 1 && macro.tokens.any { it !in ids } }) {
            return InstallResult(false, message = "Base-Pack enthält unaufgelöste höhere Makro-Referenzen")
        }

        val stageName = "$ROOT_DIR/store_stage"
        val stageStore = IndexedLongTermStore(context, stageName)
        stageStore.deleteAll()
        val stageResult = runCatching {
            stageStore.writeSnapshot(
                meta = IndexedLongTermStore.Meta(0L, KnowledgeIdSpace.BASE_MACRO_MIN, true),
                quants = emptyList(),
                macros = pack.macros,
                relations = pack.relations,
                exchanges = emptyList()
            )
        }.getOrElse {
            stageStore.deleteAll()
            return InstallResult(false, message = "Staging fehlgeschlagen: ${it.message ?: it::class.java.simpleName}")
        }

        val headers = stageStore.readMacroHeaders()
        if (headers.size != pack.macros.size || stageStore.readRelations().size != pack.relations.size) {
            stageStore.deleteAll()
            return InstallResult(false, message = "Staging-Validierung fehlgeschlagen")
        }
        // Touch every token once during validation so truncated token payloads cannot be installed.
        val tokenValid = runCatching {
            headers.forEach { h ->
                val tokens = stageStore.tokenList(h.tokenOffsetBytes, h.tokenCount)
                for (i in tokens.indices) tokens[i]
            }
            true
        }.getOrDefault(false)
        if (!tokenValid) {
            stageStore.deleteAll()
            return InstallResult(false, message = "Token-Validierung fehlgeschlagen")
        }

        val liveDir = store.rootDirectory()
        val stageDir = stageStore.rootDirectory()
        val backupDir = File(root, "store_backup")
        val oldManifest = if (manifestFile.exists()) manifestFile.readBytes() else null
        backupDir.deleteRecursively()

        return try {
            if (liveDir.exists() && !liveDir.renameTo(backupDir)) error("Bestehende Base konnte nicht gesichert werden")
            if (!stageDir.renameTo(liveDir)) error("Neue Base konnte nicht aktiviert werden")
            val ok = writeManifest(
                packId = pack.packId,
                version = pack.version,
                stableIds = true,
                sourceId = pack.sourceId,
                sourceVersion = pack.sourceVersion,
                macroCount = pack.macros.size,
                relationCount = pack.relations.size
            )
            if (!ok) error("Manifest konnte nicht geschrieben werden")
            backupDir.deleteRecursively()
            val installed = manifest()
            InstallResult(true, installed, "${installed.packId} v${installed.version} installiert")
        } catch (e: Exception) {
            liveDir.deleteRecursively()
            if (backupDir.exists()) backupDir.renameTo(liveDir)
            if (oldManifest != null) manifestFile.writeBytes(oldManifest) else manifestFile.delete()
            stageDir.deleteRecursively()
            InstallResult(false, manifest(), "Install fehlgeschlagen: ${e.message ?: e::class.java.simpleName}")
        } finally {
            // stageResult is intentionally used to force a completed consistent checkpoint above.
            if (stageResult.bytes < 0L) stageStore.deleteAll()
        }
    }

    companion object {
        const val ROOT_DIR = "equinox_v06_base"
        const val STORE_DIR = "$ROOT_DIR/store"
        private const val MANIFEST_FILE = "manifest.bin"
        private const val DISABLED_FLAG = "disabled.flag"
        private const val MAGIC = 0x4551424D // EQBM
        private const val FORMAT_VERSION = 2
    }
}
