package com.danielealbano.androidremotecontrolmcp.ui.navigation

sealed class TopLevelRoute(
    val route: String,
) {
    data object Server : TopLevelRoute("server")

    data object Settings : TopLevelRoute("settings")

    data object About : TopLevelRoute("about")
}

sealed class SettingsRoute(
    val route: String,
) {
    data object Index : SettingsRoute("settings/index")

    data object General : SettingsRoute("settings/general")

    data object McpTools : SettingsRoute("settings/mcp_tools")

    data object Permissions : SettingsRoute("settings/permissions")

    data object Storage : SettingsRoute("settings/storage")

    data object ChannelSettings : SettingsRoute("settings/channel")

    data object NotificationFilter : SettingsRoute("settings/channel/notification_filter")

    data object WifiMonitor : SettingsRoute("settings/channel/wifi_monitor")
}
