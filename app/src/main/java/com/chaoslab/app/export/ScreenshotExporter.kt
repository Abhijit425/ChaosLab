package com.chaoslab.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.chaoslab.app.engine.ChaosEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Captures the simulation at full native resolution, brands it and saves it to the gallery. */
object ScreenshotExporter {

    private const val TAG = "ScreenshotExporter"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun capture(engine: ChaosEngine, context: Context, title: String, onResult: (Uri?) -> Unit) {
        val app = context.applicationContext
        engine.requestScreenshot { raw ->
            io.execute {
                val uri = try {
                    val img = compose(raw, title)
                    raw.recycle()
                    save(app, img).also { img.recycle() }
                } catch (t: Throwable) {
                    Log.e(TAG, "Saving screenshot failed", t)
                    null
                }
                main.post { onResult(uri) }
            }
        }
    }

    /** Flips the GL frame (bottom-up rows) and stamps the CHAOS LAB watermark. */
    private fun compose(raw: Bitmap, title: String): Bitmap {
        val w = raw.width; val h = raw.height
        raw.setHasAlpha(false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.save()
        c.scale(1f, -1f, w / 2f, h / 2f)
        c.drawBitmap(raw, 0f, 0f, null)
        c.restore()

        val unit = h / 1000f
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 111, 227, 255)
            textSize = 30f * unit
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.25f
            textAlign = Paint.Align.RIGHT
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 200, 210, 240)
            textSize = 18f * unit
            letterSpacing = 0.12f
            textAlign = Paint.Align.RIGHT
        }
        val margin = 36f * unit
        c.drawText("CHAOS LAB", w - margin, h - margin - 26f * unit, brand)
        c.drawText(title.uppercase(Locale.US), w - margin, h - margin, sub)
        return out
    }

    private fun save(context: Context, bmp: Bitmap): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ChaosLab_$stamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChaosLab")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { os ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) throw IllegalStateException("PNG encode failed")
            } ?: throw IllegalStateException("Could not open output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Made in CHAOS LAB — a physics playground for Android")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share your universe").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
