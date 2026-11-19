package com.shawady.vault.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class WifiQrTest {
    @Test fun parsesTheCommonForms() {
        assertEquals(WifiQr.Net("Office", "p@ss", "WPA2", false), WifiQr.parse("WIFI:T:WPA;S:Office;P:p@ss;;"))
        assertEquals(WifiQr.Net("Net3", "x", "WPA3", true), WifiQr.parse("WIFI:T:SAE;S:Net3;P:x;H:true;;"))
        assertEquals(WifiQr.Net("Guest", "", "Open", false), WifiQr.parse("WIFI:T:nopass;S:Guest;;"))
    }
    @Test fun handlesEscapesAndOrder() {
        assertEquals(WifiQr.Net("a;b:c", "p\\w\"d", "WPA2", false), WifiQr.parse("""WIFI:S:a\;b\:c;T:WPA;P:p\\w\"d;;"""))
    }
    @Test fun rejectsOthers() {
        assertNull(WifiQr.parse("https://x.com")); assertNull(WifiQr.parse("WIFI:T:WPA;P:x;;"))
    }
}
