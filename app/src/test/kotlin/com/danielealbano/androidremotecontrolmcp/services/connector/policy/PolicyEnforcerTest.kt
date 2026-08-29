package com.danielealbano.androidremotecontrolmcp.services.connector.policy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The enforcement point itself: which commands the device refuses, with which typed code, and
 * in which order. These are the assertions that make §6.4's "the device refusing is the
 * guarantee" true rather than aspirational — every one of them is a refusal the platform is
 * NOT relied upon to make.
 */
class PolicyEnforcerTest {
    /** A scriptable [DeviceEnvironment]; every field is a plain var the test moves. */
    private class FakeEnvironment(
        var now: Long = 0,
        var minute: Int = 12 * 60,
        var locked: Boolean? = false,
        var foreground: String? = "com.example.notes",
        var own: String = "com.danielealbano.androidremotecontrolmcp",
        var settings: Set<String> = emptySet(),
    ) : DeviceEnvironment {
        override fun nowMillis(): Long = now

        override fun minuteOfDay(): Int = minute

        override fun isScreenLocked(): Boolean? = locked

        override fun foregroundPackage(): String? = foreground

        override fun ownPackage(): String = own

        override fun settingsPackages(): Set<String> = settings
    }

    private fun toolCall(
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", name)
                putJsonObject("arguments") { arguments.forEach { (k, v) -> put(k, v) } }
            }
        }

    private fun openPolicy(
        activeHours: String = "",
        posture: String = DevicePolicy.Posture.ON_PHONE_LIST,
        apps: List<String> = emptyList(),
        rate: RateLimit = RateLimit(),
        paused: Boolean = false,
    ) = DevicePolicy(
        version = "v1",
        activeHours = activeHours,
        drivableAppPosture = posture,
        drivableApps = apps,
        rateLimit = rate,
        paused = paused,
    )

    private fun refusalOf(decision: PolicyDecision): String {
        assertTrue(decision is PolicyDecision.Refused, "expected a refusal, got $decision")
        return (decision as PolicyDecision.Refused).error
    }

    // ── the fail-closed default ───────────────────────────────────────────────────────

    @Test
    fun `with no snapshot every tool call is refused`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        assertEquals(PolicyRefusal.UNAVAILABLE, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
        assertEquals(PolicyRefusal.UNAVAILABLE, refusalOf(enforcer.evaluate(toolCall("android_get_screen_state"))))
    }

    @Test
    fun `session methods are allowed even with no snapshot`() {
        // The caller holds ONE long-lived MCP session across reconnects; refusing its
        // negotiation would break the session rather than protect the device.
        val enforcer = PolicyEnforcer(FakeEnvironment())
        val initialize = buildJsonObject { put("method", "initialize") }
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(initialize))
    }

    @Test
    fun `clearing the policy returns the device to refusing`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy())
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_tap")))
        enforcer.clear()
        assertEquals(PolicyRefusal.UNAVAILABLE, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    // ── pause, lock screen, hours ─────────────────────────────────────────────────────

    @Test
    fun `a paused device refuses every command, reads included`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy(paused = true))
        assertEquals(PolicyRefusal.PAUSED, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
        assertEquals(PolicyRefusal.PAUSED, refusalOf(enforcer.evaluate(toolCall("android_get_screen_state"))))
    }

    @Test
    fun `nothing executes while the screen is locked`() {
        val env = FakeEnvironment(locked = true)
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        assertEquals(PolicyRefusal.SCREEN_LOCKED, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `an unreadable keyguard counts as locked`() {
        val env = FakeEnvironment(locked = null)
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        assertEquals(PolicyRefusal.SCREEN_LOCKED, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `a command outside the active-hours window is refused`() {
        val env = FakeEnvironment(minute = 3 * 60)
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy(activeHours = "08:00-22:00"))
        assertEquals(PolicyRefusal.OUTSIDE_ACTIVE_HOURS, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
        assertTrue(enforcer.isOutsideActiveHours())
    }

    @Test
    fun `inside the window the connector reports the window open and schedules its close`() {
        val env = FakeEnvironment(minute = 21 * 60 + 59)
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy(activeHours = "08:00-22:00"))
        assertFalse(enforcer.isOutsideActiveHours())
        assertEquals(60_000L, enforcer.millisUntilWindowCloses())
        assertEquals(0L, enforcer.millisUntilWindowOpens())
    }

    @Test
    fun `outside the window the reopen delay is reported for the detach sleep`() {
        val env = FakeEnvironment(minute = 23 * 60)
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy(activeHours = "08:00-22:00"))
        assertEquals(9 * 60 * 60_000L, enforcer.millisUntilWindowOpens())
    }

    @Test
    fun `with no window there is nothing to detach for`() {
        val enforcer = PolicyEnforcer(FakeEnvironment(minute = 3 * 60))
        enforcer.apply(openPolicy(activeHours = ""))
        assertFalse(enforcer.isOutsideActiveHours())
        assertEquals(0L, enforcer.millisUntilWindowCloses())
    }

    // ── the rate cap ──────────────────────────────────────────────────────────────────

    @Test
    fun `the rate cap refuses once the window's budget is spent`() {
        val env = FakeEnvironment()
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy(rate = RateLimit(commands = 2, windowSeconds = 60)))
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_tap")))
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_tap")))
        assertEquals(PolicyRefusal.RATE_LIMITED, refusalOf(enforcer.evaluate(toolCall("android_tap"))))

        env.now = 60_000
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_tap")))
    }

    @Test
    fun `a command refused earlier in the order never charges the rate cap`() {
        // Otherwise a paused or out-of-hours device would silently burn its budget and be
        // rate-limited the moment it was resumed.
        val env = FakeEnvironment()
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy(rate = RateLimit(commands = 1, windowSeconds = 60), paused = true))
        repeat(5) { assertEquals(PolicyRefusal.PAUSED, refusalOf(enforcer.evaluate(toolCall("android_tap")))) }

        enforcer.apply(openPolicy(rate = RateLimit(commands = 1, windowSeconds = 60)))
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_tap")))
    }

    // ── the structural refusals ───────────────────────────────────────────────────────

    @Test
    fun `launching the OS Settings app is refused whatever the policy says`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        // The most permissive policy possible: no window, no cap, no allowlist.
        enforcer.apply(openPolicy())
        val decision = enforcer.evaluate(toolCall("android_open_app", mapOf("package_id" to "com.android.settings")))
        assertEquals(PolicyRefusal.STRUCTURALLY_DENIED, refusalOf(decision))
    }

    @Test
    fun `launching this app itself is refused`() {
        val env = FakeEnvironment(own = "com.danielealbano.androidremotecontrolmcp")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        val decision = enforcer.evaluate(toolCall("android_open_app", mapOf("package_id" to env.own)))
        assertEquals(PolicyRefusal.STRUCTURALLY_DENIED, refusalOf(decision))
    }

    @Test
    fun `writing into the foreground Settings app is refused`() {
        // The teeth of the rule: the agent can navigate to Settings with Back and Home
        // whatever we do about launching, so what must be refused is INPUT while it is there.
        val env = FakeEnvironment(foreground = "com.android.settings")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        assertEquals(PolicyRefusal.STRUCTURALLY_DENIED, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `reading the Settings screen is allowed`() {
        // Perception is not agency: blinding the agent would stop it seeing that it needs to
        // press Back, and it discloses nothing the holder is not already looking at.
        val env = FakeEnvironment(foreground = "com.android.settings")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_get_screen_state")))
    }

    @Test
    fun `a deep link naming a denied package is refused`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy())
        val decision =
            enforcer.evaluate(toolCall("android_open_uri", mapOf("uri" to "package:com.android.settings")))
        assertEquals(PolicyRefusal.STRUCTURALLY_DENIED, refusalOf(decision))
    }

    @Test
    fun `an intent uri naming a denied package is refused`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy())
        val uri = "intent://x#Intent;scheme=https;package=com.android.settings;end"
        assertEquals(
            PolicyRefusal.STRUCTURALLY_DENIED,
            refusalOf(enforcer.evaluate(toolCall("android_open_uri", mapOf("uri" to uri)))),
        )
    }

    @Test
    fun `an ordinary web deep link is judged by the foreground app, not by its host`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy())
        val decision = enforcer.evaluate(toolCall("android_open_uri", mapOf("uri" to "https://example.com/x")))
        assertEquals(PolicyDecision.Allowed, decision)
    }

    // ── the drivable-app allowlist ────────────────────────────────────────────────────

    @Test
    fun `an empty allowlist under the on-phone-list posture restricts nothing`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy(posture = DevicePolicy.Posture.ON_PHONE_LIST, apps = emptyList()))
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_tap")))
    }

    @Test
    fun `an empty allowlist under allowlist-only drives nothing`() {
        // Deliberate: an org that chose the stricter posture and allowlisted nothing has
        // asked for a fleet that drives no app, and the device says so on every call.
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy(posture = DevicePolicy.Posture.ALLOWLIST_ONLY, apps = emptyList()))
        assertEquals(PolicyRefusal.APP_NOT_DRIVABLE, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `the allowlist admits a named app and refuses the rest`() {
        val env = FakeEnvironment(foreground = "com.example.notes")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(
            openPolicy(posture = DevicePolicy.Posture.ALLOWLIST_ONLY, apps = listOf("com.example.notes")),
        )
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_tap")))

        env.foreground = "com.example.bank"
        assertEquals(PolicyRefusal.APP_NOT_DRIVABLE, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `launching a non-allowlisted app is refused by its target, before it launches`() {
        val enforcer = PolicyEnforcer(FakeEnvironment(foreground = "com.example.notes"))
        enforcer.apply(
            openPolicy(posture = DevicePolicy.Posture.ALLOWLIST_ONLY, apps = listOf("com.example.notes")),
        )
        val decision = enforcer.evaluate(toolCall("android_open_app", mapOf("package_id" to "com.example.bank")))
        assertEquals(PolicyRefusal.APP_NOT_DRIVABLE, refusalOf(decision))
    }

    @Test
    fun `an unknown foreground app is refused, never assumed drivable`() {
        val enforcer = PolicyEnforcer(FakeEnvironment(foreground = null))
        enforcer.apply(openPolicy())
        assertEquals(PolicyRefusal.APP_NOT_DRIVABLE, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `an unrecognised posture is treated as the stricter one`() {
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy(posture = "something-new", apps = emptyList()))
        assertEquals(PolicyRefusal.APP_NOT_DRIVABLE, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `a read tool is never refused by the allowlist`() {
        val enforcer = PolicyEnforcer(FakeEnvironment(foreground = "com.example.bank"))
        enforcer.apply(openPolicy(posture = DevicePolicy.Posture.ALLOWLIST_ONLY, apps = listOf("com.example.notes")))
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(toolCall("android_get_screen_state")))
    }

    // ── the navigation escape ─────────────────────────────────────────────────────────

    private fun pressKey(key: String): JsonObject = toolCall("android_press_key", mapOf("key" to key))

    @Test
    fun `back home and recents escape a structurally denied foreground`() {
        // Without this the device is uncommandable, not merely constrained: press_key names no
        // target, so the connector's own UI or a system crash dialog in front refused every
        // command including Home, recoverable only by hand. A global action changes which app
        // is in front and injects nothing into the protected one.
        val env = FakeEnvironment(foreground = "com.android.settings")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        CommandDescriptor.GLOBAL_NAVIGATION_KEYS.forEach { key ->
            assertEquals(PolicyDecision.Allowed, enforcer.evaluate(pressKey(key)), "key=$key")
        }
    }

    @Test
    fun `the escape covers this app's own UI and the system ui, not only Settings`() {
        val env = FakeEnvironment(own = "com.danielealbano.androidremotecontrolmcp")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        listOf(env.own, "com.android.systemui").forEach { denied ->
            env.foreground = denied
            assertEquals(PolicyDecision.Allowed, enforcer.evaluate(pressKey("HOME")), "foreground=$denied")
        }
    }

    @Test
    fun `the keys that act inside the focused app stay refused there`() {
        // ENTER confirms whatever dialog Settings is showing, SPACE toggles a checkbox and DEL
        // destroys text — all of it input into the protected window, which is the agency the
        // denylist exists to prevent.
        val env = FakeEnvironment(foreground = "com.android.settings")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        listOf("ENTER", "DEL", "TAB", "SPACE").forEach { key ->
            assertEquals(
                PolicyRefusal.STRUCTURALLY_DENIED,
                refusalOf(enforcer.evaluate(pressKey(key))),
                "key=$key",
            )
        }
    }

    @Test
    fun `the escape is keyed on the key, so an unknown one is refused like any other write`() {
        val env = FakeEnvironment(foreground = "com.android.settings")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        assertEquals(PolicyRefusal.STRUCTURALLY_DENIED, refusalOf(enforcer.evaluate(pressKey("POWER"))))
        assertEquals(
            PolicyRefusal.STRUCTURALLY_DENIED,
            refusalOf(enforcer.evaluate(toolCall("android_press_key"))),
        )
    }

    @Test
    fun `the key is matched however the caller cased it`() {
        val env = FakeEnvironment(foreground = "com.android.settings")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy())
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(pressKey("home")))
    }

    @Test
    fun `a non-allowlisted foreground is escapable too`() {
        // Being unable to leave a non-allowlisted app is the same dead end as being unable to
        // leave a denied one, and the rule that resolves both is "always able to navigate
        // away". Input into that app stays refused.
        val env = FakeEnvironment(foreground = "com.example.bank")
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(
            openPolicy(posture = DevicePolicy.Posture.ALLOWLIST_ONLY, apps = listOf("com.example.notes")),
        )
        CommandDescriptor.GLOBAL_NAVIGATION_KEYS.forEach { key ->
            assertEquals(PolicyDecision.Allowed, enforcer.evaluate(pressKey(key)), "key=$key")
        }
        assertEquals(PolicyRefusal.APP_NOT_DRIVABLE, refusalOf(enforcer.evaluate(pressKey("ENTER"))))
        assertEquals(PolicyRefusal.APP_NOT_DRIVABLE, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `an undeterminable foreground is escapable, because it is the same dead end`() {
        val enforcer = PolicyEnforcer(FakeEnvironment(foreground = null))
        enforcer.apply(openPolicy())
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(pressKey("HOME")))
        assertEquals(PolicyRefusal.APP_NOT_DRIVABLE, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `the escape never reaches a call that names its own target`() {
        // The escape is about leaving the app in front; naming a target is not leaving, so a
        // stray `key` argument on a launch must not buy anything.
        val enforcer = PolicyEnforcer(FakeEnvironment())
        enforcer.apply(openPolicy())
        val launch =
            toolCall("android_open_app", mapOf("package_id" to "com.android.settings", "key" to "HOME"))
        assertEquals(PolicyRefusal.STRUCTURALLY_DENIED, refusalOf(enforcer.evaluate(launch)))

        val deepLink = toolCall("android_open_uri", mapOf("uri" to "package:com.android.settings", "key" to "HOME"))
        assertEquals(PolicyRefusal.STRUCTURALLY_DENIED, refusalOf(enforcer.evaluate(deepLink)))
    }

    @Test
    fun `the escape does not survive the checks that come before the app-scoped ones`() {
        // It exempts the foreground rules and nothing else: a paused, locked, out-of-hours or
        // rate-capped device still refuses Home, which is what keeps "nothing acts at 3am" and
        // "nothing acts on a locked screen" absolute.
        val env = FakeEnvironment(foreground = "com.android.settings")
        val enforcer = PolicyEnforcer(env)

        enforcer.apply(openPolicy(paused = true))
        assertEquals(PolicyRefusal.PAUSED, refusalOf(enforcer.evaluate(pressKey("HOME"))))

        env.locked = true
        enforcer.apply(openPolicy())
        assertEquals(PolicyRefusal.SCREEN_LOCKED, refusalOf(enforcer.evaluate(pressKey("HOME"))))

        env.locked = false
        env.minute = 3 * 60
        enforcer.apply(openPolicy(activeHours = "08:00-22:00"))
        assertEquals(PolicyRefusal.OUTSIDE_ACTIVE_HOURS, refusalOf(enforcer.evaluate(pressKey("HOME"))))

        env.minute = 12 * 60
        enforcer.apply(openPolicy(rate = RateLimit(commands = 1, windowSeconds = 60)))
        assertEquals(PolicyDecision.Allowed, enforcer.evaluate(pressKey("HOME")))
        assertEquals(PolicyRefusal.RATE_LIMITED, refusalOf(enforcer.evaluate(pressKey("HOME"))))
    }

    // ── ordering ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the pause refusal wins over every later rule`() {
        // The refusal a caller sees must name the FIRST reason, so the audit trail says
        // "paused" rather than an incidental "not drivable".
        val env = FakeEnvironment(foreground = "com.android.settings", locked = true, minute = 3 * 60)
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy(activeHours = "08:00-22:00", paused = true))
        assertEquals(PolicyRefusal.PAUSED, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    @Test
    fun `the lock-screen refusal wins over the active-hours one`() {
        val env = FakeEnvironment(locked = true, minute = 3 * 60)
        val enforcer = PolicyEnforcer(env)
        enforcer.apply(openPolicy(activeHours = "08:00-22:00"))
        assertEquals(PolicyRefusal.SCREEN_LOCKED, refusalOf(enforcer.evaluate(toolCall("android_tap"))))
    }

    // ── the pure helpers ──────────────────────────────────────────────────────────────

    @Test
    fun `uri package extraction reads the forms that name one`() {
        assertEquals("com.example.a", PolicyEnforcer.packageNamedByUri("package:com.example.a"))
        assertEquals("com.example.a", PolicyEnforcer.packageNamedByUri("android-app://com.example.a/path"))
        assertEquals(
            "com.example.a",
            PolicyEnforcer.packageNamedByUri("intent://host#Intent;package=com.example.a;end"),
        )
        assertEquals(null, PolicyEnforcer.packageNamedByUri("https://example.com"))
        assertEquals(null, PolicyEnforcer.packageNamedByUri("package:"))
    }

    @Test
    fun `app drivability is decided by posture and list alone`() {
        val onPhoneList = openPolicy(posture = DevicePolicy.Posture.ON_PHONE_LIST)
        assertTrue(PolicyEnforcer.isAppDrivable(onPhoneList, "anything"))

        val allowlistOnly = openPolicy(posture = DevicePolicy.Posture.ALLOWLIST_ONLY, apps = listOf("com.a"))
        assertTrue(PolicyEnforcer.isAppDrivable(allowlistOnly, "com.a"))
        assertFalse(PolicyEnforcer.isAppDrivable(allowlistOnly, "com.b"))
    }
}
