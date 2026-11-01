package com.edgeai.agent.services

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

data class IncomingMessage(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

class AgentNotificationListener : NotificationListenerService() {
    companion object {
        private const val MAX_CACHED_MESSAGES = 50

        @Volatile
        private var instance: AgentNotificationListener? = null

        private val messageCache = ArrayDeque<IncomingMessage>()

        fun readRecentMessages(limit: Int = 20): List<IncomingMessage> =
            synchronized(messageCache) {
                messageCache.toList().takeLast(limit.coerceAtLeast(0))
            }

        fun clearRecentMessages() {
            synchronized(messageCache) {
                messageCache.clear()
            }
        }
    }

    private val messengerPackages =
        setOf(
            "org.telegram.messenger",
            "com.whatsapp",
            "org.thoughtcrime.securesms",
        )

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName !in messengerPackages) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE).orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        synchronized(messageCache) {
            messageCache.addLast(IncomingMessage(packageName, title, text))
            while (messageCache.size > MAX_CACHED_MESSAGES) {
                messageCache.removeFirst()
            }
        }

        // Find direct reply action
        val actions = NotificationCompat.getActionCount(sbn.notification)
        for (i in 0 until actions) {
            val action = NotificationCompat.getAction(sbn.notification, i)
            val remoteInputs = action?.remoteInputs
            if (!remoteInputs.isNullOrEmpty()) {
                // Agent can trigger quick reply via direct action
            }
        }
    }

    fun replyToNotification(action: NotificationCompat.Action, replyText: String) {
        val remoteInputs = action.remoteInputs ?: return
        val remoteInput = remoteInputs.firstOrNull() ?: return
        val actionIntent = action.actionIntent ?: return
        val intent = Intent()
        val bundle = Bundle().apply {
            putCharSequence(remoteInput.resultKey, replyText)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        actionIntent.send(this, 0, intent)
    }
}
