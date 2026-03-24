package uk.co.sevenbirches.homelab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class HomelabFirebaseService : FirebaseMessagingService() {

    // ── Token refresh ──────────────────────────────────────────────────────
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply { put("token", token) }
                    .toString().toRequestBody("application/json".toMediaType())
                OkHttpClient().newCall(
                    Request.Builder()
                        .url("$BASE_URL/fcm/register")
                        .post(body)
                        .build()
                ).execute()
            } catch (e: Exception) {
                // Silent — will register next time app opens
            }
        }
    }

    // ── Incoming message ───────────────────────────────────────────────────
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title         = message.notification?.title ?: message.data["title"] ?: "Homelab Alert"
        val body          = message.notification?.body  ?: message.data["body"]  ?: ""
        // alert_monitor.py sends these as data keys
        val containerName = message.data["container"] ?: ""
        val alertType     = message.data["metric"]    ?: ""

        ensureNotificationChannel()
        showActionableNotification(title, body, containerName, alertType)
    }

    // ── Build notification with ACK + SNOOZE buttons ───────────────────────
    private fun showActionableNotification(
        title: String,
        body: String,
        containerName: String,
        alertType: String
    ) {
        // Stable ID so the same alert replaces itself rather than stacking
        val notificationId = (containerName + alertType).hashCode()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Tapping the notification body opens the app on the Alerts tab
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "alerts")
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ACK button
        val ackIntent = Intent(this, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_ACKNOWLEDGE
            putExtra(AlertActionReceiver.EXTRA_CONTAINER,       containerName)
            putExtra(AlertActionReceiver.EXTRA_ALERT_TYPE,      alertType)
            putExtra(AlertActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val ackPending = PendingIntent.getBroadcast(
            this,
            notificationId,            // unique request code per alert
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // SNOOZE 1H button
        val snoozeIntent = Intent(this, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_SNOOZE
            putExtra(AlertActionReceiver.EXTRA_CONTAINER,       containerName)
            putExtra(AlertActionReceiver.EXTRA_ALERT_TYPE,      alertType)
            putExtra(AlertActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val snoozePending = PendingIntent.getBroadcast(
            this,
            notificationId + 1,        // +1 so it doesn't collide with ack pending intent
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)          // stays until user acts
            .setContentIntent(tapPending)
            .addAction(0, "✓  ACK",        ackPending)
            .addAction(0, "💤  SNOOZE 1H", snoozePending)
            .build()

        nm.notify(notificationId, notification)
    }

    // ── Notification channel (Android 8+) ─────────────────────────────────
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Homelab Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical and warning alerts from the homelab monitoring agent"
                enableVibration(true)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "homelab_alerts"
    }
}
