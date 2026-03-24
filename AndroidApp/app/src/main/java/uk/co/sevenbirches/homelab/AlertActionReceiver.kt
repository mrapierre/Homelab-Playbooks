package uk.co.sevenbirches.homelab

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Handles ACK and SNOOZE taps from notification action buttons.
 *
 * Flow:
 *   User taps button → OS fires this receiver → goAsync() keeps it alive
 *   → coroutine POSTs to metrics API → notification dismissed on success
 *
 * If the API call fails the notification stays visible so the user can retry.
 */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val containerName  = intent.getStringExtra(EXTRA_CONTAINER)    ?: return
        val alertType      = intent.getStringExtra(EXTRA_ALERT_TYPE)   ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val action         = intent.action                             ?: return

        // goAsync() prevents the system killing us before the network call finishes
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = when (action) {
                    ACTION_ACKNOWLEDGE -> "acknowledge"
                    ACTION_SNOOZE      -> "snooze"
                    else               -> return@launch
                }

                val bodyJson = JSONObject().apply {
                    put("container_name", containerName)
                    put("alert_type", alertType)
                    if (action == ACTION_SNOOZE) put("hours", 1)
                }.toString()

                val response = OkHttpClient().newCall(
                    Request.Builder()
                        .url("$BASE_URL/alerts/$endpoint")
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()

                if (response.isSuccessful) {
                    // Dismiss the notification — action is complete
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notificationId)
                }
                // If not successful, leave notification visible so user can retry

            } catch (e: Exception) {
                // Network error — leave notification visible
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACKNOWLEDGE    = "uk.co.sevenbirches.homelab.ACK"
        const val ACTION_SNOOZE         = "uk.co.sevenbirches.homelab.SNOOZE"
        const val EXTRA_CONTAINER       = "container_name"
        const val EXTRA_ALERT_TYPE      = "alert_type"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
