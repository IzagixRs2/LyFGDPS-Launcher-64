package com.izagix.lyf.gdp

import android.content.Context
import com.geode.launcher.utils.DownloadUtils
import com.geode.launcher.utils.LaunchUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Keeps LyFGDPS' bundled Geode mods up to date without requiring a new launcher APK.
 *
 * The updater intentionally does not trust an old cached ETag forever.  It performs a
 * fresh metadata request (no-cache) on every launcher start and keeps a small fingerprint
 * containing ETag/Last-Modified/size.  Old updater preferences are invalidated by the
 * schema value, so phones that used an earlier updater cannot get stuck on an old mod.
 *
 * A download is written to a temporary .geode, validated, then replaced atomically.  If
 * anything fails, the currently installed/bundled mod is kept.
 */
object ModUpdater {
    private const val PREFS = "LyFGDPSModUpdater"
    private const val SCHEMA = 2
    private const val KEY_SCHEMA = "schema"
    private const val KEY_FINGERPRINT_PREFIX = "fingerprint_"
    private const val TIMEOUT_MS = 45_000L

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class RemoteMod(
        val id: String,
        val fileName: String,
        val url: String,
    )

    private data class RemoteMetadata(
        val etag: String?,
        val lastModified: String?,
        val contentLength: Long?,
    ) {
        fun fingerprint(): String = listOf(
            etag.orEmpty(),
            lastModified.orEmpty(),
            contentLength?.toString().orEmpty(),
        ).joinToString("|")
    }

    private val mods = listOf(
        RemoteMod(
            id = "geode.node-ids",
            fileName = "geode.node-ids.geode",
            url = GdpsConfig.NODE_IDS_MOD_URL,
        ),
        RemoteMod(
            id = "izagix.lyfgdps",
            fileName = "izagix.lyfgdps.geode",
            url = GdpsConfig.MOD_URL,
        ),
    )

    suspend fun updateMods(context: Context) {
        withTimeoutOrNull(TIMEOUT_MS) {
            val modsDirectory = File(
                LaunchUtils.getBaseDirectory(context),
                "game/geode/mods"
            )
            modsDirectory.mkdirs()

            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            migratePreferences(preferences)

            for (mod in mods) {
                try {
                    updateOne(mod, modsDirectory, preferences)
                } catch (error: Exception) {
                    println("LyFGDPS mod update failed for ${mod.id}: ${error.message}")
                }
            }
        } ?: println("LyFGDPS mod update timed out; using installed/bundled mods.")
    }

    private fun migratePreferences(preferences: android.content.SharedPreferences) {
        if (preferences.getInt(KEY_SCHEMA, 0) != SCHEMA) {
            // Remove all old ETag/fingerprint decisions. This is important for phones that
            // were upgraded from the previous updater implementation.
            preferences.edit().clear().putInt(KEY_SCHEMA, SCHEMA).apply()
            println("LyFGDPS mod updater: reset old update cache")
        }
    }

    private suspend fun updateOne(
        mod: RemoteMod,
        modsDirectory: File,
        preferences: android.content.SharedPreferences,
    ) {
        val destination = File(modsDirectory, mod.fileName)
        val remote = fetchMetadata(mod.url)
        val remoteFingerprint = remote.fingerprint()
        val savedFingerprint = preferences.getString(KEY_FINGERPRINT_PREFIX + mod.id, null)

        // Only skip when the current local file exists AND the freshly queried GitHub
        // metadata exactly matches what we recorded after a successful download.
        if (destination.exists() && savedFingerprint != null && remoteFingerprint == savedFingerprint) {
            println("LyFGDPS mod ${mod.id} is up to date")
            return
        }

        println("LyFGDPS mod ${mod.id}: update required")

        val temporary = File.createTempFile("lyfgdps-mod-", ".geode", modsDirectory)
        try {
            val downloadedHash = DownloadUtils.downloadFile(
                httpClient = httpClient,
                url = addNoCacheQuery(mod.url),
                outputFile = temporary,
            )

            validateMod(temporary, mod.id)

            // If the remote server returned a zero/unknown size, still rely on the
            // downloaded file itself. Otherwise catch incomplete downloads before replace.
            if (remote.contentLength != null && remote.contentLength > 0L &&
                temporary.length() != remote.contentLength
            ) {
                throw IOException(
                    "Downloaded ${temporary.length()} bytes, expected ${remote.contentLength}"
                )
            }

            // Force replacement even when an old .geode already exists. Geode will then
            // see the new archive hash on the next game start.
            replaceFile(temporary, destination)

            preferences.edit()
                .putString(KEY_FINGERPRINT_PREFIX + mod.id, remoteFingerprint)
                .apply()

            println("Updated LyFGDPS mod ${mod.id} (sha256=$downloadedHash)")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private suspend fun fetchMetadata(url: String): RemoteMetadata = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(addNoCacheQuery(url))
            .head()
            .header("User-Agent", "LyFGDPS-Launcher")
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .build()

        httpClient.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            RemoteMetadata(
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                contentLength = response.header("Content-Length")?.toLongOrNull(),
            )
        }
    }

    private fun addNoCacheQuery(url: String): String {
        // A changing cache-buster prevents an Android/HTTP cache from serving a stale
        // `latest/download` redirect or asset.
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}_lyfgdps_check=${System.currentTimeMillis()}"
    }

    private fun replaceFile(source: File, destination: File) {
        val backup = File(destination.parentFile, destination.name + ".old")
        if (backup.exists()) backup.delete()

        if (destination.exists() && !destination.renameTo(backup)) {
            throw IOException("Could not prepare old mod for replacement: ${destination.name}")
        }

        if (!source.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination)
            throw IOException("Could not install new mod: ${destination.name}")
        }

        if (backup.exists()) backup.delete()
    }

    private suspend fun validateMod(file: File, expectedId: String) = withContext(Dispatchers.IO) {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("mod.json")
                ?: throw IOException("Downloaded file has no mod.json")

            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val id = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(json)
                ?.groupValues
                ?.getOrNull(1)

            if (id != expectedId) {
                throw IOException("Downloaded mod id is $id, expected $expectedId")
            }
        }
    }
}
