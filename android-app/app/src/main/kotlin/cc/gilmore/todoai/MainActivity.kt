package cc.gilmore.todoai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import cc.gilmore.todoai.push.NotificationHelper
import cc.gilmore.todoai.ui.navigation.TodoAiNavGraph
import cc.gilmore.todoai.ui.theme.TodoAiTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.d(TAG, "notification permission result: granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: starting")
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        // If TodoAiApplication.onCreate already crashed, `application` isn't
        // actually a TodoAiApplication with a ready `container` by the time
        // any Activity gets created — this cast would throw, but
        // TodoAiApplication's own logging (Logcat tag TodoAi.Application)
        // will already show the real failure before this line ever runs.
        val container = (application as TodoAiApplication).container
        Log.d(TAG, "onCreate: got AppContainer from Application")

        val todoIdFromNotification = intent.getStringExtra(NotificationHelper.EXTRA_TODO_ID)
        Log.d(TAG, "onCreate: todoIdFromNotification=$todoIdFromNotification")

        setContent {
            TodoAiTheme {
                TodoAiNavGraph(container = container, initialTodoIdFromNotification = todoIdFromNotification)
            }
        }
        Log.d(TAG, "onCreate: setContent done")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "requestNotificationPermissionIfNeeded: already granted=$granted")
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TAG = "TodoAi.MainActivity"
    }
}
