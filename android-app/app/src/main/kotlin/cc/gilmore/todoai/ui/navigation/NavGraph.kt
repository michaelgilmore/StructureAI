package cc.gilmore.todoai.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cc.gilmore.todoai.AppContainer
import cc.gilmore.todoai.model.ChatSubjectType
import cc.gilmore.todoai.ui.dialog.DialogScreen
import cc.gilmore.todoai.ui.login.LoginScreen
import cc.gilmore.todoai.ui.planner.PlannerScreen
import cc.gilmore.todoai.ui.pod.PodScreen
import cc.gilmore.todoai.ui.report.ReportScreen
import cc.gilmore.todoai.ui.settings.SettingsScreen
import cc.gilmore.todoai.ui.splash.SplashScreen
import cc.gilmore.todoai.ui.tododetail.TodoDetailScreen
import cc.gilmore.todoai.ui.todolist.TodoListScreen
import cc.gilmore.todoai.util.DateUtils
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate

private object Routes {
    const val LOGIN = "login"
    const val POD = "pod"
    const val TODO_LIST = "todoList"
    const val TODO_DETAIL = "todoDetail"
    const val TODO_ID_ARG = "todoId"
    // Today screen's FAB sets this (to today's date, wire-formatted) so a
    // todo created from there starts out due today; the Todos tab's FAB
    // still navigates without it, leaving the field empty as before.
    const val DEFAULT_DUE_DATE_ARG = "defaultDueDate"
    const val PLANNER = "planner"
    const val DIALOG = "dialog"
    const val SETTINGS = "settings"
    const val REPORT = "report"
    const val SUBJECT_TYPE_ARG = "subjectType"
    const val SUBJECT_ID_ARG = "subjectId"
    const val SUBJECT_LABEL_ARG = "subjectLabel"

    // The three tabs the bottom nav bar switches between. TODO_DETAIL and
    // DIALOG are pushed on top of whichever tab is active instead of being
    // tabs themselves — see TodoAiNavGraph's doc comment.
    val BOTTOM_NAV_ROUTES = listOf(POD, TODO_LIST, PLANNER)
}

/**
 * Percent-encodes a nav-graph string argument. Route matching splits on
 * '&'/'='/'/' , so free text like a todo title (used as [subjectLabel])
 * can't go into a route unescaped — a title containing any of those
 * characters would otherwise silently corrupt the route or crash
 * argument parsing. [subjectId] is encoded too even though today's only
 * two sources (a todo id, or "{scope}#{periodStart}") happen to be
 * URL-safe already — encoding unconditionally means that stays true if
 * either source's format ever changes.
 */
private fun String.navEncode(): String = URLEncoder.encode(this, "UTF-8")
private fun String?.navDecode(): String? = this?.let { URLDecoder.decode(it, "UTF-8") }

private fun dialogRoute(subjectType: ChatSubjectType, subjectId: String?, subjectLabel: String): String {
    val query = buildList {
        add("${Routes.SUBJECT_TYPE_ARG}=${subjectType.wireValue}")
        if (subjectId != null) add("${Routes.SUBJECT_ID_ARG}=${subjectId.navEncode()}")
        add("${Routes.SUBJECT_LABEL_ARG}=${subjectLabel.navEncode()}")
    }.joinToString("&")
    return "${Routes.DIALOG}?$query"
}

