package com.example.pcoverlaycontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiSettingsTest {

    @Test
    fun acceptsIpAndHostname() {
        assertTrue(PiSettings.isValidHost("192.168.100.245"))
        assertTrue(PiSettings.isValidHost("pi.local"))
        assertTrue(PiSettings.isValidHost("raspberrypi"))
    }

    @Test
    fun rejectsEmptyAndMalformedHosts() {
        assertFalse(PiSettings.isValidHost(""))
        assertFalse(PiSettings.isValidHost("a b"))
        assertFalse(PiSettings.isValidHost("192.168.1.5/abc"))
        assertFalse(PiSettings.isValidHost("user@host"))
        assertFalse(PiSettings.isValidHost("host:80"))
    }

    @Test
    fun baseUrlUsesHttpPort() {
        assertEquals("http://10.0.0.5:5000", PiSettings.baseUrlFor("10.0.0.5"))
    }

    @Test
    fun pinMustBe4To8Digits() {
        assertTrue(PiSettings.isValidPin("1234"))
        assertTrue(PiSettings.isValidPin("12345678"))
        assertFalse(PiSettings.isValidPin("123"))
        assertFalse(PiSettings.isValidPin("123456789"))
        assertFalse(PiSettings.isValidPin("12a4"))
        assertFalse(PiSettings.isValidPin(""))
    }

    @Test
    fun pinHashIsStableAndDoesNotContainPin() {
        val hash = PiSettings.hashPin("1234")
        assertEquals(hash, PiSettings.hashPin("1234"))
        assertNotEquals(hash, PiSettings.hashPin("1235"))
        assertEquals(64, hash.length)
        assertFalse(hash.contains("1234"))
    }
}
