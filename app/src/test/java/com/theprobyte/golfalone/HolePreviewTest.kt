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
        for (hole in Holes.all) {
            val m = Terrain(hole).mismatch()
            println("${hole.name}: drawn vs physics, deepest stray px: land ${m[0]}, water ${m[1]}, sand ${m[2]}, green ${m[3]}")
            // Keep every drawn edge within 3 px of the tiles the physics uses.
            org.junit.Assert.assertTrue("${hole.name} strays ${m.toList()}", m.all { it <= 3f })
        }
        val dir = System.getProperty("golfalone.shots")?.let { File(it, "holes").apply { mkdirs() } } ?: return
        val scale = 3
        Holes.all.forEachIndexed { i, hole ->
            val t = Terrain(hole)
            val b = Bitmap.createBitmap(t.w * scale, t.h * scale, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(scale.toFloat(), scale.toFloat())
            val p = Paint().apply { isFilterBitmap = false }
            c.drawBitmap(t.ground, 0f, 0f, p)
            c.drawBitmap(t.water[0], 0f, 0f, p)
            FileOutputStream(File(dir, "hole-${i + 1}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
