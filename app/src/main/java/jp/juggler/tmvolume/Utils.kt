package jp.juggler.tmvolume

import android.content.Context
import android.view.View


fun String?.notEmpty() = if (this?.isNotEmpty() == true) this else null

fun Int.clip(min: Int, max: Int) = if (this < min) min else if (this > max) max else this
fun Float.clip(min: Float, max: Float) =
    if (this < min) min else if (this > max) max else this

fun avg(a: Int, b: Int) = (a + b) / 2

var View.isEnabledAlpha: Boolean
    get() = isEnabled
    set(value) {
        if (value == isEnabled) return
        isEnabled = value
        alpha = if (value) 1.0f else 0.3f
    }

fun View.vg(shown: Boolean) =
    shown.also { visibility = if (it) View.VISIBLE else View.GONE }

inline fun <reified T> Any.cast(): T = this as T
inline fun <reified T> Context.systemService(x: String): T =
    applicationContext.getSystemService(x)!!.cast()
