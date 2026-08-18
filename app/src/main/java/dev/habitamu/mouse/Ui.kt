package dev.habitamu.mouse

import android.content.Context
import android.util.TypedValue
import android.view.View

/** Density independent pixels as a float, for painting. */
fun Context.dp(value: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    value,
    resources.displayMetrics
)

/** Density independent pixels rounded to whole pixels, for layout. */
fun Context.dpInt(value: Float): Int = dp(value).toInt()

fun View.dp(value: Float): Float = context.dp(value)
