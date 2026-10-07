package com.shilapi.xcertplay.carlife

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling plain-text session log for the CarLife channel — the no-adb escape
 * hatch: the head unit writes its own engine/phases/unknown-CMD trace into
 * its external files dir (Android/data/<pkg>/files/logs), so a drive test can
 * be carried back on a USB stick when adb is unavailable.
 */
object CarLifeSessionLog {
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private var writer: PrintWriter? = null
    private var file: File? = null

    /** Starts a fresh log file per engine session. */
    @Synchronized
    fun newSession(context: Context) {
        runCatching {
            writer?.close()
            val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
            // Keep the folder from growing without bound: drop files > 7 days old.
            dir.listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 7L * 24 * 3600 * 1000) it.delete()
            }
            file = File(dir, "carlife-${fileFmt.format(Date())}.log")
            writer = PrintWriter(FileWriter(file, true), true)
            writer?.println("==== CarLife session ${timeFmt.format(Date())} ====")
        }.onFailure { Log.w(TAG, "session log init failed", it) }
    }

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        Log.i(TAG, "$tag: $message")
        runCatching {
            val w = writer ?: run {
                newSession(context)
                writer ?: return
            }
            w.println("${timeFmt.format(Date())} $tag: $message")
        }
    }

    fun currentFile(): File? = file

    private const val TAG = "CarLifeLog"
}
