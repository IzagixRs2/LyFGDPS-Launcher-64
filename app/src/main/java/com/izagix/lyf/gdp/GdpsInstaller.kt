package com.izagix.lyf.gdp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GdpsInstaller {
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(GdpsConfig.GAME_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    }

    suspend fun downloadApk(context: Context): File = withContext(Dispatchers.IO) {
        val output = File(context.cacheDir, "LyFGDPS.apk")
        val connection = (URL(GdpsConfig.GDPS_APK_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("No se pudo descargar LyFGDPS (HTTP ${connection.responseCode})")
            }

            connection.inputStream.use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        } finally {
            connection.disconnect()
        }

        if (!output.exists() || output.length() < 1_000_000L) {
            output.delete()
            throw IllegalStateException("El APK de LyFGDPS descargado es inválido o está incompleto.")
        }

        output
    }

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
