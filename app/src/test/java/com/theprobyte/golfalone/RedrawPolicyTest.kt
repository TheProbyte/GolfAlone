package com.theprobyte.golfalone

import org.junit.Assert.assertEquals
import org.junit.Test

class RedrawPolicyTest {
    private val idle = RedrawPolicy.State(touching = false, ballMoving = false, transitioning = false, effects = false, cameraMoving = false)

    @Test fun anIdleScreenDrawsSlowly() = assertEquals(RedrawPolicy.IDLE_MS, RedrawPolicy.intervalMs(idle))

    @Test fun anythingMovingDrawsEveryVsync() {
        for (s in listOf(idle.copy(touching = true), idle.copy(ballMoving = true), idle.copy(transitioning = true),
            idle.copy(effects = true), idle.copy(cameraMoving = true))) assertEquals(s.toString(), 0L, RedrawPolicy.intervalMs(s))
    }
}
