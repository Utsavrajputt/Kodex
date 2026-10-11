package com.invictus.kodex.github.install

import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.data.GitHubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** An APK kept on disk from an earlier download (survives the app being killed, e.g. by a self-install). */
data class CachedApk(val artifactId: Long, val label: String, val file: File, val sizeBytes: Long, val modifiedAt: Long)

/**
 * Streams an artifact zip to a `.part` file in `filesDir/gh_apk`, renames it on success and extracts the
 * `.apk` to `a<artifactId>__<name>.apk`. Extracted APKs are kept: the newest [KEEP_LAST] stay until the
 * user deletes them, anything older is removed once it is more than [MAX_AGE_MS] old. Temporary files
 * (`.part`, `.zip`, half-extracted APKs) are removed once they are stale.
 */
class ApkDownloader(baseDir: File) {
    val dir: File = File(baseDir, "gh_apk")

    /** Downloads [artifact]'s zip. The returned file is final (not `.part`). Cancel = coroutine cancel. */
    suspend fun downloadZip(repo: GitHubRepository, artifact: GhArtifact, onProgress: (Long) -> Unit): File {
        withContext(Dispatchers.IO) { dir.mkdirs() }
        val part = File(dir, "artifact-${artifact.id}.zip.part")
        val zip = File(dir, "artifact-${artifact.id}.zip")
        try {
            repo.downloadArtifact(artifact, part, onProgress)
            val ok = withContext(Dispatchers.IO) { zip.delete(); part.renameTo(zip) }
            if (!ok) throw java.io.IOException("rename failed")
            return zip
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    /** Extracts the `.apk` from [zip] (zip-slip / size safe), names it after [artifact], removes the zip. */
    suspend fun extract(zip: File, artifact: GhArtifact): File = withContext(Dispatchers.IO) {
        try {
            val raw = ArtifactZip.extractApk(zip, dir)
            val target = File(dir, "a${artifact.id}__${safeLabel(artifact.name)}.apk")
            target.delete()
            if (!raw.renameTo(target)) {
                raw.delete()
                throw java.io.IOException("rename failed")
            }
            target
        } finally {
            zip.delete()
        }
    }

    /** The kept APK of [artifactId], if a complete one is on disk. */
    fun cachedApk(artifactId: Long): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.length() > 0 && it.name.startsWith("a${artifactId}__") && it.name.endsWith(".apk") }

    /** Every kept APK, newest first. */
    fun listCached(): List<CachedApk> =
        dir.listFiles().orEmpty().mapNotNull { f ->
            val m = NAME.matchEntire(f.name) ?: return@mapNotNull null
            if (!f.isFile || f.length() <= 0) return@mapNotNull null
            CachedApk(m.groupValues[1].toLong(), m.groupValues[2], f, f.length(), f.lastModified())
        }.sortedByDescending { it.modifiedAt }

    /**
     * Retention: the newest [KEEP_LAST] APKs are kept; the rest only after they are older than [maxAgeMs].
     * Temporary files are only removed once they are [STALE_TMP_MS] old, so a download that is running
     * right now is never touched.
     */
    fun cleanOld(maxAgeMs: Long = MAX_AGE_MS, now: Long = System.currentTimeMillis()) {
        val files = dir.listFiles() ?: return
        val keep = listCached().take(KEEP_LAST).map { it.file }.toSet()
        files.forEach { f ->
            val isApk = NAME.matches(f.name)
            when {
                isApk -> if (f !in keep && now - f.lastModified() > maxAgeMs) f.delete()
                now - f.lastModified() > STALE_TMP_MS -> f.delete()
            }
        }
    }

    fun delete(file: File) { file.delete() }

    fun deleteAllApks() {
        listCached().forEach { it.file.delete() }
    }

    private fun safeLabel(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.').take(80).ifEmpty { "app" }

    companion object {
        const val KEEP_LAST = 3
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val STALE_TMP_MS = 15L * 60 * 1000
        private val NAME = Regex("^a(\\d+)__(.+)\\.apk$")
    }
}
