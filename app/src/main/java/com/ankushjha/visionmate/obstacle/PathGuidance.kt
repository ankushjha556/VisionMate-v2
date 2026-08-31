package com.ankushjha.visionmate.obstacle

import com.ankushjha.visionmate.util.Closeness
import com.ankushjha.visionmate.util.Detection
import com.ankushjha.visionmate.util.PassSide

/**
 * Geometry-based path guidance (the Phase 3 research layer, v1 scope):
 * when the walking column ahead is blocked, look at the left and right
 * thirds of the frame — if one side is free of CLOSE/STOP detections,
 * suggest passing on that side.
 *
 * "Free" = no CLOSE/STOP-tier box intrudes into that side's column band.
 */
object PathGuidance {

    /**
     * @param hazards all detections with their closeness tier
     * @param frameWidth analysis-frame width in px
     */
    fun suggestPassSide(hazards: List<Pair<Detection, Closeness>>, frameWidth: Int): PassSide {
        if (frameWidth <= 0) return PassSide.NONE

        fun sideBlocked(sideLeft: Boolean): Boolean {
            val xLo = if (sideLeft) 0f else frameWidth * 0.62f
            val xHi = if (sideLeft) frameWidth * 0.38f else frameWidth.toFloat()
            return hazards.any { (d, c) ->
                if (c != Closeness.CLOSE && c != Closeness.STOP) return@any false
                d.x2 > xLo && d.x1 < xHi
            }
        }

        val leftBlocked = sideBlocked(sideLeft = true)
        val rightBlocked = sideBlocked(sideLeft = false)

        return when {
            !leftBlocked && rightBlocked -> PassSide.LEFT
            !rightBlocked && leftBlocked -> PassSide.RIGHT
            !leftBlocked && !rightBlocked -> PassSide.LEFT // prefer left; both open
            else -> PassSide.NARROW
        }
    }
}
