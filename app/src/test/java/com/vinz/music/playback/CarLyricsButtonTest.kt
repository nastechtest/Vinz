package com.vinz.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #392 (1.8.4): the session's lyrics button (notification, watch, car) now that "Lyrics in the notification" works
 * without "Lyrics in the car". Every combination of the two switches, in a car and out of one.
 */
class CarLyricsButtonTest {
    /** car switch, notification switch, in a car → lyrics showing (the button's face), then both switches after a tap. */
    private val table = listOf(
        // Not in a car: the button is the notification's switch; the car's is never touched.
        Case(car = false, phone = false, driving = false, showing = false, carAfter = false, phoneAfter = true),
        Case(car = false, phone = true, driving = false, showing = true, carAfter = false, phoneAfter = false),
        Case(car = true, phone = false, driving = false, showing = false, carAfter = true, phoneAfter = true),
        Case(car = true, phone = true, driving = false, showing = true, carAfter = true, phoneAfter = false),
        // In a car: the car's switch, as before 1.8.4.
        Case(car = false, phone = false, driving = true, showing = false, carAfter = true, phoneAfter = false),
        Case(car = true, phone = false, driving = true, showing = true, carAfter = false, phoneAfter = false),
        // Hiding in a car also takes the notification's switch off, or the line would stay on the car's screen.
        Case(car = false, phone = true, driving = true, showing = true, carAfter = false, phoneAfter = false),
        Case(car = true, phone = true, driving = true, showing = true, carAfter = false, phoneAfter = false),
    )

    private data class Case(val car: Boolean, val phone: Boolean, val driving: Boolean, val showing: Boolean, val carAfter: Boolean, val phoneAfter: Boolean)

    @Test
    fun everyCombination() {
        assertEquals(8, table.map { Triple(it.car, it.phone, it.driving) }.toSet().size)
        for (t in table) {
            val name = "car ${t.car}, notification ${t.phone}, in a car ${t.driving}"
            assertEquals(name, LyricsButton(t.showing, t.carAfter, t.phoneAfter), lyricsButton(t.car, t.phone, t.driving))
        }
    }

    @Test
    fun theButtonSaysWhatIsShowing() {
        // Its face is the same answer the overlay uses, so "Hide lyrics" is never offered over nothing (or the reverse).
        for (car in listOf(false, true)) for (phone in listOf(false, true)) for (driving in listOf(false, true)) {
            assertEquals(
                "car $car, notification $phone, in a car $driving",
                carLyricsActive(car, phone, carConnected = driving, driving = driving),
                lyricsButton(car, phone, driving).showing,
            )
        }
    }

    @Test
    fun aTapAlwaysChangesWhatIsShowing() {
        for (car in listOf(false, true)) for (phone in listOf(false, true)) for (driving in listOf(false, true)) {
            val before = lyricsButton(car, phone, driving)
            val after = lyricsButton(before.car, before.phone, driving)
            assertEquals("car $car, notification $phone, in a car $driving", !before.showing, after.showing)
        }
    }

    @Test
    fun onlyTheNotificationSwitch() {
        // The case #392 asked for: no car lyrics, lyrics in the notification. The button hides and shows them there.
        val on = lyricsButton(inCar = false, onPhone = true, driving = false)
        assertEquals(LyricsButton(showing = true, car = false, phone = false), on)
        val off = lyricsButton(on.car, on.phone, driving = false)
        assertEquals(LyricsButton(showing = false, car = false, phone = true), off)
    }
}
