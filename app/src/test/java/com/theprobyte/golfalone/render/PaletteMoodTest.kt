package com.theprobyte.golfalone.render

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** A time of day must never cost readability: the surfaces you play on stay apart. */
class PaletteMoodTest {
    private fun dist(a: Int, b: Int): Float {
        fun ch(c: Int, s: Int) = ((c shr s) and 0xff).toFloat()
        val dr = ch(a, 16) - ch(b, 16); val dg = ch(a, 8) - ch(b, 8); val db = ch(a, 0) - ch(b, 0)
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun surfaces() = linkedMapOf(
        "fairway" to Palette.FAIR_A, "rough" to Palette.ROUGH, "green" to Palette.GREEN_A, "sand" to Palette.SAND,
        "water" to Palette.MID, "shallow" to Palette.SHALLOW, "stone" to Palette.STONE_TOP, "wood" to Palette.DRIFT[3], "ball" to Palette.BALL,
    )

    @Test fun everyMoodKeepsSurfacesDistinct() {
        val before = Palette.mood
        try {
            Palette.setMood(Mood.DAY)
            val day = surfaces()
            for (m in Mood.values()) {
                Palette.setMood(m)
                val now = surfaces()
                val keys = day.keys.toList()
                for (i in keys.indices) for (j in i + 1 until keys.size) {
                    val d0 = dist(day.getValue(keys[i]), day.getValue(keys[j]))
                    val d1 = dist(now.getValue(keys[i]), now.getValue(keys[j]))
                    assertTrue("$m: ${keys[i]} vs ${keys[j]} is $d1 apart (day $d0)", d1 >= minOf(40f, d0 * 0.75f))
                }
            }
        } finally {
            Palette.setMood(before)
        }
    }

    @Test fun backToDayRestoresTheRamps() {
        val before = Palette.mood
        try {
            Palette.setMood(Mood.DAY)
            val day = Palette.GRASS.copyOf()
            Palette.setMood(Mood.DAWN)
            Palette.setMood(Mood.DAY)
            assertTrue(day.contentEquals(Palette.GRASS))
        } finally {
            Palette.setMood(before)
        }
    }
}
