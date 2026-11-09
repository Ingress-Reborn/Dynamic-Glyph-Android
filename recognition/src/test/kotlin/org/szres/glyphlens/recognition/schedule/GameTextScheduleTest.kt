package org.szres.glyphlens.recognition.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTextScheduleTest {
    @Test
    fun oneRequestOwnsTheLaneUntilItsCompletion() {
        val schedule = GameTextSchedule()
        val first = schedule.take(0.0)!!
        assertNull(schedule.take(0.2))
        schedule.finish(first)
        val next = schedule.take(0.2)!!
        schedule.finish(first)
        assertEquals(next, schedule.inFlight)
        schedule.finish(next)
        assertNull(schedule.inFlight)
        assertEquals(first.id + 1, next.id)
    }

    @Test
    fun headerCadenceSupportsSourceClockRestart() {
        val schedule = GameTextSchedule()
        val first = schedule.take(10.0)!!
        schedule.finish(first)
        assertNull(schedule.take(10.09))
        val next = schedule.take(10.125)!!
        schedule.finish(next)
        val restarted = schedule.take(0.0)!!
        assertEquals(0.0, restarted.sourceTime, 0.0)
        schedule.finish(restarted)
        assertNull(schedule.take(Double.NaN))
        assertNull(schedule.take(Double.POSITIVE_INFINITY))
    }

    @Test
    fun unchangedHeaderConsumesNoLaneCadenceOrRequestId() {
        val schedule = GameTextSchedule()
        val first = schedule.take(0.0, headerInterval = 0.5)!!
        schedule.finish(first)
        assertNull(schedule.take(0.4, headerInterval = 0.5))
        assertNull(schedule.take(0.5, headerInterval = 0.5, admitsHeader = { false }))
        assertNull(schedule.inFlight)
        val changed = schedule.take(0.55, headerInterval = 0.5)!!
        assertEquals(first.id + 1, changed.id)
        assertEquals(0.55, changed.sourceTime, 0.0)
    }

    @Test
    fun disabledHeaderConsumesNoWork() {
        val schedule = GameTextSchedule()
        assertNull(schedule.take(0.0, readsHeader = false))
        assertEquals(1, schedule.take(0.0)!!.id)
    }
}
