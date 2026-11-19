package com.theprobyte.golfalone

import android.graphics.Bitmap
import android.graphics.Canvas
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.PixelFont
import com.theprobyte.golfalone.render.Sprites
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** A contact sheet of every sprite and both fonts at 6× (with -Pshots=<dir>). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpriteSheetTest {
    @Test fun spritesNeverRescale() {
        // Bitmaps baked once must carry no density, or a canvas at another density scales them.
        for (s in Sprites.all) org.junit.Assert.assertEquals(s.name, Bitmap.DENSITY_NONE, s.bitmap.density)
    }

    @Test fun renderSheet() {
        val dir = System.getProperty("golfalone.shots")?.let { File(it).apply { mkdirs() } } ?: return
        val w = 180
        val h = 110
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Palette.GRASS[5])
        var x = 4
        for (s in Sprites.all) {
            s.draw(c, x, 4)
            x += s.w + 4
            if (x > w - 12) x = 4
        }
        val icons = Sprites.icons
        x = 4
        for (s in icons) { Sprites.icon(c, s, x, 20, Palette.PAPER_INK); x += s.w + 4 }
        Sprites.palmCanopy(c, 120, 40, 0)
        Sprites.palmTrunk(c, 120, 40)
        PixelFont.draw(c, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", 4, 48, Palette.PAPER_INK, 1, 0, PixelFont.Face.BIG)
        PixelFont.draw(c, "0123456789 !.,:-+/?'%<>*()", 4, 58, Palette.PAPER_INK, 1, 0, PixelFont.Face.BIG)
        PixelFont.draw(c, "ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789", 4, 70, Palette.PAPER_INK)
        PixelFont.draw(c, "NEW BEST  HOLE 1/6  PAR 3", 4, 80, Palette.PAPER_INK, 1, 0, PixelFont.Face.BIG)
        PixelFont.draw(c, "NEW BEST  HOLE 1/6  PAR 3", 4, 92, Palette.PAPER_INK)
        val big = Bitmap.createScaledBitmap(b, w * 6, h * 6, false)
        FileOutputStream(File(dir, "sprite-sheet.png")).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
