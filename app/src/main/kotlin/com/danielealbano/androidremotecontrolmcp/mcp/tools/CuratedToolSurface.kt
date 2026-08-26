package com.danielealbano.androidremotecontrolmcp.mcp.tools

/**
 * The platform-curated tool surface (`docs/phones/android_remote_control.md` §4).
 *
 * Upstream ships 56 tools; the platform exposes only the small, verb-shaped subset §4 lists,
 * and treats upstream's surface as "the menu, not the contract". Curation is a POSITIVE
 * allowlist enforced at the registration site
 * ([com.danielealbano.androidremotecontrolmcp.services.mcp.McpToolServerFactory]): a tool whose
 * base name is not in [ALLOWLIST] is never registered, so `tools/list` returns exactly this
 * set. The implementations of the excluded tools stay in the tree — absence, not deletion, is
 * the control — they are simply not registered.
 *
 * Deliberately NOT exposed in v1 (§4), though upstream implements them: the file/storage tools,
 * camera, microphone, location-as-a-tool ([get_location]; the device *action* `locate` is a
 * separate control plane, not a tool), clipboard, sharing, and raw intent dispatch
 * (`send_intent`).
 *
 * Every callable tool carries a [ToolGroup]. The group is read vs write per the §4 table and is
 * load-bearing on the platform, where it drives mode filtering and the approval floor (§6.2):
 * WRITE-group ("acting") tools park an approval by default, READ-group tools are perception only.
 *
 * Names here are the upstream base names (before the `android_[<slug>_]` prefix). The mapping to
 * the §4 logical names is noted per row.
 */
object CuratedToolSurface {
    /** Read vs write, per the §4 tool table. Read = perception; write = acts on the device. */
    enum class ToolGroup { READ, WRITE }

    // ── READ (perception) ──────────────────────────────────────────────────────────────────

    /** §4 `android_screen_state` (+ `android_screenshot`, the optional `include_screenshot`). */
    const val GET_SCREEN_STATE = "get_screen_state"

    /** §4 `android_find_nodes`. */
    const val FIND_NODES = "find_nodes"

    /** §4 `android_list_apps`. */
    const val LIST_APPS = "list_apps"

    /** §4 `android_read_notifications`. */
    const val NOTIFICATION_LIST = "notification_list"

    /**
     * `android_get_sim_info` — reads the tethered SIM's own number/ICCID/carrier for
     * provenance-verifying an MSISDN without SMS (docs/phones/android_remote_control.md §4). READ:
     * it is device-identity perception, and the host's `read_sim_number` action consumes it. It
     * needs `READ_PHONE_NUMBERS`, granted by the provisioning path like the other grants.
     */
    const val GET_SIM_INFO = "get_sim_info"

    // ── WRITE (acting) ─────────────────────────────────────────────────────────────────────

    /** §4 `android_tap_node`. */
    const val TAP_NODE = "tap_node"

    /** §4 `android_tap`. */
    const val TAP = "tap"

    /** §4 `android_long_press`. */
    const val LONG_PRESS = "long_press"

    /** §4 `android_swipe`. */
    const val SWIPE = "swipe"

    /** §4 `android_scroll`. */
    const val SCROLL = "scroll"

    /** §4 `android_press_key` (back / home / recents / enter and similar). */
    const val PRESS_KEY = "press_key"

    /**
     * §4 `android_type` — plain text entry (append at the field's end). `node_id` is optional:
     * omitted, the text goes to the field that currently holds input focus.
     */
    const val TYPE = "type_append_text"

    /** §4 `android_launch_app` — allowlisted apps only, checked on-device (§6.4). */
    const val OPEN_APP = "open_app"

    /** §4 `android_close_app`. */
    const val CLOSE_APP = "close_app"

    /** §4 `android_open_uri`. */
    const val OPEN_URI = "open_uri"

    /** §4 `android_dismiss_notification`. */
    const val NOTIFICATION_DISMISS = "notification_dismiss"

    /** Base name → group for every curated tool. Its key set IS [ALLOWLIST]. */
    val GROUPS: Map<String, ToolGroup> =
        mapOf(
            GET_SCREEN_STATE to ToolGroup.READ,
            FIND_NODES to ToolGroup.READ,
            LIST_APPS to ToolGroup.READ,
            NOTIFICATION_LIST to ToolGroup.READ,
            GET_SIM_INFO to ToolGroup.READ,
            TAP_NODE to ToolGroup.WRITE,
            TAP to ToolGroup.WRITE,
            LONG_PRESS to ToolGroup.WRITE,
            SWIPE to ToolGroup.WRITE,
            SCROLL to ToolGroup.WRITE,
            PRESS_KEY to ToolGroup.WRITE,
            TYPE to ToolGroup.WRITE,
            OPEN_APP to ToolGroup.WRITE,
            CLOSE_APP to ToolGroup.WRITE,
            OPEN_URI to ToolGroup.WRITE,
            NOTIFICATION_DISMISS to ToolGroup.WRITE,
        )

    /** The curated base names — the positive allowlist the factory enforces. */
    val ALLOWLIST: Set<String> = GROUPS.keys

    /** The group of a curated tool base name, or null if the tool is not curated. */
    fun groupOf(baseName: String): ToolGroup? = GROUPS[baseName]

    /** The base name of the `open_app` verb the app allowlist (§6.4) gates on package. */
    const val LAUNCH_APP: String = OPEN_APP
}
