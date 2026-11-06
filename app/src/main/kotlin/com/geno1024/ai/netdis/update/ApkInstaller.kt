package com.geno1024.ai.netdis.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {

    /**
     * The provider's `updates/` root in res/xml/file_paths.xml must match the
     * directory [Updater.download] writes into; otherwise FileProvider throws
     * and the installer never opens.
     */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.updates", file)

    /**
     * Android 8+ blocks installing apps until the user grants this permission,
     * so the caller has to send them to Settings first.
     */
    fun canRequestInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Hands the APK to the system installer and reports what went wrong instead
     * of failing silently. Returns `null` once an installer accepted the file.
     *
     * Some OEM builds do not resolve the package archive MIME type for
     * `ACTION_VIEW` but do handle the legacy `ACTION_INSTALL_PACKAGE`, so that
     * is tried as a fallback.
     */
    @Suppress("DEPRECATION") // ACTION_INSTALL_PACKAGE is the OEM fallback path.
    fun launchInstall(context: Context, file: File): String? {
        if (!file.isFile) return "安装包已不存在，请重新下载"
        val uri = try {
            uriFor(context, file)
        } catch (e: IllegalArgumentException) {
            return "无法共享安装包：${e.message ?: "缓存目录未在 FileProvider 中声明"}"
        }
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
            },
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
            },
        )
        var failure = "系统没有可用的安装器"
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return null
            } catch (e: ActivityNotFoundException) {
                failure = "系统没有可用的安装器"
            } catch (e: SecurityException) {
                failure = "系统拒绝打开安装器：${e.message ?: "权限不足"}"
            }
        }
        return failure
    }

    /** Opens the "install unknown apps" page. Returns an error, or `null` on success. */
    fun launchSettings(context: Context): String? =
        try {
            context.startActivity(
                settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        } catch (e: ActivityNotFoundException) {
            "系统没有可用的安装权限设置页"
        } catch (e: SecurityException) {
            "无法打开安装权限设置页：${e.message ?: "权限不足"}"
        }
}
