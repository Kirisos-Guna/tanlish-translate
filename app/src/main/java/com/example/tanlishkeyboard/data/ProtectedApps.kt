package com.example.tanlishkeyboard.data

/**
 * Banking and payment apps the floating button stays out of without being asked.
 *
 * These apps treat any window drawn over them as a possible attack: touches get dropped, or the app
 * warns about an overlay and refuses to go on. There is no way to float over them politely, and Android
 * offers no "finance" app category to ask about, so the only thing that works is not being on screen.
 *
 * Android has no complete list of these either. Being wrong in this direction only costs a hidden
 * button, so the name check is deliberately broad, and anything it misses is what
 * [Settings.protectedApps] is for.
 */
object ProtectedApps {

    /** Banks and wallets whose package name does not give them away. */
    private val PACKAGE_PREFIXES = listOf(
        "com.axis.",
        "com.dreamplug.androidapp", // CRED
        "com.fedmobile", // Federal Bank
        "com.fss.pnb", // Punjab National Bank
        "com.google.android.apps.nbu.paisa.", // Google Pay
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "com.paypal.",
        "com.phonepe.",
        "com.sbi.",
        "com.snapwork.hdfc",
        "in.org.npci.", // BHIM
        "net.one97.paytm",
    )

    fun isBuiltIn(packageName: String): Boolean {
        val name = packageName.lowercase()
        return "bank" in name || PACKAGE_PREFIXES.any { name.startsWith(it) }
    }
}
