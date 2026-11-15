package com.abrignoni.knowndatalogger

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification access (optional). Android tells a notification listener about every post and every update of a
 * notification. Accessibility events do not always come for an update of a notification that is already showing:
 * on a moto g play (Android 16, 15 November 2026) Zangi and Snapchat each kept one notification per chat and
 * updated it for each message, and 2 of 5 updates came with no event (Snapchat posted the fifth, by its time,
 * and no event came). While this listener is connected, CaptureService takes notifications from it and ignores
 * the accessibility events for them, so none is logged twice.
 */
class NotificationWatch : NotificationListenerService() {
    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        CaptureService.instance?.onPostedNotification(sbn.packageName, sbn.notification)
    }

    companion object {
        @Volatile var connected = false
    }
}
