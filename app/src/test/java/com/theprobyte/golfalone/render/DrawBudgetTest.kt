package com.theprobyte.golfalone.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.theprobyte.golfalone.game.HoleState
import com.theprobyte.golfalone.game.Holes
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How much the pieces on a hole cost to draw, counted exactly (pixels, rects, ovals and rings
 * through [Pix]) rather than timed, so CI noise can't hide a regression. Drawn as the game
 * draws them, with the camera at the tee and at the cup on a Pixel-sized view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawBudgetTest {

    /** Primitives the pieces of one hole may draw in one frame. */
    private val budget = 700

    @Test fun piecesStayWithinTheirBudget() {
        val lw = 180
        val lh = 400
        val c = Canvas(Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888))
        var worst = 0
        var worstAt = ""
        for (course in Holes.allCourses) course.holes.forEachIndexed { i, hole ->
            val st = HoleState(hole)
            val crabX = FloatArray(hole.crabs.size) { st.crabX(it) }
            val crabY = FloatArray(hole.crabs.size) { st.crabY(it) }
            for ((name, fx, fy) in listOf(Triple("tee", hole.teeX, hole.teeY), Triple("cup", hole.cupX, hole.cupY))) {
                val ix = (fx - lw / 2f).toInt().coerceIn(0, maxOf(0, hole.pxW - lw))
                val iy = (fy - lh / 2f).toInt().coerceIn(-40, maxOf(-40, hole.pxH - lh + 40))
                Pix.calls = 0
                Bodies.draw(c, hole, st, ix, iy, 1.3f, crabX, crabY, 1f, true, -99f, -99f, -1f)
                Bodies.windmills(c, hole, ix, iy, 0.7f)
                Bodies.scenery(c, hole, ix, iy, 1.3f, false)
                val n = Pix.calls
                if (n > worst) { worst = n; worstAt = "${course.id}-${i + 1} at the $name" }
                println("${course.id}-${i + 1} ${hole.name} $name: $n")
                assertTrue("${course.id}-${i + 1} ${hole.name} at the $name draws $n primitives (budget $budget)", n <= budget)
            }
        }
        println("worst: $worst ($worstAt)")
    }
}