@Composable
fun TodoAiNavGraph(container: AppContainer, initialTodoIdFromNotification: String?) {
    val navController = rememberNavController()
    val isSignedIn by container.authRepository.isSignedIn.collectAsState()

    LaunchedEffect(Unit) {
        container.authRepository.checkExistingSession()
    }

    // See TodoRepository.refreshDeviceTokenRegistration's doc comment: the
    // other call site (TodoFcmService.onNewToken) typically fires once, at
    // first install, before the user has signed in — so without this,
    // POST /device/token's first (and often only) real attempt 401s and is
    // never retried. Keyed on isSignedIn rather than Unit so a fresh login
    // after a sign-out (isSignedIn false → true again) retries too; a
    // restored session (null → true) and a fresh login both land here the
    // same way. Cheap/idempotent to repeat — see that function's doc
    // comment.
    LaunchedEffect(isSignedIn) {
        if (isSignedIn == true) {
            container.todoRepository.refreshDeviceTokenRegistration()
        }
    }

    // Splash blocks everything below — Login included — for a fixed 5
    // seconds on every cold start (see ui/splash/SplashScreen.kt). The two
    // LaunchedEffects above are deliberately declared before this gate, not
    // after, so the session check and device-token registration start
    // running immediately in parallel with the splash animation instead of
    // waiting for it — by the time the 5 seconds are up, `isSignedIn` has
    // almost always already resolved, so there's no extra loading spinner
    // stacked after the splash on top of it.
    var showSplash by remember { mutableStateOf(true) }
    if (showSplash) {
        SplashScreen(onFinished = { showSplash = false })
        return
    }

    when (isSignedIn) {
        null -> {
            // Still resolving whether a cached Cognito session exists —
            // avoid flashing the login screen for users who are actually
            // already signed in.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        false -> LoginScreen(authRepository = container.authRepository, credentialStore = container.credentialStore)
        true -> {
            val backStackEntry by navController.currentBackStackEntryAsState()
            // destination.route is the route *template* ("todoDetail?todoId={todoId}"),
            // not the resolved one — substringBefore('?') strips the query
            // template so this compares against the bare Routes constants.
            val currentRoute = backStackEntry?.destination?.route?.substringBefore('?')

            // Hoisted up from inside composable(Routes.TODO_LIST) — that
            // used to be safe only because TODO_LIST was also the
            // NavHost's startDestination, so it was guaranteed to compose
            // on every cold start. Now that Routes.POD is the start
            // destination (see NavHost below), a deep link from tapping a
            // reminder notification needs to fire regardless of which tab
            // happens to be first, so this lives here instead — keyed the
            // same way, still a one-shot per app launch.
            LaunchedEffect(initialTodoIdFromNotification) {
                if (initialTodoIdFromNotification != null) {
                    navController.navigate("${Routes.TODO_DETAIL}?${Routes.TODO_ID_ARG}=$initialTodoIdFromNotification")
                }
            }

            Scaffold(
                bottomBar = {
                    if (currentRoute in Routes.BOTTOM_NAV_ROUTES) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentRoute == Routes.POD,
                                onClick = {
                                    navController.navigate(Routes.POD) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Filled.Today, contentDescription = "Today") },
                                label = { Text("Today") },
                            )
                            NavigationBarItem(
                                selected = currentRoute == Routes.TODO_LIST,
                                onClick = {
                                    navController.navigate(Routes.TODO_LIST) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Filled.Checklist, contentDescription = "Todos") },
                                label = { Text("Todos") },
                            )
                            NavigationBarItem(
                                selected = currentRoute == Routes.PLANNER,
                                onClick = {
                                    navController.navigate(Routes.PLANNER) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = "Plan") },
                                label = { Text("Plan") },
                            )
                        }
                    }
                },
            ) { outerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = Routes.POD,
                    modifier = Modifier.fillMaxSize().padding(outerPadding),
                ) {
                    composable(Routes.TODO_LIST) {
                        TodoListScreen(
                            repository = container.todoRepository,
                            authRepository = container.authRepository,
                            connectivityObserver = container.connectivityObserver,
                            onAddTodo = { navController.navigate(Routes.TODO_DETAIL) },
                            onOpenTodo = { id -> navController.navigate("${Routes.TODO_DETAIL}?${Routes.TODO_ID_ARG}=$id") },
                            onOpenChat = { navController.navigate(dialogRoute(ChatSubjectType.GENERAL, null, "General")) },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onOpenReport = { navController.navigate(Routes.REPORT) },
                        )
                    }
                    composable(Routes.REPORT) {
                        ReportScreen(
                            repository = container.todoRepository,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.POD) {
                        PodScreen(
                            repository = container.todoRepository,
                            connectivityObserver = container.connectivityObserver,
                            voiceLaunchPreferenceStore = container.voiceLaunchPreferenceStore,
                            onOpenTodo = { id -> navController.navigate("${Routes.TODO_DETAIL}?${Routes.TODO_ID_ARG}=$id") },
                            onAddTodo = {
                                val today = DateUtils.formatDueDate(LocalDate.now())
                                navController.navigate("${Routes.TODO_DETAIL}?${Routes.DEFAULT_DUE_DATE_ARG}=$today")
                            },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            authRepository = container.authRepository,
                            aiModeStore = container.aiModeStore,
                            voiceLaunchPreferenceStore = container.voiceLaunchPreferenceStore,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        // Both args are independent and optional, same
                        // pattern as the DIALOG route below — a caller can
                        // pass either, both, or neither. TODO_ID_ARG alone
                        // (opening an existing todo) leaves DEFAULT_DUE_DATE_ARG
                        // at its null default, and vice versa for the Today
                        // screen's FAB.
                        route = "${Routes.TODO_DETAIL}?${Routes.TODO_ID_ARG}={${Routes.TODO_ID_ARG}}" +
                            "&${Routes.DEFAULT_DUE_DATE_ARG}={${Routes.DEFAULT_DUE_DATE_ARG}}",
                        arguments = listOf(
                            navArgument(Routes.TODO_ID_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument(Routes.DEFAULT_DUE_DATE_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { backStackEntry ->
                        val todoId = backStackEntry.arguments?.getString(Routes.TODO_ID_ARG)
                        val defaultDueDate = DateUtils.parseDueDate(backStackEntry.arguments?.getString(Routes.DEFAULT_DUE_DATE_ARG))
                        TodoDetailScreen(
                            repository = container.todoRepository,
                            todoId = todoId,
                            onDone = { navController.popBackStack() },
                            onAskAi = { id, title -> navController.navigate(dialogRoute(ChatSubjectType.TODO, id, title)) },
                            defaultDueDate = defaultDueDate,
                        )
                    }
                    composable(Routes.PLANNER) {
                        PlannerScreen(
                            onStartPlanning = { navController.navigate(dialogRoute(ChatSubjectType.PLANNING, null, "Planning")) },
                        )
                    }
                    composable(
                        route = "${Routes.DIALOG}?${Routes.SUBJECT_TYPE_ARG}={${Routes.SUBJECT_TYPE_ARG}}" +
                            "&${Routes.SUBJECT_ID_ARG}={${Routes.SUBJECT_ID_ARG}}" +
                            "&${Routes.SUBJECT_LABEL_ARG}={${Routes.SUBJECT_LABEL_ARG}}",
                        arguments = listOf(
                            navArgument(Routes.SUBJECT_TYPE_ARG) { type = NavType.StringType; defaultValue = ChatSubjectType.GENERAL.wireValue },
                            navArgument(Routes.SUBJECT_ID_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument(Routes.SUBJECT_LABEL_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { backStackEntry ->
                        val args = backStackEntry.arguments
                        val subjectType = ChatSubjectType.fromWire(args?.getString(Routes.SUBJECT_TYPE_ARG))
                        val subjectId = args?.getString(Routes.SUBJECT_ID_ARG).navDecode()
                        val subjectLabel = args?.getString(Routes.SUBJECT_LABEL_ARG).navDecode() ?: "General"
                        DialogScreen(
                            aiRepository = container.aiRepository,
                            todoRepository = container.todoRepository,
                            subjectType = subjectType,
                            subjectId = subjectId,
                            subjectLabel = subjectLabel,
                            onBack = { navController.popBackStack() },
                            onOpenTodo = { id -> navController.navigate("${Routes.TODO_DETAIL}?${Routes.TODO_ID_ARG}=$id") },
                        )
                    }
                }
            }
        }
    }
}
