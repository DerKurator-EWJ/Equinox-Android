package de.equinox.v05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Step 37: portable memory profiles.
 *
 * A profile is a snapshot of the learned Equinox identity: indexed long-term store plus both
 * adaptive sensor codebooks. Runtime/UI preferences deliberately stay global. Switching a
 * profile replaces only those learned files, then MainActivity reloads the existing core.
 */
class MemoryProfileManager(private val context: Context) {
    data class ProfileInfo(
        val id: String,
        val name: String,
        val bytes: Long,
        val modified: Long,
        val active: Boolean
    )

    companion object {
        private const val PROFILE_DIR = "equinox_profiles"
        private const val INDEX_FILE = "profiles.json"
        private const val PREFS = "equinox_profile_state"
        private const val ACTIVE_KEY = "active_profile"
        private const val FORMAT = 1
        private val COMPANION_FILES = listOf(
            "equinox_v06_adaptive_quantizer.json",
            "equinox_v06_adaptive_visual_quantizer.json",
            KnowledgeLayerStore.FILE_NAME
        )
    }

    private val profileDir = File(context.filesDir, PROFILE_DIR).apply { mkdirs() }
    private val indexFile = File(profileDir, INDEX_FILE)
    private val liveStore = File(context.filesDir, IndexedLongTermStore.STORE_DIR)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init { ensureDefaultProfile() }

    @Synchronized
    fun listProfiles(): List<ProfileInfo> {
        val active = activeProfileId()
        return readIndex().map { (id, name) ->
            val f = archiveFile(id)
            ProfileInfo(id, name, if (f.exists()) f.length() else 0L, if (f.exists()) f.lastModified() else 0L, id == active)
        }.sortedWith(compareByDescending<ProfileInfo> { it.active }.thenBy { it.name.lowercase() })
    }

    @Synchronized
    fun activeProfileId(): String = prefs.getString(ACTIVE_KEY, null) ?: readIndex().first().first

    @Synchronized
    fun activeProfileName(): String {
        val active = activeProfileId()
        return readIndex().firstOrNull { it.first == active }?.second ?: "Default"
    }

    /** Saves the current live learned state into the active profile archive. */
    @Synchronized
    fun saveActive(): Boolean = saveProfile(activeProfileId())

    @Synchronized
    fun saveProfile(id: String): Boolean {
        val pair = readIndex().firstOrNull { it.first == id } ?: return false
        return writeLiveArchive(archiveFile(id), pair.second)
    }

    /** Creates an empty profile and makes it active, after snapshotting the previous profile. */
    @Synchronized
    fun createFresh(name: String): ProfileInfo? {
        saveActive()
        val cleanName = sanitizeName(name)
        val id = newId()
        val index = readIndex().toMutableList().apply { add(id to cleanName) }
        if (!writeIndex(index)) return null
        if (!writeEmptyArchive(archiveFile(id), cleanName)) return null
        prefs.edit().putString(ACTIVE_KEY, id).apply()
        clearLiveLearnedState()
        return profileInfo(id)
    }

    /** Duplicates a stored profile archive without switching the live state. */
    @Synchronized
    fun duplicate(id: String, name: String): ProfileInfo? {
        if (id == activeProfileId()) saveActive()
        val source = archiveFile(id)
        if (!source.exists()) return null
        val newId = newId()
        val cleanName = sanitizeName(name)
        val dest = archiveFile(newId)
        if (!runCatching { source.copyTo(dest, overwrite = true); true }.getOrDefault(false)) return null
        val index = readIndex().toMutableList().apply { add(newId to cleanName) }
        if (!writeIndex(index)) { dest.delete(); return null }
        return profileInfo(newId)
    }

    /** Saves the current profile, restores target profile into live files and marks it active. */
    @Synchronized
    fun switchTo(id: String): Boolean {
        if (id == activeProfileId()) return true
        val target = archiveFile(id)
        if (!target.exists()) return false
        saveActive()
        if (!restoreArchive(target)) return false
        prefs.edit().putString(ACTIVE_KEY, id).apply()
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val index = readIndex().toMutableList()
        if (index.size <= 1) return false
        val pos = index.indexOfFirst { it.first == id }
        if (pos < 0) return false
        val wasActive = id == activeProfileId()
        archiveFile(id).delete()
        index.removeAt(pos)
        if (!writeIndex(index)) return false
        if (wasActive) {
            val fallback = index.first()
            prefs.edit().putString(ACTIVE_KEY, fallback.first).apply()
            restoreArchive(archiveFile(fallback.first))
        }
        return true
    }

    @Synchronized
    fun exportProfile(id: String, output: OutputStream): Boolean {
        if (id == activeProfileId()) saveActive()
        val src = archiveFile(id)
        if (!src.exists()) return false
        return runCatching {
            FileInputStream(src).use { input -> output.use { out -> input.copyTo(out) } }
            true
        }.getOrDefault(false)
    }

