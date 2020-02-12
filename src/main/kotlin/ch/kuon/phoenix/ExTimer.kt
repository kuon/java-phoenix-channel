package ch.kuon.phoenix

import kotlin.concurrent.schedule
import java.util.Timer
import java.util.concurrent.atomic.AtomicInteger

/**
 *
 * Exponential backoff timer
 *
 * Example from tests:
 *
 * @sample LibraryTest.testTimer
 * @property callback The function called when the timer fires
 * @property timerCalc A function that is called when the timer is scheduled.
 *
 *  It is called with the current try and must return a
 *  time in miliseconds.
 *
 *  It is used to implement exponential timers.
 *
 *  First value of `tries` is 1.
 */
class ExTimer(var callback: Callback, var timerCalc: (Int) -> Int) {

    var timer: Timer? = null
    var tries = AtomicInteger()

    /**
     * Reset the timeout and cancel the timer if it is scheduled.
     */
    fun reset() {
        tries.set(0)
        timer?.cancel()
        timer = null
    }

    /**
     * Schedule the timer to run once
     */
    fun scheduleTimeout() {
        timer?.cancel()

        timer = Timer()

        timer?.schedule(timerCalc(tries.get() + 1).toLong()) {
            tries.incrementAndGet()
            callback()
        }
    }
}

