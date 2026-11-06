package com.geno1024.ai.netdis.router

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a capture to the app cache and hands it to the system share sheet.
 * The `traffic/` root is declared next to `updates/` in res/xml/file_paths.xml;
 * if that declaration and this directory drift apart FileProvider throws, so
 * both carry a comment pointing at the other.
 */
object TrafficExport {

    fun cacheDir(context: Context): File =
        File(context.cacheDir, "traffic").apply { mkdirs() }

    fun write(context: Context, text: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(cacheDir(context), "netdis-capture-$stamp.txt")
        file.writeText(text)
        return file
    }

    /** Returns `null` once a chooser was opened, or a user-facing error. */
    fun share(context: Context, file: File): String? {
        if (!file.isFile) return "抓包文件已不存在，请重新导出"
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        } catch (e: IllegalArgumentException) {
            return "无法共享抓包：${e.message ?: "缓存目录未在 FileProvider 中声明"}"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NetDis 路由器抓包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "导出抓包")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            null
        } catch (e: ActivityNotFoundException) {
            "系统没有可用于分享的应用"
        } catch (e: SecurityException) {
            "系统拒绝分享抓包：${e.message ?: "权限不足"}"
        }
    }
}
