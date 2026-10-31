package com.example.tanlishkeyboard.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedAppsTest {

    private val store = MemoryKeyValueStore()
    private val settings = Settings(store)

    @Test
    fun `an app with bank in its name is protected`() {
        assertTrue(ProtectedApps.isBuiltIn("com.csam.icici.bank.imobile"))
        assertTrue(ProtectedApps.isBuiltIn("com.canarabank.mobility"))
        assertTrue(ProtectedApps.isBuiltIn("com.example.SomeBANKapp"))
    }

    @Test
    fun `banks and wallets whose names do not say so are protected`() {
        assertTrue(ProtectedApps.isBuiltIn("com.sbi.lotusintouch"))
        assertTrue(ProtectedApps.isBuiltIn("com.sbi.SBIFreedomPlus"))
        assertTrue(ProtectedApps.isBuiltIn("com.phonepe.app"))
        assertTrue(ProtectedApps.isBuiltIn("net.one97.paytm"))
        assertTrue(ProtectedApps.isBuiltIn("com.google.android.apps.nbu.paisa.user"))
        assertTrue(ProtectedApps.isBuiltIn("in.org.npci.upiapp"))
    }

    @Test
    fun `the apps people type Tanglish in are left alone`() {
        assertFalse(ProtectedApps.isBuiltIn("com.whatsapp"))
        assertFalse(ProtectedApps.isBuiltIn("com.google.android.apps.messaging"))
        assertFalse(ProtectedApps.isBuiltIn("com.google.android.gm"))
        assertFalse(ProtectedApps.isBuiltIn("com.android.chrome"))
    }

    @Test
    fun `an unknown foreground app is not protected`() {
        assertFalse(settings.isProtected(null))
    }

    @Test
    fun `an app the user chose is protected, and survives a restart`() {
        assertFalse(settings.isProtected("com.tamilmercantile.mpay"))

        settings.protectedApps = setOf("com.tamilmercantile.mpay", "in.co.anotherlender")

        val restarted = Settings(store)
        assertTrue(restarted.isProtected("com.tamilmercantile.mpay"))
        assertTrue(restarted.isProtected("in.co.anotherlender"))
        assertFalse(restarted.isProtected("com.whatsapp"))
    }

    @Test
    fun `choosing nothing protects only the built in apps`() {
        settings.protectedApps = emptySet()

        assertTrue(settings.protectedApps.isEmpty())
        assertTrue(settings.isProtected("com.phonepe.app"))
    }
}
