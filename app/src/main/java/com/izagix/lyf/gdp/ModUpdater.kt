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
 * The launcher checks the latest release asset on GitHub. If its ETag changed,
 * the new .geode file is downloaded and atomically moved into the Geode mods folder.
 */
object ModUpdater {
    private const val PREFS = "LyFGDPSModUpdater"
    private const val KEY_PREFIX = "etag_"
    private const val TIMEOUT_MS = 30_000L

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class RemoteMod(
        val id: String,
        val fileName: String,
        val url: String,
    )

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

    /**
     * Updates all configured mods. A failed network update never prevents the launcher
     * from starting; the bundled versions remain available as a fallback.
     */
    suspend fun updateMods(context: Context) {
        withTimeoutOrNull(TIMEOUT_MS) {
            val modsDirectory = File(
                LaunchUtils.getBaseDirectory(context),
                "game/geode/mods"
            )
            modsDirectory.mkdirs()

            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            for (mod in mods) {
                try {
                    updateOne(mod, modsDirectory, preferences)
                } catch (error: Exception) {
                    println("LyFGDPS mod update failed for ${mod.id}: ${error.message}")
                }
            }
        } ?: println("LyFGDPS mod update timed out; using installed/bundled mods.")
    }

    private suspend fun updateOne(
        mod: RemoteMod,
        modsDirectory: File,
        preferences: android.content.SharedPreferences,
    ) {
        val destination = File(modsDirectory, mod.fileName)
        val savedEtag = preferences.getString(KEY_PREFIX + mod.id, null)

        val remoteEtag = fetchEtag(mod.url)

        // If GitHub gives us an ETag and it has not changed, there is nothing to download.
        if (destination.exists() && remoteEtag != null && remoteEtag == savedEtag) {
            return
        }

        val temporary = File.createTempFile("lyfgdps-mod-", ".geode", modsDirectory)
        try {
            DownloadUtils.downloadFile(
                httpClient = httpClient,
                url = mod.url,
                outputFile = temporary,
            )

            validateMod(temporary, mod.id)

            // Replace only after the new file has been fully downloaded and validated.
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }

            if (remoteEtag != null) {
                preferences.edit()
                    .putString(KEY_PREFIX + mod.id, remoteEtag)
                    .apply()
            }

            println("Updated LyFGDPS mod ${mod.id}")
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private suspend fun fetchEtag(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "LyFGDPS-Launcher")
            .build()

        httpClient.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.header("ETag")
        }
    }

    private suspend fun validateMod(file: File, expectedId: String) = withContext(Dispatchers.IO) {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("mod.json")
                ?: throw IOException("Downloaded file has no mod.json")

            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)
                ?.groupValues
                ?.getOrNull(1)

            if (id != expectedId) {
                throw IOException("Downloaded mod id is $id, expected $expectedId")
            }
        }
    }
}