    /** Imports a portable .eqx/.zip snapshot as a new profile. It does not auto-switch. */
    @Synchronized
    fun importProfile(input: InputStream, suggestedName: String): ProfileInfo? {
        val temp = File(profileDir, "import_${System.nanoTime()}.tmp")
        val ok = runCatching {
            input.use { ins -> FileOutputStream(temp).use { out -> ins.copyTo(out) } }
            validateArchive(temp)
        }.getOrDefault(false)
        if (!ok) { temp.delete(); return null }

        val id = newId()
        val name = sanitizeName(suggestedName.removeSuffix(".eqx").removeSuffix(".zip"))
        val dest = archiveFile(id)
        if (!temp.renameTo(dest)) {
            runCatching { temp.copyTo(dest, overwrite = true) }.getOrElse { temp.delete(); return null }
            temp.delete()
        }
        val index = readIndex().toMutableList().apply { add(id to name) }
        if (!writeIndex(index)) { dest.delete(); return null }
        return profileInfo(id)
    }

    private fun profileInfo(id: String): ProfileInfo? = listProfiles().firstOrNull { it.id == id }

    private fun clearLiveLearnedState() {
        liveStore.deleteRecursively()
        COMPANION_FILES.forEach { File(context.filesDir, it).delete() }
    }

    private fun restoreArchive(src: File): Boolean = runCatching {
        clearLiveLearnedState()
        ZipInputStream(BufferedInputStream(FileInputStream(src))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name != "profile_manifest.json") {
                    val target = File(context.filesDir, entry.name)
                    val root = context.filesDir.canonicalFile
                    val canonical = target.canonicalFile
                    require(canonical.path.startsWith(root.path + File.separator)) { "Unsafe profile path" }
                    if (entry.isDirectory) canonical.mkdirs() else {
                        canonical.parentFile?.mkdirs()
                        FileOutputStream(canonical).use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        true
    }.getOrDefault(false)

    private fun writeLiveArchive(dest: File, name: String): Boolean = runCatching {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".tmp")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("name", name)
                .put("exportedAt", System.currentTimeMillis())
                .toString().toByteArray()
            zip.putNextEntry(ZipEntry("profile_manifest.json")); zip.write(manifest); zip.closeEntry()

            if (liveStore.exists()) {
                liveStore.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.relativeTo(context.filesDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            COMPANION_FILES.forEach { fileName ->
                val file = File(context.filesDir, fileName)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(fileName))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        if (dest.exists()) dest.delete()
        require(temp.renameTo(dest)) { "Could not commit profile archive" }
        true
    }.getOrDefault(false)

    private fun writeEmptyArchive(dest: File, name: String): Boolean = runCatching {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zip ->
            val manifest = JSONObject().put("format", FORMAT).put("name", name).put("empty", true).toString().toByteArray()
            zip.putNextEntry(ZipEntry("profile_manifest.json")); zip.write(manifest); zip.closeEntry()
        }
        true
    }.getOrDefault(false)

    private fun validateArchive(file: File): Boolean = runCatching {
        var validEntry = false
        ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val n = entry.name
                require(!n.startsWith("/") && !n.contains("..")) { "Unsafe archive" }
                if (n == "profile_manifest.json" || n.startsWith(IndexedLongTermStore.STORE_DIR + "/") || n in COMPANION_FILES) validEntry = true
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        validEntry
    }.getOrDefault(false)

    private fun ensureDefaultProfile() {
        val index = readIndexRaw()
        if (index.isNotEmpty()) {
            if (prefs.getString(ACTIVE_KEY, null) == null) prefs.edit().putString(ACTIVE_KEY, index.first().first).apply()
            return
        }
        val id = "default"
        writeIndex(listOf(id to "Default"))
        prefs.edit().putString(ACTIVE_KEY, id).apply()
        // Capture the already-existing live identity if Step 37 is installed over an older build.
        if (!writeLiveArchive(archiveFile(id), "Default")) writeEmptyArchive(archiveFile(id), "Default")
    }

    private fun readIndex(): List<Pair<String, String>> = readIndexRaw().ifEmpty {
        ensureDefaultProfile(); readIndexRaw()
    }

    private fun readIndexRaw(): List<Pair<String, String>> = runCatching {
        if (!indexFile.exists()) return@runCatching emptyList()
        val root = JSONObject(indexFile.readText())
        val arr = root.optJSONArray("profiles") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) add(id to name)
            }
        }
    }.getOrDefault(emptyList())

    private fun writeIndex(items: List<Pair<String, String>>): Boolean = runCatching {
        val arr = JSONArray()
        items.forEach { (id, name) -> arr.put(JSONObject().put("id", id).put("name", name)) }
        indexFile.writeText(JSONObject().put("format", FORMAT).put("profiles", arr).toString())
        true
    }.getOrDefault(false)

    private fun sanitizeName(raw: String): String = raw.trim().ifBlank { "Memory ${listProfiles().size + 1}" }.take(48)
    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    private fun archiveFile(id: String): File = File(profileDir, "$id.eqx")
}
