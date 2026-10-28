package ai.deepseek.dsh.android.domain

import java.util.concurrent.TimeUnit

/** Room 只存 wall clock；单调时钟留在内存，投影器不得读系统时间。 */
interface Clock {
    fun wallClockMs(): Long

    fun monotonicMs(): Long
}

class SystemClock : Clock {
    override fun wallClockMs(): Long = System.currentTimeMillis()

    override fun monotonicMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
}

class FakeClock(startWallClockMs: Long = 0, startMonotonicMs: Long = 0) : Clock {
    private var wall: Long = startWallClockMs
    private var monotonic: Long = startMonotonicMs

    fun advance(ms: Long) {
        wall += ms
        monotonic += ms
    }

    override fun wallClockMs(): Long = wall

    override fun monotonicMs(): Long = monotonic
}
