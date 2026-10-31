package com.gym.hisab.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object Share {
    fun text(ctx: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(ctx, Intent.createChooser(i, null))
    }

    /** Opens WhatsApp chat with the member (Indian numbers: 10 digits get +91), message pre-filled. */
    fun whatsapp(ctx: Context, phone: String?, text: String) {
        val digits = phone.orEmpty().filter { it.isDigit() }
        if (digits.length < 10) { text(ctx, text); return }
        val num = if (digits.length == 10) "91$digits" else digits
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(text))))
    }

    fun dial(ctx: Context, phone: String) {
        start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })))
    }

    fun openUrl(ctx: Context, url: String) {
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Writes a CSV to the app cache and shares it through a FileProvider (no storage permission needed). */
    fun csv(ctx: Context, fileName: String, content: String) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, fileName)
        // UTF-8 BOM so Excel on Windows shows Hindi names and the rupee sign correctly.
        f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray(Charsets.UTF_8))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(ctx, Intent.createChooser(i, null))
    }

    private fun start(ctx: Context, i: Intent) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No app can handle it; nothing else to do.
        }
    }
}
