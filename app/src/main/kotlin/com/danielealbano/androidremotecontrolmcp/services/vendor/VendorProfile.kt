package com.danielealbano.androidremotecontrolmcp.services.vendor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.utils.OemKeepAliveSettings
import com.danielealbano.androidremotecontrolmcp.utils.startSettingsActivity

/**
 * What one Android SKIN does differently, behind a seam, so supporting the next one is a new object
 * rather than a new branch in five call sites.
 *
 * # Why this exists
 *
 * Everything a stock Android device needs is a platform API: the battery-optimisation exemption is
 * `PowerManager`, the keyguard is `KeyguardManager`, device admin is `DevicePolicyManager`. None of
 * that belongs here. What belongs here is the residue — the things a VENDOR invented, that AOSP has
 * no API for, and that therefore cannot be written once:
 *
 *  - **Autostart.** MIUI/HyperOS, ColorOS, EMUI, Funtouch and One UI each ship their own
 *    "may this app start itself" control, in their own app, under their own component name. There
 *    is no platform intent and no platform query.
 *
 * As more phones are supported, a vendor's quirks are added as ONE [VendorProfile] rather than
 * spread across the call sites that happen to need them. A profile that is only a name and an empty
 * list is a perfectly good profile — it says "this skin needs nothing special", which is the
 * answer for stock Android and the one this file makes cheap to express.
 *
 * # Selection is by RESOLVABILITY, not by brand string
 *
 * The obvious design — read [Build.MANUFACTURER], switch on "xiaomi" — is the one this deliberately
 * avoids, for the reason `StructuralDenylist` gives about Settings package names: a hard-coded list
 * of vendor spellings fails on the cases that matter. Rebrands (Redmi, POCO), regional variants,
 * custom ROMs carrying a vendor's security app, and OEMs that simply are not in the list all get
 * the wrong answer, and the failure is silent — a button that goes nowhere.
 *
 * So the manufacturer only ORDERS the candidates, and the PackageManager decides: a profile applies
 * when a screen it names actually resolves on this phone. A Xiaomi that dropped the screen falls
 * through; a non-Xiaomi that ships it is served. [forThisDevice] is what every caller asks.
 *
 * # What a profile may NOT claim
 *
 * A profile says where to SEND the holder. It never claims to know whether the vendor setting is
 * ON: Android exposes no API for that, and the ops behind these screens have no public names — MIUI
 * addresses its autostart op by the bare number 10008, which an app can reach only through
 * hidden-API reflection that this project forbids and modern Android blocks. The platform's
 * phone-host CAN read it over adb (`phone-host-agent/internal/phonepolicy`), and that is where any
 * such state must come from.
 */
interface VendorProfile {
    /** Stable id for logs and for the docs to key against. */
    val id: String

    /** Human name of the skin, for a log line an operator reads. */
    val displayName: String

    /**
     * Manufacturer strings ([Build.MANUFACTURER], lower-cased) this profile is the FIRST guess for.
     * Only an ordering hint — a profile still has to resolve to be chosen, and one with an empty
     * set can still win on resolvability alone.
     */
    val manufacturers: Set<String>

    /**
     * Candidate autostart screens, best first. Empty means this skin has no such control, which is
     * the correct answer for stock Android rather than a gap.
     *
     * Every component listed here MUST also be in the manifest's `<queries>`, or package-visibility
     * filtering hides it from [PackageManager.resolveActivity] and the screen looks absent on a
     * phone that has it.
     */
    val autostartComponents: List<ComponentName>
}

/** MIUI / HyperOS — Xiaomi, Redmi and POCO. The reference phone this support was built on. */
object MiuiVendorProfile : VendorProfile {
    override val id = "miui"
    override val displayName = "MIUI / HyperOS"
    override val manufacturers = setOf("xiaomi", "redmi", "poco")
    override val autostartComponents =
        listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
}

/**
 * Stock Android and every skin that needs nothing beyond the platform APIs.
 *
 * It is a real profile rather than a null: the call sites then have no "no vendor" branch, and the
 * absence of an autostart screen is stated once, here, instead of being inferred from a null in
 * three places.
 */
object GenericVendorProfile : VendorProfile {
    override val id = "generic"
    override val displayName = "Android"
    override val manufacturers = emptySet<String>()
    override val autostartComponents = emptyList<ComponentName>()
}

/**
 * The profile registry and its selection rule.
 *
 * Adding a vendor is: write the object, add it to [known], and put every component it names in the
 * manifest's `<queries>`. Nothing else changes — a caller that ACTS asks [openAutostart] and a
 * surface that OFFERS the control asks [hasAutostartScreen] first, so neither knows a vendor by
 * name.
 */
object VendorProfiles {
    private const val TAG = "MCP:VendorProfiles"

    /** Every profile with something to say. [GenericVendorProfile] is the fallback, not a member. */
    val known: List<VendorProfile> = listOf(MiuiVendorProfile)

    /**
     * The profile for the phone this code is running on.
     *
     * Ordered by the manufacturer hint, then decided by what RESOLVES — so a Xiaomi build that
     * dropped the security app falls through to generic instead of offering a dead button, and a
     * phone whose brand nobody listed is still served if it ships a known screen.
     */
    fun forThisDevice(context: Context): VendorProfile = forDevice(context, Build.MANUFACTURER.orEmpty())

    /**
     * [forThisDevice] with the brand supplied rather than read.
     *
     * The split exists because [Build.MANUFACTURER] comes from a system property that does not
     * exist off a device, so it is the one input a JVM test cannot vary — and the rule worth
     * pinning is precisely that varying it changes nothing a resolvable screen did not already
     * decide.
     */
    internal fun forDevice(
        context: Context,
        manufacturer: String,
    ): VendorProfile {
        val hint = manufacturer.lowercase()
        val ordered = known.sortedByDescending { it.manufacturers.contains(hint) }
        return ordered.firstOrNull { profile ->
            profile.autostartComponents.any { resolves(context, it) }
        } ?: GenericVendorProfile
    }

    /**
     * The autostart screen to open on this phone, or null when there is none to open.
     *
     * Null is the honest answer a caller must handle by NOT offering the control — a button that
     * silently goes nowhere is worse than an absent one, and was the state of this app on every
     * non-Xiaomi phone before the seam existed.
     */
    fun autostartIntent(context: Context): Intent? {
        val profile = forThisDevice(context)
        val component = profile.autostartComponents.firstOrNull { resolves(context, it) } ?: return null
        return Intent().apply { this.component = component }
    }

    /**
     * Whether this phone has a vendor autostart screen at all — what a surface OFFERING the control
     * gates on, so that the control is absent rather than dead on every build that has none.
     */
    fun hasAutostartScreen(context: Context): Boolean = autostartIntent(context) != null

    /**
     * Opens the vendor autostart screen.
     *
     * Only call this behind [hasAutostartScreen]: the fallback here is for the launch a build
     * RESOLVES and then refuses (a vendor background-start guard), not for a phone that has no such
     * screen — landing that holder on an app-details page they never asked for explains nothing.
     */
    fun openAutostart(context: Context) {
        val intent = autostartIntent(context)
        if (intent != null && context.startSettingsActivity(intent)) return
        Log.i(TAG, "Vendor autostart screen absent or refused the launch; opening application details")
        OemKeepAliveSettings.openAppDetails(context)
    }

    /** Whether an explicit component has an activity on this phone, package visibility included. */
    private fun resolves(
        context: Context,
        component: ComponentName,
    ): Boolean =
        context.packageManager.resolveActivity(
            Intent().apply { this.component = component },
            PackageManager.MATCH_DEFAULT_ONLY,
        ) != null
}
