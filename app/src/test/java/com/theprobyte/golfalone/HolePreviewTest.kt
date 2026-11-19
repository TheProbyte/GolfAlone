package com.theprobyte.golfalone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.theprobyte.golfalone.game.Holes
import com.theprobyte.golfalone.render.Terrain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Renders every hole's terrain to PNG (with -Pshots=<dir>) for eyeballing map changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HolePreviewTest {
    @Test fun renderAllHoles() {
        for (hole in Holes.everyHole) {
            // What's drawn as land is exactly what physics treats as land.
            val (land, _, _) = Terrain(hole).masks()
            org.junit.Assert.assertTrue("${hole.name}: drawn coast = physics coast", land.contentEquals(hole.coast.land))
            val m = Terrain(hole).mismatch()
            println("${hole.name}: drawn vs physics, deepest stray px: land ${m[0]}, water ${m[1]}, sand ${m[2]}, green ${m[3]}")
            // Keep every drawn edge within 3 px of the tiles the physics uses.
            org.junit.Assert.assertTrue("${hole.name} strays ${m.toList()}", m.all { it <= 3f })
        }
        val dir = System.getProperty("golfalone.shots")?.let { File(it, "holes").apply { mkdirs() } } ?: return
        val scale = 3
        for (course in Holes.COURSES) {
            com.theprobyte.golfalone.render.Palette.setMood(course.mood)
            course.holes.forEachIndexed { i, hole ->
                val base = Terrain(hole)
                for (t in listOfNotNull(base, base.flooded)) {
                    val b = Bitmap.createBitmap(t.w * scale, t.h * scale, Bitmap.Config.ARGB_8888)
                    val c = Canvas(b)
                    c.scale(scale.toFloat(), scale.toFloat())
                    val p = Paint().apply { isFilterBitmap = false }
                    c.drawBitmap(t.ground, 0f, 0f, p)
                    c.drawBitmap(t.water(0), 0f, 0f, p)
                    val tag = if (t === base) "" else "-high"
                    FileOutputStream(File(dir, "${course.id}-${i + 1}$tag.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        com.theprobyte.golfalone.render.Palette.setMood(com.theprobyte.golfalone.render.Mood.DAY)
    }
}
