package com.theprobyte.golfalone

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Builds a course's maps one at a time, in hole order, on a background thread, except that
 * the hole you're about to play can jump the queue ([promote]), so practising hole 9 just
 * after changing course doesn't wait for holes 1-8 first.
 */
class MapBuilder(name: String) {
    private val queue = LinkedBlockingDeque<Runnable>()
    private val exec = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue) { r -> Thread(r, name).apply { isDaemon = true } }

    fun <T> submit(task: Callable<T>): Future<T> = exec.submit(task)

    fun submit(task: Runnable): Future<*> = exec.submit(task)

    /** Moves [f] to the front of the queue if it's still waiting there. */
    fun promote(f: Future<*>) {
        if (f is Runnable && queue.remove(f)) queue.offerFirst(f)
    }
}
