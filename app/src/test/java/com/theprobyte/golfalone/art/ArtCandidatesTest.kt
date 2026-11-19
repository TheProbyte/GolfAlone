package com.theprobyte.golfalone.art

import android.graphics.Bitmap
import android.graphics.Canvas
import com.theprobyte.golfalone.game.Hole
import com.theprobyte.golfalone.render.Palette
import com.theprobyte.golfalone.render.Pix
import com.theprobyte.golfalone.render.Sprite
import com.theprobyte.golfalone.render.Sprites
import com.theprobyte.golfalone.render.Terrain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * Candidate art for the next style pass, drawn with the game's own Terrain masks, Palette
 * and Sprite code, so what's judged is what would ship. With -Pshots=<dir> it writes PNGs
 * and 8 water frames per candidate. Nothing here is used by the game yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtCandidatesTest {

    private fun c(hex: Long) = hex.toInt()

    /** A cooler, deeper ramp for leaves, so canopies read against the fairway. */
    private val leaf = mapOf(
        'o' to c(0xFF12352B), 'd' to c(0xFF1F5540), 'm' to c(0xFF2F7A4A), 'l' to c(0xFF5AA64E), 'h' to c(0xFF9FD364),
        'c' to Palette.WOODS[1], 'C' to Palette.WOODS[3],
    )

    /** Palm A: radiating fronds that droop towards the viewer. */
    private val palmA = Sprite("palm-a", listOf(
        "...........h...........",
        "...mml.....l.......m...",
        "..mlhlm....l....mlhlm..",
        ".ddmmlll...l...lllmmdd.",
        "...ddmmlll.l.lllmmdd...",
        "..mllllhlllmlllhllllm..",
        ".mlhhllllllmllllllhhlm.",
        "mllddmllllcCcllllmddllm",
        "ldo..ddllccCcclldd..odl",
        "do....mmllcccllmm....od",
        "o....mlhd.lcl.dhlm....o",
        "....mlho...l...ohlm....",
        "....lho....l....ohl....",
        "....do.....d.....od....",
        "....o......o......o....",
    ), leaf)

    /** Palm B: a rounder, layered crown. */
    private val palmB = Sprite("palm-b", listOf(
        "........mmm........",
        ".....mmllmllmm.....",
        "...mmllhlmlhllmm...",
        "..mlllhllmllhlllm..",
        ".mlhmmdmlllmdmmhlm.",
        "mlhm..dmlhlmd..mhlm",
        "lhm.mmlllhlllmm.mhl",
        "hm.mlhhllcllhhlm.mh",
        "m.mlhlllcCclllhlm.m",
        "o.lhmoolcccloomhl.o",
        "..lmo.olllllo.oml..",
        ".lmo..olhlhlo..oml.",
        ".mo....ohlho....om.",
        ".o......dmd......o.",
        "........ooo........",
    ), leaf)

    private val patch = Hole("ART", 3, listOf(
        "~~~~~~~~~~~~~~~~~~~~",
        "~~~~~~~~~~~~~~~~~~~~",
        "~~~~~~,,,,,,,~~~~~~~",
        "~~~~,,,,,,,,,,,~~~~~",
        "~~~,,,....P..,,,~~~~",
        "~~,,,......:::,,,~~~",
        "~~,,..~~~..:O::,,~~~",
        "~~,,.~~~~~..::,,,~~~",
        "~~,,..~~~...T..,,~~~",
        "~~~,,..P.......,,~~~",
        "~~~~,,,,....,,,,~~~~",
        "~~~~~~,,,,,,,~~~~~~~",
        "~~~~~~~~~~~~~~~~~~~~",
        "~~~~~~~~~~~~~~~~~~~~",
    ))

    private val sea = Palette.SEA

    /** W1 "Crests": clean depth bands, travelling wave crests, and a shore wash that laps in and out. */
    private fun waterCrests(x: Int, y: Int, f: Int, land: Boolean, bank: Int, d: Float): Int {
        val phase = 0.5f + 0.5f * sin(2f * PI.toFloat() * f / 8f)
        if (land) {
            // Wet sand where the wash has just pulled back.
            return if (d < 0.8f + 1.6f * (1f - phase)) Palette.alpha(Palette.SANDS[2], 150) else 0
        }
        if (bank > 0) return 0
        var col = when {
            d < 3f -> sea[4]
            d < 7f -> sea[3]
            d < 14f -> sea[2]
            else -> sea[1]
        }
        // Contour: the first row of each deeper band keeps the lighter colour on alternate pixels.
        if ((d in 3f..4f || d in 7f..8f || d in 14f..15f) && (x + y) % 2 == 0) col = Palette.lighter(col)
        // The wash: a foam line that runs up the beach and back.
        val reach = 0.6f + 2.2f * phase
        if (d < reach) col = if (d > reach - 1.1f) sea[5] else Palette.lerp(sea[4], sea[5], 0.5f)
        // Travelling crests on open water: short light dashes with a dark trough under them.
        if (d > 9f) {
            val cx = x - f
            val gx = Math.floorDiv(cx, 13)
            val gy = y / 9
            val jx = gx * 13 + (Terrain.hash(gx, gy, 71) * 8).toInt()
            val jy = gy * 9 + (Terrain.hash(gx, gy, 72) * 6).toInt()
            val life = (f + (Terrain.hash(gx, gy, 73) * 8).toInt()) % 8
            val len = intArrayOf(0, 2, 3, 4, 4, 3, 2, 0)[life]
            if (len > 0 && cx >= jx && cx < jx + len) {
                if (y == jy) col = sea[4] else if (y == jy + 1) col = sea[0]
            }
        }
        return col
    }

    /** W2 "Caustics": a dithered gradient, rippling light in the shallows, slow swells offshore. */
    private fun waterCaustics(x: Int, y: Int, f: Int, land: Boolean, bank: Int, d: Float): Int {
        if (land || bank > 0) return 0
        val t = 2f * PI.toFloat() * f / 8f
        // A gradient in 2×2 cells: coarser dither reads as texture, not noise.
        val b = Terrain.bayer(x / 2, y / 2) / 16f
        val depth = (d / 4f) + b
        var col = when {
            depth < 1f -> sea[4]
            depth < 2f -> sea[3]
            depth < 3.4f -> sea[2]
            else -> sea[1]
        }
        if (d < 8f) {
            val v = sin(x * 0.9f + t) + sin(y * 1.2f - t * 0.8f) + sin((x + y) * 0.55f + t * 1.3f)
            if (v > 2.05f) col = Palette.lerp(col, sea[5], 0.7f)
        } else {
            // Long, slow swells: light lines drifting down the screen.
            val s = (y + f) % 16
            if (s == 0 && sin(x * 0.21f + y * 0.05f) > 0.2f) col = Palette.lighter(col)
        }
        val pulse = 1.2f + 0.6f * sin(t)
        if (d < pulse) col = sea[5]
        else if (d < pulse + 1f && (x + f) % 3 != 0) col = Palette.lerp(sea[4], sea[5], 0.6f)
        return col
    }

    private fun render(water: ((Int, Int, Int, Boolean, Int, Float) -> Int)?, palm: Sprite?, frame: Int): Bitmap {
        val t = Terrain(patch)
        val (land, bank, edge) = t.masks()
        val b = Pix.bitmap(t.w, t.h)
        val cv = Canvas(b)
        cv.drawBitmap(t.ground, 0f, 0f, Sprites.blit)
        if (water == null) cv.drawBitmap(t.water(frame), 0f, 0f, Sprites.blit)
        else {
            val px = IntArray(t.w * t.h)
            b.getPixels(px, 0, t.w, 0, 0, t.w, t.h)
            for (y in 0 until t.h) for (x in 0 until t.w) {
                val i = y * t.w + x
                val o = water(x, y, frame, land[i], bank[i].toInt(), edge[i])
                if (o == 0) continue
                px[i] = if ((o ushr 24) == 0xFF) o else Palette.lerp(px[i], o or 0xFF000000.toInt(), (o ushr 24) / 255f)
            }
            b.setPixels(px, 0, t.w, 0, 0, t.w, t.h)
        }
        // Palms at the two P tiles, with the ball under the first one's canopy.
        val posts = patch.posts
        var k = 0
        while (k < posts.size) {
            val bx = posts[k].toInt()
            val by = posts[k + 1].toInt()
            if (palm == null) Sprites.palmShadow(cv, bx, by, 0)
            else Pix.oval(cv, bx + 5f, by + 1.5f, 9f, 3.5f, Palette.alpha(Palette.GRASS[1], 90))
            Sprites.palmTrunk(cv, bx, by)
            k += 2
        }
        val ballX = posts[0].toInt() - 3
        val ballY = posts[1].toInt() - 11
        Sprites.ball(cv, ballX, ballY, 0)
        k = 0
        while (k < posts.size) {
            val bx = posts[k].toInt()
            val by = posts[k + 1].toInt()
            if (palm == null) Sprites.palmCanopy(cv, bx, by, 0)
            else palm.draw(cv, bx - 3 - palm.w / 2, by - 11 - 7)
            k += 2
        }
        Sprites.ball(cv, ballX, ballY, 0, ghost = true)
        return b
    }

    private fun save(b: Bitmap, dir: File, name: String) {
        val big = Bitmap.createScaledBitmap(b, b.width * 6, b.height * 6, false)
        FileOutputStream(File(dir, "$name.png")).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderCandidates() {
        val dir = System.getProperty("golfalone.shots")?.let { File(it, "art").apply { mkdirs() } } ?: return
        for (f in 0 until 8) {
            save(render(null, null, f), dir, "today-frame-$f")
            save(render(::waterCrests, palmA, f), dir, "w1-frame-$f")
            save(render(::waterCaustics, palmB, f), dir, "w2-frame-$f")
        }
    }
}
