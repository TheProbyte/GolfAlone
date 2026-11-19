package com.theprobyte.golfalone

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptics composed from the vibrator's primitives where the device supports them
 * (Pixels do), falling back to standard view feedback.
 */
class Haptics(context: Context) {
    var enabled = true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun supports(p: Int): Boolean =
        Build.VERSION.SDK_INT >= 31 && vibrator?.hasVibrator() == true && vibrator.areAllPrimitivesSupported(p)

    private val lowTick = supports(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
    private val tick = supports(VibrationEffect.Composition.PRIMITIVE_TICK)
    private val click = supports(VibrationEffect.Composition.PRIMITIVE_CLICK)
    private val thud = supports(VibrationEffect.Composition.PRIMITIVE_THUD)

    /** Pulling back: a tick every 10% of power, firmer at full power. */
    fun tension(view: View, step: Int, max: Boolean) {
        if (!enabled) return
        if (max && click) compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f) }
        else if (tick) compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, (0.12f + 0.05f * step).coerceAtMost(0.6f)) }
        else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** The club meets the ball. */
    fun strike(view: View, power: Float) {
        if (!enabled) return
        if (click) compose {
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, (0.35f + 0.65f * power).coerceAtMost(1f))
            if (power > 0.55f && thud) addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.25f + 0.5f * power, 12)
        } else view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun wall(view: View, impact: Float) {
        if (!enabled || impact < 25f) return
        val s = (impact / 260f).coerceIn(0.12f, 1f)
        if (lowTick) compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, s) }
        else view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun sand(view: View) {
        if (!enabled) return
        if (lowTick) compose {
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f)
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.25f, 30)
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.14f, 30)
        } else view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun splash(view: View) {
        if (!enabled) return
        if (thud) compose {
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.6f)
            if (lowTick) addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.3f, 60)
        } else view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun lipOut(view: View) {
        if (!enabled) return
        if (tick) compose {
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f, 45)
        } else view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** In the cup: a rattle then a settle; [great] adds a final flourish. */
    fun cup(view: View, great: Boolean) {
        if (!enabled) return
        if (tick) compose {
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
            addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f, 60)
            if (thud) addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.7f, 70)
            if (great) {
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 160)
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f, 70)
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 70)
            }
        } else view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun tap(view: View) {
        if (!enabled) return
        if (tick) compose { addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f) }
        else view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private inline fun compose(block: VibrationEffect.Composition.() -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return
        val c = VibrationEffect.startComposition()
        c.block()
        vibrator?.vibrate(c.compose())
    }
}
