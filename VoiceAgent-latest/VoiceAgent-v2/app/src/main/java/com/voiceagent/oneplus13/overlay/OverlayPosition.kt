package com.voiceagent.oneplus13.overlay

internal data class OverlayPosition(val x: Int, val y: Int)

/** Fit the measured window to the visible display frame (already excludes the IME).
 * Never subtract keyboard height twice or overwrite the user's preferred drag position.
 */
internal fun fitOverlayPosition(
    preferredX: Int, preferredY: Int, width: Int, height: Int,
    left: Int, top: Int, right: Int, bottom: Int
): OverlayPosition = OverlayPosition(
    preferredX.coerceIn(left, maxOf(left, right - width.coerceAtLeast(0))),
    preferredY.coerceIn(top, maxOf(top, bottom - height.coerceAtLeast(0)))
)
