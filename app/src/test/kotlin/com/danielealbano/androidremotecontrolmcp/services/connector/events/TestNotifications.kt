package com.danielealbano.androidremotecontrolmcp.services.connector.events

import com.danielealbano.androidremotecontrolmcp.services.notifications.NotificationData

/**
 * A [NotificationData] for tests, with everything the device-event plane does not read defaulted.
 *
 * The plane reports five of this class's thirteen fields; spelling out the other eight at each call
 * site would bury the one that matters in the case being tested.
 */
internal fun testNotification(
    packageName: String = "com.example.mail",
    appName: String = "Mail",
    title: String? = "Invoice",
    text: String? = "Due Friday",
    timestamp: Long = 1_757_000_000_000,
): NotificationData =
    NotificationData(
        notificationId = "hash",
        packageName = packageName,
        appName = appName,
        title = title,
        text = text,
        bigText = null,
        subText = null,
        timestamp = timestamp,
        isOngoing = false,
        isClearable = true,
        category = null,
        groupKey = null,
        actions = emptyList(),
    )
