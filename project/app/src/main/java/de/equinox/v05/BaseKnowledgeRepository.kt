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
 * Step 45 only mounts/validates this layer; Step 46 will provide the external import/conversion API.
 */
class BaseKnowledgeRepository(private val context: Context) {
    data class Manifest(
        val packId: String = "",
        val version: Int = 0,
        val stableIds: Boolean = true
    ) {
        val installed: Boolean get() = packId.isNotBlank() && version > 0
    }

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
                require(input.readInt() in 1..FORMAT_VERSION)
                Manifest(
                    packId = input.readUTF(),
                    version = input.readInt(),
                    stableIds = input.readBoolean()
                )
            }
        }.getOrElse { Manifest() }
    }

    /** Step 46 hook: installs metadata only after a converted base store has been validated. */
    fun writeManifest(packId: String, version: Int, stableIds: Boolean = true): Boolean = runCatching {
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
        }
        if (manifestFile.exists()) manifestFile.delete()
        require(temp.renameTo(manifestFile))
        true
    }.getOrDefault(false)

    companion object {
        const val ROOT_DIR = "equinox_v06_base"
        const val STORE_DIR = "$ROOT_DIR/store"
        private const val MANIFEST_FILE = "manifest.bin"
        private const val DISABLED_FLAG = "disabled.flag"
        private const val MAGIC = 0x4551424D // EQBM
        private const val FORMAT_VERSION = 1
    }
}
