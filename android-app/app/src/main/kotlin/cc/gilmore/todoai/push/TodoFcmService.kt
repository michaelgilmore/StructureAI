package cc.gilmore.todoai.push

import android.util.Log
import cc.gilmore.todoai.TodoAiApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Client half of due-date push notifications. The server half — an SNS
 * platform endpoint subscribed to the reminders topic, created from the
 * token this registers — now exists in aws-backend (see
 * `lambdas/device-token/`, `lambdas/reminder-scan/`, and
 * `infra/lib/notifications-stack.ts`; requires a one-time manual Secrets
 * Manager step, see `aws-backend/README.md`'s "Push notifications"
 * section). This service also still works on its own for local testing
 * without any of that deployed: send an FCM message directly via the
 * Firebase console or the FCM API and it'll show up as a notification.
 *
 * `onNewToken` alone isn't sufficient to get a device registered, though —
 * see [cc.gilmore.todoai.repository.TodoRepository.refreshDeviceTokenRegistration]'s
 * doc comment for why NavGraph.kt also calls that on sign-in.
 *
 * `data` payload sent by real reminders: `todoId`, `title`, `body` — see
 * `lambdas/reminder-scan/index.ts`'s `publishReminder`. Falls back to
 * `remoteMessage.notification` if a message ever arrives as a
 * notification-type message instead of a data-only one (e.g. a manual test
 * send from the Firebase console, which defaults to that shape).
 */
class TodoFcmService : FirebaseMessagingService() {
    // A plain CoroutineScope(Dispatchers.IO) with no exception handler
    // means an uncaught exception inside launch{} propagates to the
    // thread's default uncaught-exception handler and crashes the process
    // — not just this service. registerDeviceTokenIfNeeded already wraps
    // its own network call in runCatching (see TodoRepository.kt), but the
    // try/catch here is cheap insurance against anything upstream of that
    // (e.g. a bad `container` reference) doing the same.
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    override fun onNewToken(token: String) {
        // Logged at INFO so you can grab it from Logcat and use it with
        // Firebase console → Cloud Messaging → "Send test message" for a
        // manual sanity check independent of the real server path — see
        // android-app/README.md.
        //
        // This usually fires once, at first install, often before sign-in
        // completes — so it isn't the only place that registers a device;
        // see TodoRepository.refreshDeviceTokenRegistration's doc comment.
        Log.i(TAG, "FCM token: $token")
        scope.launch {
            try {
                val app = applicationContext as TodoAiApplication
                app.container.todoRepository.registerDeviceTokenIfNeeded(token)
                Log.d(TAG, "onNewToken: registerDeviceTokenIfNeeded call completed")
            } catch (e: Throwable) {
                Log.e(TAG, "onNewToken: failed to register device token", e)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val todoId = remoteMessage.data["todoId"]
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "Todo reminder"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"]
        Log.d(TAG, "onMessageReceived: todoId=$todoId, title=$title")
        try {
            NotificationHelper.showReminder(applicationContext, todoId, title, body)
        } catch (e: Throwable) {
            Log.e(TAG, "onMessageReceived: showReminder failed", e)
        }
    }

    private companion object {
        const val TAG = "TodoAi.Fcm"
    }
}
