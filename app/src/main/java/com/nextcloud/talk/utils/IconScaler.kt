package com.nextcloud.talk.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import android.util.LruCache

/**
 * Utility to rasterize and cache scaled drawables (bitmap-backed) to avoid repeated work.
 */
object IconScaler {
    // Simple LRU cache keyed by a small string (drawable id or constantState + size)
    private val cache = object : LruCache<String, BitmapDrawable>(64) {}

    fun getScaledDrawable(context: Context, drawable: Drawable, widthPx: Int, heightPx: Int): Drawable {
        val cs = drawable.constantState?.toString() ?: System.identityHashCode(drawable).toString()
        val key = "$cs|${widthPx}x$heightPx"
        synchronized(cache) {
            cache.get(key)?.let { return it }
        }

        return try {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, widthPx, heightPx)
            drawable.draw(canvas)
            val bd = BitmapDrawable(context.resources, bitmap)
            synchronized(cache) { cache.put(key, bd) }
            bd
        } catch (t: Throwable) {
            // Fallback to a transparent placeholder on error
            ColorDrawable(Color.TRANSPARENT)
        }
    }

    fun getScaledDrawableFromRes(context: Context, @DrawableRes resId: Int, widthPx: Int, heightPx: Int): Drawable {
        val key = "res:$resId|${widthPx}x$heightPx"
        synchronized(cache) {
            cache.get(key)?.let { return it }
        }
        val drawable = try {
            AppCompatResources.getDrawable(context, resId)
        } catch (t: Throwable) {
            null
        } ?: return ColorDrawable(Color.TRANSPARENT)
        return getScaledDrawable(context, drawable, widthPx, heightPx)
    }
}
