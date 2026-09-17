package com.danielealbano.androidremotecontrolmcp.services.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a message into this phone's notification shade for its HOLDER to read.
 *
 * The mirror image of [NotificationProvider], and deliberately a separate seam: that one READS
 * and acts on what other apps posted, through the notification-listener special access; this one
 * is the app speaking, through the ordinary posting API, and needs no listener at all. Behind an
 * interface for JVM-testable substitution.
 */
interface NotificationPoster {
    /**
     * Whether the OS will actually show what [post] builds — false when the holder (or the
     * missing `POST_NOTIFICATIONS` grant) has notifications off for this app.
     *
     * Asked BEFORE posting rather than inferred from the result, because `notify` succeeds
     * silently when notifications are blocked: the row is simply never shown, and a caller that
     * trusted the absence of an error would report a message delivered that nobody can read.
     */
    fun areNotificationsEnabled(): Boolean

    /**
     * Posts [message] under [title], returning the id the OS row was posted under.
     *
     * Every post gets its OWN id ([HolderMessageIds]), so a second message never replaces a first
     * the holder has not read yet.
     */
    fun post(
        title: String,
        message: String,
    ): Result<Int>
}

/**
 * The ids holder messages are posted under.
 *
 * Monotonic, because an id is what the OS REPLACES a row by: a fixed id would make every message
 * overwrite the previous one. They start clear of the 1001-1004 block the app's own service and
 * status rows occupy, and wrap rather than overflow.
 */
internal object HolderMessageIds {
    /** Clear of the fixed service/status ids (1001-1004). */
    const val FIRST_ID = 2000

    private val next = AtomicInteger(FIRST_ID)

    fun next(): Int = next.getAndUpdate { if (it == Int.MAX_VALUE) FIRST_ID else it + 1 }
}

/**
 * Posts on the app's own **Messages** channel, created on demand.
 *
 * The channel is its own rather than the connector's on purpose: the connector's row reports
 * whether the device is attached, at LOW importance so it stays quiet, and a holder who mutes it
 * would otherwise also mute every message addressed to them. A message is the opposite kind of
 * thing — it is for a person to read — so it gets DEFAULT importance and a channel the holder can
 * silence independently.
 */
@Singleton
class NotificationPosterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : NotificationPoster {
        /**
         * The compat façade, whose `areNotificationsEnabled` answers for BOTH ways the shade can
         * be shut: the `POST_NOTIFICATIONS` grant missing, and the holder having turned the app's
         * notifications off.
         */
        private val notifications get() = NotificationManagerCompat.from(context)

        override fun areNotificationsEnabled(): Boolean = notifications.areNotificationsEnabled()

        override fun post(
            title: String,
            message: String,
        ): Result<Int> =
            runCatching {
                McpApplication.ensureMessagesChannel(context)
                val manager =
                    context.getSystemService(NotificationManager::class.java)
                        ?: error("NotificationManager is unavailable on this device")
                val id = HolderMessageIds.next()
                val notification =
                    NotificationCompat
                        .Builder(context, McpApplication.MESSAGES_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(message)
                        // A message can be longer than the collapsed row shows; BigTextStyle is what
                        // makes the whole of it readable once the holder expands the notification.
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                manager.notify(id, notification)
                Logger.i(TAG, "Posted a holder message as notification $id")
                id
            }

        companion object {
            private const val TAG = "MCP:NotificationPoster"
        }
    }
