package ch.kuon.phoenix

import kotlin.concurrent.schedule
import java.util.Timer
import java.util.concurrent.atomic.AtomicInteger

/**
 *
 * Exponential backoff timer
 *
 * @property callback The function called when the timer fires
 * @property timerCalc A function that is called when the timer is scheduled.
 *
 *  It is called with the current try and must return a
 *  time in miliseconds.
 *
 *  It is used to implement exponential timers.
 *
 *  First value of `tries` is 1.
 *
 *  Example: `{ tries -> min(tries * tries * 1000, 60000) }`
 */
class ExTimer(var callback: Callback, var timerCalc: (Int) -> Int) {

    var timer: Timer? = null
    var tries = AtomicInteger()

    fun reset() {
        tries.set(0)
        timer?.cancel()
        timer = null
    }

    fun scheduleTimeout() {
        timer?.cancel()

        timer = Timer()

        timer?.schedule(timerCalc(tries.get() + 1).toLong()) {
            tries.incrementAndGet()
            callback()
        }
    }
}

