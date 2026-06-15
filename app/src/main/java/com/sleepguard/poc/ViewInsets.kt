package com.sleepguard.poc

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * System-bar safe-area padding for edge-to-edge windows (targetSdk 35+ draws content behind the
 * status and navigation bars). Call once on a root content container; future screens can reuse
 * this instead of duplicating inset code.
 *
 * It reads the dynamic system-bar + display-cutout insets (no hardcoded bar sizes, no
 * device-specific values, no per-widget margins) and applies them as padding so nothing hides
 * behind the bars. The view's padding at call time is captured ONCE as the baseline, and every
 * inset dispatch sets padding = baseline + current insets — so repeated applications never
 * accumulate. Applying it to a scrollable container's content means the last items clear the
 * navigation bar.
 *
 * Call exactly once per view (the baseline is captured at call time).
 */
fun View.applySystemBarInsetsPadding() {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(
            left = baseLeft + bars.left,
            top = baseTop + bars.top,
            right = baseRight + bars.right,
            bottom = baseBottom + bars.bottom
        )
        insets
    }
}
