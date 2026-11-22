package com.vinz.music

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.playback.carLyricsActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #392 (1.8.4): lyrics in the notification are their own switch, apart from "Lyrics in the car". And once, for those
 * who had the phone's switch on under a car switch that's off (no lyrics anywhere until now): it's turned off, so
 * nothing changes for them until they turn the new row on.
 */
class CarLyricsActiveTest {
    @Test
    fun eachSwitchWorksOnItsOwn() {
        // inCar, onPhone → lyrics shown: while really in a car, and otherwise.
        val expected = listOf(
            Triple(false, false, false to false),
            // Only the phone's switch: lyrics everywhere (before 1.8.4: nowhere).
            Triple(false, true, true to true),
            // Only the car's: in the car.
            Triple(true, false, true to false),
            Triple(true, true, true to true),
        )
        for ((inCar, onPhone, shown) in expected) {
            val (driving, parked) = shown
            assertEquals("car $inCar, phone $onPhone, driving", driving, carLyricsActive(inCar, onPhone, carConnected = true, driving = true))
            // Android Auto's phone app stays connected in the background: a connection alone isn't a car.
            assertEquals("car $inCar, phone $onPhone, connected but not driving", parked, carLyricsActive(inCar, onPhone, carConnected = true, driving = false))
            assertEquals("car $inCar, phone $onPhone, driving with no car connected", parked, carLyricsActive(inCar, onPhone, carConnected = false, driving = true))
            assertEquals("car $inCar, phone $onPhone, no car", parked, carLyricsActive(inCar, onPhone, carConnected = false, driving = false))
        }
    }

    private val car = booleanPreferencesKey("car_lyrics")
    private val phone = booleanPreferencesKey("car_lyrics_on_phone")

    @Test
    fun phoneOnUnderACarSwitchThatsOffIsTurnedOff() {
        val p = mutablePreferencesOf(car to false, phone to true)
        SettingsRepository.splitLyricsOnPhone(p)
        assertEquals(false, p[phone])
        assertEquals("the car's switch is left as it was", false, p[car])
    }

    @Test
    fun everyoneElseKeepsTheirs() {
        // Both on: lyrics on the phone before and after.
        val both = mutablePreferencesOf(car to true, phone to true)
        SettingsRepository.splitLyricsOnPhone(both)
        assertEquals(true, both[phone])
        assertEquals(true, both[car])
        // The car's switch never touched is on (its default): the phone's stays.
        val untouched = mutablePreferencesOf(phone to true)
        SettingsRepository.splitLyricsOnPhone(untouched)
        assertEquals(true, untouched[phone])
        assertNull(untouched[car])
        // Nothing to change: nothing is written for either switch.
        val carOff = mutablePreferencesOf(car to false)
        SettingsRepository.splitLyricsOnPhone(carOff)
        assertNull(carOff[phone])
        val phoneOff = mutablePreferencesOf(car to false, phone to false)
        SettingsRepository.splitLyricsOnPhone(phoneOff)
        assertEquals(false, phoneOff[phone])
        val fresh = mutablePreferencesOf()
        SettingsRepository.splitLyricsOnPhone(fresh)
        assertNull(fresh[phone])
        assertNull(fresh[car])
    }

    @Test
    fun itRunsOnce() {
        // After it ran, someone turns the car's lyrics off and the notification's on: that's their choice now.
        val p = mutablePreferencesOf(car to true, phone to false)
        SettingsRepository.splitLyricsOnPhone(p)
        p[car] = false
        p[phone] = true
        SettingsRepository.splitLyricsOnPhone(p)
        assertEquals(true, p[phone])
        // The same for the one it did change, and for a new install.
        val changed = mutablePreferencesOf(car to false, phone to true)
        SettingsRepository.splitLyricsOnPhone(changed)
        changed[phone] = true
        SettingsRepository.splitLyricsOnPhone(changed)
        assertEquals(true, changed[phone])
        val fresh = mutablePreferencesOf()
        SettingsRepository.splitLyricsOnPhone(fresh)
        fresh[car] = false
        fresh[phone] = true
        SettingsRepository.splitLyricsOnPhone(fresh)
        assertEquals(true, fresh[phone])
    }
}
