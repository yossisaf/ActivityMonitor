package com.example.activitymonitor

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.activitymonitor.db.ActivityEventEntity
import com.example.activitymonitor.db.BatterySampleEntity
import com.example.activitymonitor.repository.AppIconCache
import com.example.activitymonitor.repository.CryptoManager
import com.example.activitymonitor.repository.MonitorRepository
import com.example.activitymonitor.repository.PasswordManager
import com.example.activitymonitor.monitoring.ScreenCaptureService
import com.example.activitymonitor.ui.RecordingAdapter
import com.example.activitymonitor.repository.UsageStatsRepository
import com.example.activitymonitor.ui.BarChartView
import com.example.activitymonitor.ui.EventAdapter
import com.example.activitymonitor.ui.EventTranslator
import com.example.activitymonitor.ui.Formatters
import com.example.activitymonitor.ui.MainViewModel
import com.example.activitymonitor.ui.RecentAppAdapter
import com.example.activitymonitor.ui.RecordingAppAdapter
import com.example.activitymonitor.ui.RecordingAppRow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var repository: MonitorRepository
    private lateinit var usageStats: UsageStatsRepository
    private lateinit var iconCache: AppIconCache
    private lateinit var rootContent: LinearLayout
    private lateinit var title: TextView
    private lateinit var eventAdapter: EventAdapter
    private lateinit var searchAdapter: EventAdapter
    private lateinit var appAdapter: RecordingAppAdapter
    private var allRecordingApps: List<RecordingAppRow> = emptyList()
    private lateinit var recentAppAdapter: RecentAppAdapter
    private lateinit var passwordManager: PasswordManager
    private lateinit var toolbarBack: ImageButton
    private var mainUiInitialized = false
    private var permissionGateShowing = false
    private val navStack = ArrayDeque<String>()
    private var searchOffset = 0
    private var searchCustomStart: Long? = null
    private var searchCustomEnd: Long? = null
    private var searchPackageFilter: String? = null
    private var searchEventTypeFilter: Int? = null
    private var pendingTileStart = false
    private var pendingTileStop = false
    private var tileStopGatePassed = false
    private var pendingContinuousStart = false
    private val screenCaptureRequestCode = 7401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTileStart = intent?.action == ACTION_START_FROM_TILE
        pendingTileStop = intent?.action == ACTION_STOP_FROM_TILE
        setContentView(R.layout.activity_main)
        repository = (application as MonitorApplication).repository
        usageStats = UsageStatsRepository(this)
        iconCache = AppIconCache(this)
        passwordManager = PasswordManager(this)
        toolbarBack = findViewById(R.id.toolbarBack)
        toolbarBack.setOnClickListener { navigateBack() }
        viewModel = ViewModelProvider(this, MainViewModel.Factory(repository))[MainViewModel::class.java]
        rootContent = findViewById(R.id.screenContainer)
        title = findViewById(R.id.toolbarTitle)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        })
        showPasswordGate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_START_FROM_TILE -> {
                pendingTileStart = true
                continuePendingTileStart()
            }
            ACTION_STOP_FROM_TILE -> {
                pendingTileStop = true
                continuePendingTileStop()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!mainUiInitialized) return
        if (enforceRequiredPermissions()) return

        if (navStack.lastOrNull() == "dashboard") {
            refreshDashboard()
            updateDashboardRecordingStatus()
            updatePermissionBanner()
            updateBackgroundWarning()
        }
        when (navStack.lastOrNull()) {
            "permissions" -> updatePermissionScreen()
            "dashboard" -> updateBackgroundWarning()
        }
        updateBackgroundPermissionStatus()
    }

    private fun showPasswordGate() {
        mainUiInitialized = false
        rootContent.removeAllViews()
        bottomNavVisibility(false)
        toolbarBack.visibility = View.GONE
        title.text = "ניטור המכשיר"
        layoutInflater.inflate(R.layout.screen_lock, rootContent, true)

        val titleView = findViewById<TextView>(R.id.lockTitle)
        val subtitle = findViewById<TextView>(R.id.lockSubtitle)
        val input = findViewById<EditText>(R.id.passwordInput)
        val confirm = findViewById<EditText>(R.id.passwordConfirm)
        val error = findViewById<TextView>(R.id.passwordError)
        val action = findViewById<Button>(R.id.passwordAction)

        if (!passwordManager.hasPassword()) {
            titleView.text = "הגדרת סיסמה"
            subtitle.text = "בחר סיסמה כדי להגן על נתוני הניטור."
            confirm.visibility = View.VISIBLE
            action.text = "שמירת סיסמה"
            action.setOnClickListener {
                val first = input.text.toString()
                val second = confirm.text.toString()
                when {
                    first.length < 4 -> showPasswordError(error, "הסיסמה חייבת להכיל לפחות 4 תווים.")
                    first != second -> showPasswordError(error, "הסיסמאות אינן זהות.")
                    else -> {
                        passwordManager.setPassword(first)
                        tileStopGatePassed = pendingTileStop
                        initMainUi()
                    }
                }
            }
        } else {
            titleView.text = "כניסה"
            subtitle.text = "הזן את הסיסמה כדי להמשיך."
            confirm.visibility = View.GONE
            action.text = "כניסה"
            action.setOnClickListener {
                if (passwordManager.verify(input.text.toString())) {
                    tileStopGatePassed = pendingTileStop
                    initMainUi()
                } else showPasswordError(error, "הסיסמה שגויה.")
            }
        }
    }

    private fun showPasswordError(error: TextView, message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun bottomNavVisibility(visible: Boolean) {
        findViewById<LinearLayout>(R.id.bottomNav).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun initMainUi() {
        mainUiInitialized = true
        bottomNavVisibility(true)
        setupNav()
        setupAdapters()
        setupCollectors()

        // A stop request from the Quick Settings tile must always remain available,
        // even if another permission was revoked since the last launch.
        if (pendingTileStop) {
            navStack.clear()
            render("dashboard")
            continuePendingTileStop()
            // Allow the emergency stop action, then enforce any other missing permissions.
            if (enforceRequiredPermissions()) return
            return
        }

        if (enforceRequiredPermissions()) return
        render("dashboard")
        continuePendingTileStart()
        continuePendingTileStop()
    }

    private fun continuePendingTileStart() {
        if (!pendingTileStart || !mainUiInitialized || permissionGateShowing) return
        pendingTileStart = false
        val enabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("monitoring_enabled", true)
        if (!enabled || ScreenCaptureService.isRunning()) return
        val continuous = getSharedPreferences("screen_recording", MODE_PRIVATE)
            .getBoolean("continuous_recording", false)
        if (continuous || getScreenRecordingPackages().isNotEmpty()) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, "הניטור הופעל. בחר אפליקציות לתיעוד או הפעל הסרטה רציפה.", Toast.LENGTH_LONG).show()
        }
    }

    private fun continuePendingTileStop() {
        if (!pendingTileStop || !mainUiInitialized) return
        pendingTileStop = false

        if (tileStopGatePassed) {
            tileStopGatePassed = false
            setMonitoringEnabled(false)
            Toast.makeText(this, "הניטור נעצר לאחר אימות סיסמה", Toast.LENGTH_SHORT).show()
            return
        }

        val passwordInput = EditText(this).apply {
            hint = "סיסמה"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("אימות לפני עצירת הניטור")
            .setMessage("כדי למנוע כיבוי בטעות או בידי אדם אחר, יש להזין את סיסמת האפליקציה.")
            .setView(passwordInput)
            .setNegativeButton("ביטול") { _, _ -> MonitorTileService.refreshTile(this) }
            .setPositiveButton("אימות", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (passwordManager.verify(passwordInput.text.toString())) {
                    dialog.dismiss()
                    setMonitoringEnabled(false)
                    Toast.makeText(this, "הניטור נעצר", Toast.LENGTH_SHORT).show()
                } else {
                    passwordInput.error = "הסיסמה שגויה"
                    MonitorTileService.refreshTile(this)
                }
            }
        }
        dialog.setOnCancelListener { MonitorTileService.refreshTile(this) }
        dialog.show()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ignoresBatteryOptimizations(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } else true

    private fun updateBackgroundWarning() {
        val warning = rootContent.findViewById<TextView>(R.id.backgroundWarning) ?: return
        val needsNotifications = !hasNotificationPermission()
        val needsBattery = !ignoresBatteryOptimizations()
        warning.visibility = if (needsNotifications || needsBattery) View.VISIBLE else View.GONE
        warning.text = when {
            needsNotifications && needsBattery -> "נדרשות הגדרות להתראות וליציבות ברקע — הקש כאן"
            needsNotifications -> "התראות ניטור חסומות — הקש כאן"
            needsBattery -> "חיסכון בסוללה עלול לעצור ניטור ברקע — הקש כאן"
            else -> ""
        }
        warning.setOnClickListener { render("permissions") }
    }

    private fun updateBackgroundPermissionStatus() {
        val status = rootContent.findViewById<TextView>(R.id.backgroundPermissionStatus) ?: return
        val details = rootContent.findViewById<TextView>(R.id.backgroundPermissionDetails) ?: return
        val notifications = hasNotificationPermission()
        val battery = ignoresBatteryOptimizations()
        status.text = when {
            notifications && battery -> "מצב רקע: מוגדר"
            !notifications && !battery -> "נדרשות שתי הגדרות"
            !notifications -> "נדרשת הרשאת התראות"
            else -> "נדרש חריג חיסכון בסוללה"
        }
        details.text = when {
            notifications && battery -> "התראות זמינות והמערכת הוגדרה להקל על פעילות ממושכת ברקע. Android עדיין עשויה לעצור שירותים במצבים מסוימים."
            !notifications && !battery -> "אשר התראות כדי לראות שההסרטה פועלת, ואפשר לאפליקציה לפעול מחוץ לאופטימיזציית הסוללה. אי אפשר להבטיח פעולה רציפה בכל מצב Android."
            !notifications -> "בלי הרשאת התראות, ייתכן שלא תראה את מצב ההסרטה בהתראות. אפשר לאשר אותה בהגדרות."
            else -> "חיסכון בסוללה עלול להפסיק פעילות ממושכת. אפשר להחריג את האפליקציה באישור מערכת."
        }
        updateBackgroundWarning()
    }

    private fun requiredPermissionCountMissing(): Int {
        var missing = 0
        if (!hasNotificationPermission()) missing++
        if (!accessibilityEnabled()) missing++
        if (!usageStats.hasUsageAccess()) missing++
        if (!ignoresBatteryOptimizations()) missing++
        return missing
    }

    /**
     * These settings are checked on every launch/resume. When any is missing,
     * the rest of the app stays behind a focused setup screen until the user
     * returns from Android Settings with all four settings enabled.
     */
    private fun enforceRequiredPermissions(): Boolean {
        val missing = requiredPermissionCountMissing()
        if (missing == 0) {
            if (permissionGateShowing) {
                permissionGateShowing = false
                bottomNavVisibility(true)
                navStack.clear()
                render("dashboard")
                continuePendingTileStart()
                continuePendingTileStop()
            }
            return false
        }

        if (!permissionGateShowing) {
            permissionGateShowing = true
            navStack.clear()
            bottomNavVisibility(false)
            toolbarBack.visibility = View.GONE
            rootContent.removeAllViews()
            layoutInflater.inflate(R.layout.screen_required_permissions, rootContent, true)
            title.text = "השלמת הרשאות"
            rootContent.findViewById<Button>(R.id.requiredNotificationsAction)
                .setOnClickListener { requestOrOpenNotificationSettings() }
            rootContent.findViewById<Button>(R.id.requiredAccessibilityAction)
                .setOnClickListener { openRequiredSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "לא ניתן לפתוח את הגדרות הנגישות") }
            rootContent.findViewById<Button>(R.id.requiredUsageAction)
                .setOnClickListener { openRequiredSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), "לא ניתן לפתוח את הגדרות נתוני השימוש") }
            rootContent.findViewById<Button>(R.id.requiredBatteryAction)
                .setOnClickListener { openBatteryOptimizationSettings() }
            rootContent.findViewById<Button>(R.id.requiredEmergencyStop)
                .setOnClickListener {
                    stopScreenCaptureFromHome()
                    rootContent.findViewById<Button>(R.id.requiredEmergencyStop)?.visibility = View.GONE
                }
        }

        updateRequiredPermissionsScreen()
        return true
    }

    private fun updateRequiredPermissionsScreen() {
        if (!permissionGateShowing) return
        val notificationReady = hasNotificationPermission()
        val accessibilityReady = accessibilityEnabled()
        val usageReady = usageStats.hasUsageAccess()
        val batteryReady = ignoresBatteryOptimizations()

        setPermissionStatus(R.id.requiredNotificationsStatus, notificationReady,
            if (Build.VERSION.SDK_INT < 33) "לא נדרש בגרסת Android זו" else if (notificationReady) "הושלם" else "נדרש")
        setPermissionStatus(R.id.requiredAccessibilityStatus, accessibilityReady,
            if (accessibilityReady) "הושלם" else "נדרש")
        setPermissionStatus(R.id.requiredUsageStatus, usageReady,
            if (usageReady) "הושלם" else "נדרש")
        setPermissionStatus(R.id.requiredBatteryStatus, batteryReady,
            if (batteryReady) "הוגדר" else "נדרש")

        rootContent.findViewById<Button>(R.id.requiredNotificationsAction).visibility =
            if (notificationReady) View.GONE else View.VISIBLE
        rootContent.findViewById<Button>(R.id.requiredAccessibilityAction).visibility =
            if (accessibilityReady) View.GONE else View.VISIBLE
        rootContent.findViewById<Button>(R.id.requiredUsageAction).visibility =
            if (usageReady) View.GONE else View.VISIBLE
        rootContent.findViewById<Button>(R.id.requiredBatteryAction).visibility =
            if (batteryReady) View.GONE else View.VISIBLE

        val done = listOf(notificationReady, accessibilityReady, usageReady, batteryReady).count { it }
        val continuousRequested = getSharedPreferences("screen_recording", MODE_PRIVATE)
            .getBoolean("continuous_recording", false)
        rootContent.findViewById<Button>(R.id.requiredEmergencyStop)?.visibility =
            if (ScreenCaptureService.isRunning() || continuousRequested) View.VISIBLE else View.GONE
        rootContent.findViewById<TextView>(R.id.requiredPermissionsProgress).text =
            "$done מתוך 4 הגדרות הושלמו"
        rootContent.findViewById<TextView>(R.id.requiredPermissionsHint).text =
            if (done == 4) "כל ההגדרות הושלמו. האפליקציה נפתחת…" else
                "יש להשלים את ההגדרות החסרות במסכי Android ולחזור לכאן. האפליקציה תמשיך אוטומטית."
    }

    private fun setPermissionStatus(viewId: Int, ready: Boolean, label: String) {
        rootContent.findViewById<TextView>(viewId)?.apply {
            text = label
            setTextColor(getColor(if (ready) R.color.success else R.color.error))
        }
    }

    private fun requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission()) {
            updateRequiredPermissionsScreen()
            return
        }
        val prefs = getSharedPreferences("required_permissions", MODE_PRIVATE)
        if (!prefs.getBoolean("notification_request_attempted", false)) {
            prefs.edit().putBoolean("notification_request_attempted", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), BACKGROUND_NOTIFICATION_REQUEST)
            return
        }
        val appNotifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        openRequiredSettings(appNotifications, "לא ניתן לפתוח את הגדרות ההתראות")
    }

    private fun openRequiredSettings(intent: Intent, errorMessage: String) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun openBackgroundSetup() {
        when {
            !hasNotificationPermission() -> requestOrOpenNotificationSettings()
            !accessibilityEnabled() ->
                openRequiredSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "לא ניתן לפתוח את הגדרות הנגישות")
            !usageStats.hasUsageAccess() ->
                openRequiredSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), "לא ניתן לפתוח את הגדרות נתוני השימוש")
            !ignoresBatteryOptimizations() -> openBatteryOptimizationSettings()
            else -> AlertDialog.Builder(this)
                .setTitle("הגדרות הרקע הושלמו")
                .setMessage("כל ההרשאות הנדרשות פעילות. עדיין ייתכן ש־Android או יצרן הטלפון יגבילו פעילות ברקע במצבים מסוימים.")
                .setPositiveButton("הבנתי", null)
                .show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BACKGROUND_NOTIFICATION_REQUEST) {
            updateBackgroundPermissionStatus()
            updateBackgroundWarning()
            if (mainUiInitialized) enforceRequiredPermissions()
        }
    }

    private fun setupNav() {
        mapOf(
            R.id.navDashboard to "dashboard",
            R.id.navStats to "recordings",
            R.id.navPermissions to "permissions"
        ).forEach { (id, key) ->
            findViewById<Button>(id).setOnClickListener { selectRootTab(key) }
        }
    }

    private fun selectRootTab(key: String) {
        navStack.clear()
        navStack.addLast(key)
        render(key)
    }

    private fun updateNavSelection(key: String) {
        findViewById<Button>(R.id.navDashboard).isSelected = key == "dashboard"
        findViewById<Button>(R.id.navStats).isSelected = key == "recordings"
        findViewById<Button>(R.id.navPermissions).isSelected = key == "permissions"
    }

    private fun setupAdapters() {
        eventAdapter = EventAdapter(iconCache) { openEvent(it) }
        searchAdapter = EventAdapter(iconCache) { openEvent(it) }
        recentAppAdapter = RecentAppAdapter(iconCache) { session -> openAppTimeline(session.packageName, session.appName) }
        appAdapter = RecordingAppAdapter(iconCache) { pkg, enabled ->
            setScreenRecordingForApp(pkg, enabled)
            updateAppsSelectionSummary()
        }
    }

    private fun setupCollectors() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.events.collect { eventAdapter.submitList(it) } }
                launch {
                    viewModel.searchResults.collect {
                        searchAdapter.submitList(it)
                        rootContent.findViewById<View>(R.id.searchEmpty)?.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                        rootContent.findViewById<TextView>(R.id.searchResultCount)?.text = when (it.size) {
                            0 -> "אין תוצאות"
                            1 -> "תוצאה אחת"
                            else -> "${it.size} תוצאות"
                        }
                    }
                }
                launch { viewModel.battery.collect { updateBatteryScreenIfVisible(it) } }
            }
        }
    }

    private fun render(key: String) {
        if (navStack.lastOrNull() != key) navStack.addLast(key)
        toolbarBack.visibility = if (navStack.size > 1) View.VISIBLE else View.GONE
        updateNavSelection(key)
        when (key) {
            "dashboard" -> showDashboard()
            "apps" -> showApps()
            "stats" -> showStats()
            "search" -> showSearch()
            "recordings" -> showRecordings()
            "permissions" -> showPermissions()
            else -> showDashboard()
        }
    }

    private fun inflateScreen(layout: Int, titleText: String) {
        rootContent.removeAllViews()
        layoutInflater.inflate(layout, rootContent, true)
        title.text = titleText
    }

    private fun navigateBack() {
        if (navStack.size > 1) {
            navStack.removeLast()
            render(navStack.last())
        } else {
            finish()
        }
    }

    private fun showDashboard() {
        inflateScreen(R.layout.screen_dashboard, "הסרטה")
        findViewById<Button>(R.id.dashboardPermissions).setOnClickListener { render("permissions") }
        findViewById<Button>(R.id.dashboardSearch).setOnClickListener { render("apps") }
        findViewById<Button>(R.id.dashboardContinuous).setOnClickListener { startContinuousRecording() }
        findViewById<Button>(R.id.dashboardStop).setOnClickListener { stopScreenCaptureFromHome() }
        findViewById<Button>(R.id.dashboardRecordings).setOnClickListener { render("recordings") }
        refreshDashboard()
        updateDashboardRecordingStatus()
        updateBackgroundWarning()
    }

    private fun recordingRoot(): File =
        File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "ActivityMonitor")

    private fun recordingFiles(): List<File> {
        val activePath = ScreenCaptureService.currentRecordingPath()
        return recordingRoot().listFiles { file ->
            file.isFile &&
                file.extension.equals("mp4", ignoreCase = true) &&
                file.absolutePath != activePath
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun updateDashboardRecordingStatus() {
        val status = rootContent.findViewById<TextView>(R.id.dashboardRecordingStatus) ?: return
        val active = ScreenCaptureService.currentRecordingPackage()
        val selectedCount = getScreenRecordingPackages().size
        val screenPrefs = getSharedPreferences("screen_recording", MODE_PRIVATE)
        val continuousRequested = screenPrefs.getBoolean("continuous_recording", false)
        val running = ScreenCaptureService.isRunning()
        val stopButton = rootContent.findViewById<Button>(R.id.dashboardStop)
        stopButton?.visibility = if (running || continuousRequested) View.VISIBLE else View.GONE
        val label = active?.let {
            runCatching {
                val info = packageManager.getApplicationInfo(it, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }
        val monitorEnabled = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("monitoring_enabled", true)
        status.text = when {
            !monitorEnabled -> "ההסרטה מושהית"
            running && ScreenCaptureService.isContinuousMode() ->
                "● ההסרטה הכללית פעילה — כל מה שמופיע במסך מוקלט עד לעצירה"
            running && !label.isNullOrBlank() ->
                "● מוקלטת עכשיו האפליקציה: $label"
            running ->
                "מצב לפי אפליקציות פעיל. ההסרטה תתחיל כשתפתח אפליקציה שסומנה"
            continuousRequested ->
                "ההסרטה הכללית מוגדרת אך אינה פעילה כרגע. לחץ כאן להפעלה מחדש"
            selectedCount > 0 ->
                "נבחרו $selectedCount אפליקציות, אך ההסרטה אינה פעילה. לחץ על ״בחירת אפליקציות״ כדי להפעיל אותה"
            else ->
                "ההסרטה כבויה. בחר אחת משתי האפשרויות למעלה"
        }
        status.setOnClickListener(
            if (continuousRequested && !running) View.OnClickListener { startContinuousRecording() } else null
        )
    }

    private fun refreshDashboard() {
        if (navStack.lastOrNull() != "dashboard") return
        val files = recordingFiles()
        rootContent.findViewById<TextView>(R.id.dashboardVideoCount)?.text = when (files.size) {
            0 -> "אין סרטונים שמורים עדיין"
            1 -> "סרטון שמור אחד"
            else -> "${files.size} סרטונים שמורים"
        }
        updateDashboardRecordingStatus()
    }

    private fun updatePermissionBanner() {
        updateBackgroundWarning()
    }

    private fun showApps() {
        inflateScreen(R.layout.screen_apps, "בחירת אפליקציות")
        findViewById<RecyclerView>(R.id.appsRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = appAdapter
        }
        findViewById<Button>(R.id.openSearch).setOnClickListener { startSelectedAppsRecording() }
        findViewById<EditText>(R.id.appsSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderRecordingApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        loadApps()
    }

    private fun loadApps() {
        val selectedBefore = getScreenRecordingPackages()
        val apps = runCatching {
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != packageName }
                .map { info ->
                    RecordingAppRow(
                        packageName = info.packageName,
                        name = runCatching { packageManager.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName).ifBlank { info.packageName }
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                .toList()
        }.getOrElse {
            Toast.makeText(this, "לא ניתן לטעון את רשימת האפליקציות", Toast.LENGTH_LONG).show()
            emptyList()
        }
        allRecordingApps = apps
        val installed = apps.mapTo(mutableSetOf()) { it.packageName }
        val validSelection = selectedBefore.filterTo(mutableSetOf()) { it in installed }
        if (validSelection != selectedBefore) {
            getSharedPreferences("screen_recording", MODE_PRIVATE).edit()
                .putStringSet(ScreenCaptureService.KEY_PACKAGES, validSelection.toSet()).apply()
        }
        val query = rootContent.findViewById<EditText>(R.id.appsSearch)?.text?.toString().orEmpty()
        renderRecordingApps(query)
    }

    private fun renderRecordingApps(query: String) {
        val normalized = query.trim().lowercase(java.util.Locale.ROOT)
        val visible = if (normalized.isEmpty()) allRecordingApps else allRecordingApps.filter {
            it.name.lowercase(java.util.Locale.ROOT).contains(normalized) ||
                it.packageName.lowercase(java.util.Locale.ROOT).contains(normalized)
        }
        appAdapter.submitList(visible, getScreenRecordingPackages())
        updateAppsSelectionSummary()
    }

    private fun updateAppsSelectionSummary() {
        if (navStack.lastOrNull() != "apps") return
        rootContent.findViewById<TextView>(R.id.appsCount)?.text =
            "${allRecordingApps.size} אפליקציות מותקנות במכשיר"
        val selected = getScreenRecordingPackages().size
        rootContent.findViewById<TextView>(R.id.selectedAppsCount)?.text =
            if (selected == 0) "לא נבחרו אפליקציות" else "נבחרו $selected אפליקציות"
        rootContent.findViewById<Button>(R.id.openSearch)?.apply {
            isEnabled = selected > 0
            text = if (selected == 0) "בחר אפליקציות תחילה" else "הפעלת מצב לפי אפליקציות ($selected)"
        }
    }

    private fun showStats() {
        inflateScreen(R.layout.screen_stats, "סטטיסטיקות")
        findViewById<Button>(R.id.openBattery).setOnClickListener { showBattery() }
        lifecycleScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        val start = Formatters.dayStart(-6); val end = System.currentTimeMillis() + 1
        val total = if (usageStats.hasUsageAccess()) usageStats.queryAggregated(start, end).values.sum() else repository.totalDuration(start, end)
        val openings = repository.sessionCount(start, end); val events = repository.eventCount(start, end)
        val top = repository.topAppsByEvents(start, end, 5); val counts = repository.eventCounts(start, end)
        val rangeEvents = repository.search("", start, end, limit = 10000)
        val hourly = MutableList(24) { 0f }; val days = mutableMapOf<String, Int>()
        rangeEvents.forEach {
            val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            hourly[cal.get(Calendar.HOUR_OF_DAY)]++
            val d = Formatters.shortDate(it.timestamp); days[d] = (days[d] ?: 0) + 1
        }
        val busiestDay = days.maxByOrNull { it.value }?.let { "${it.key} (${it.value} אירועים)" } ?: "אין נתונים"
        if (navStack.lastOrNull() != "stats") return
        findViewById<TextView>(R.id.statsSummary).text = "זמן שימוש כולל: ${Formatters.duration(total)}\nפתיחות: $openings\nאירועים: $events\nהאפליקציה הבולטת: ${top.firstOrNull()?.appName ?: "אין נתונים"}\nיום עם הפעילות הרבה ביותר: $busiestDay"
        findViewById<TextView>(R.id.statsEventTypes).text = counts.take(6).joinToString("\n") { "${EventTranslator.description(it.eventType)} — ${it.count}" }.ifBlank { "אין נתונים עדיין" }
        findViewById<TextView>(R.id.statsApps).text = top.joinToString("\n") { "${it.appName} — ${it.count} אירועים" }.ifBlank { "אין נתונים עדיין" }
        findViewById<BarChartView>(R.id.statsChart).apply { values = hourly; labels = List(24) { i -> if (i % 4 == 0) i.toString().padStart(2, '0') else "" } }
    }

    private fun showBattery() {
        navStack.addLast("battery")
        inflateScreen(R.layout.screen_battery, "סוללה")
        updateBatteryScreenIfVisible(viewModel.battery.value)
    }

    private fun showSearch() {
        inflateScreen(R.layout.screen_search, "חיפוש")
        findViewById<RecyclerView>(R.id.searchRecycler).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = searchAdapter }
        val input = findViewById<EditText>(R.id.searchInput)
        val range = findViewById<TextView>(R.id.searchRange)
        val resultCount = findViewById<TextView>(R.id.searchResultCount)
        searchOffset = 0; searchCustomStart = null; searchCustomEnd = null; searchPackageFilter = null; searchEventTypeFilter = null
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(input, range)
                true
            } else false
        }
        findViewById<Button>(R.id.searchClear).setOnClickListener {
            input.setText("")
            input.requestFocus()
            runSearch(input, range)
        }
        findViewById<Button>(R.id.searchToday).setOnClickListener { clearCustomRange(); searchOffset = 0; runSearch(input, range) }
        findViewById<Button>(R.id.searchYesterday).setOnClickListener { clearCustomRange(); searchOffset = -1; runSearch(input, range) }
        findViewById<Button>(R.id.searchWeek).setOnClickListener { clearCustomRange(); searchOffset = -6; runSearch(input, range) }
        findViewById<Button>(R.id.searchMonth).setOnClickListener { clearCustomRange(); searchOffset = -29; runSearch(input, range) }
        findViewById<Button>(R.id.searchFrom).setOnClickListener { pickSearchDate(true, range) }
        findViewById<Button>(R.id.searchTo).setOnClickListener { pickSearchDate(false, range) }
        findViewById<Button>(R.id.searchAppFilter).setOnClickListener { chooseSearchApp(input, range) }
        findViewById<Button>(R.id.searchTypeFilter).setOnClickListener { chooseSearchType(input, range) }
        findViewById<Button>(R.id.searchGo).setOnClickListener { runSearch(input, range) }
        runSearch(input, range)
    }

    private fun clearCustomRange() {
        searchCustomStart = null; searchCustomEnd = null
    }

    private fun pickSearchDate(from: Boolean, range: TextView) {
        val base = Calendar.getInstance().apply { timeInMillis = (if (from) searchCustomStart else searchCustomEnd) ?: System.currentTimeMillis() }
        android.app.DatePickerDialog(this, { _, y, m, d ->
            val c = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            if (from) searchCustomStart = c.timeInMillis else searchCustomEnd = c.timeInMillis + 24L * 60L * 60L * 1000L
            searchOffset = 0; range.text = "טווח מותאם"
        }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun chooseSearchApp(input: EditText, range: TextView) {
        lifecycleScope.launch {
            val apps = repository.topAppsByEvents(Formatters.dayStart(-90), System.currentTimeMillis() + 1, 60)
            val labels = arrayOf("כל האפליקציות") + apps.map { it.appName }.distinct().toTypedArray()
            AlertDialog.Builder(this@MainActivity).setTitle("סינון לפי אפליקציה").setItems(labels) { _, which ->
                searchPackageFilter = if (which == 0) null else apps.firstOrNull { it.appName == labels[which] }?.packageName
                runSearch(input, range)
            }.show()
        }
    }

    private fun chooseSearchType(input: EditText, range: TextView) {
        val types = listOf(null, android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        val labels = listOf("כל האירועים המרכזיים", "פתיחת אפליקציה או מעבר למסך")
        AlertDialog.Builder(this).setTitle("סינון לפי סוג פעולה").setItems(labels.toTypedArray()) { _, which ->
            searchEventTypeFilter = types[which]; runSearch(input, range)
        }.show()
    }

    private fun runSearch(input: EditText, range: TextView) {
        val now = System.currentTimeMillis() + 1
        val start = searchCustomStart ?: Formatters.dayStart(searchOffset)
        val end = searchCustomEnd ?: if (searchOffset == -1) Formatters.dayStart(0) else now
        range.text = when {
            searchCustomStart != null || searchCustomEnd != null -> "טווח מותאם"
            searchOffset == 0 -> "היום"; searchOffset == -1 -> "אתמול"; searchOffset == -6 -> "7 ימים אחרונים"; searchOffset == -29 -> "30 ימים אחרונים"; else -> "טווח"
        }
        viewModel.search(input.text.toString(), start, end, searchPackageFilter, searchEventTypeFilter)
    }

    private fun showPermissions() {
        inflateScreen(R.layout.screen_permissions, "הגדרות")
        updatePermissionScreen()
        findViewById<Button>(R.id.openAccessibility).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        findViewById<Button>(R.id.openUsage).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        findViewById<Button>(R.id.changePassword).setOnClickListener { showChangePasswordDialog() }
        findViewById<Button>(R.id.openRecordings).setOnClickListener { render("recordings") }
        findViewById<Button>(R.id.backgroundSetup).setOnClickListener { openBackgroundSetup() }
        val advancedToggle = findViewById<Button>(R.id.advancedToggle)
        val advanced = findViewById<View>(R.id.advancedContainer)
        advancedToggle.setOnClickListener {
            val open = advanced.visibility != View.VISIBLE
            advanced.visibility = if (open) View.VISIBLE else View.GONE
            advancedToggle.text = if (open) "אפשרויות מתקדמות  ↓" else "אפשרויות מתקדמות  ›"
        }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val retention = findViewById<EditText>(R.id.retentionDays)
        retention.setText(prefs.getInt("retention_days", 30).toString())
        findViewById<Button>(R.id.saveRetention).setOnClickListener {
            val days = retention.text.toString().toIntOrNull()?.coerceIn(1, 3650) ?: 30
            prefs.edit().putInt("retention_days", days).apply()
            lifecycleScope.launch { repository.cleanup(days) }
            Toast.makeText(this, "הגדרת השמירה נשמרה", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.deleteHistory).setOnClickListener {
            AlertDialog.Builder(this).setTitle("מחיקת כל ההיסטוריה")
                .setMessage("כל האירועים, הסשנים ודגימות הסוללה במכשיר יימחקו. אי אפשר לבטל פעולה זו.")
                .setNegativeButton("ביטול", null)
                .setPositiveButton("מחק") { _, _ ->
                    lifecycleScope.launch {
                        repository.deleteAllHistory()
                        Toast.makeText(this@MainActivity, "ההיסטוריה נמחקה", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun showChangePasswordDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val oldPassword = EditText(this).apply {
            hint = "סיסמה נוכחית"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newPassword = EditText(this).apply {
            hint = "סיסמה חדשה"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirm = EditText(this).apply {
            hint = "אימות סיסמה חדשה"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(oldPassword)
        box.addView(newPassword)
        box.addView(confirm)

        AlertDialog.Builder(this)
            .setTitle("שינוי סיסמה")
            .setView(box)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("שמירה") { _, _ ->
                when {
                    !passwordManager.verify(oldPassword.text.toString()) ->
                        Toast.makeText(this, "הסיסמה הנוכחית שגויה", Toast.LENGTH_SHORT).show()
                    newPassword.text.length < 4 ->
                        Toast.makeText(this, "הסיסמה החדשה קצרה מדי", Toast.LENGTH_SHORT).show()
                    newPassword.text.toString() != confirm.text.toString() ->
                        Toast.makeText(this, "הסיסמאות החדשות אינן זהות", Toast.LENGTH_SHORT).show()
                    else -> {
                        passwordManager.setPassword(newPassword.text.toString())
                        Toast.makeText(this, "הסיסמה שונתה", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun updatePermissionScreen() {
        if (rootContent.findViewById<View>(R.id.accessibilityStatus) == null) return
        val accessibility = accessibilityEnabled()
        val usage = usageStats.hasUsageAccess()
        findViewById<TextView>(R.id.accessibilityStatus).text = if (accessibility) "פעיל" else "לא פעיל"
        findViewById<TextView>(R.id.usageStatus).text = if (usage) "פעיל" else "לא פעיל"
        findViewById<TextView>(R.id.accessibilityDetails).text =
            "נדרש כדי לזהות מתי עוברים בין אפליקציות ולהתחיל או להפסיק את ההסרטה לפי הבחירה."
        findViewById<TextView>(R.id.usageDetails).text =
            "משמש לנתוני זמן שימוש בלבד. אפשר להשאיר כבוי אם אינך זקוק לסטטיסטיקות."
        updateBackgroundPermissionStatus()
    }

    private fun getScreenRecordingPackages(): MutableSet<String> {
        val prefs = getSharedPreferences("screen_recording", MODE_PRIVATE)
        return (prefs.getStringSet(ScreenCaptureService.KEY_PACKAGES, emptySet()) ?: emptySet()).toMutableSet()
    }

    private fun setScreenRecordingForApp(packageName: String, enabled: Boolean) {
        val selected = getScreenRecordingPackages()
        if (enabled) selected.add(packageName) else selected.remove(packageName)
        getSharedPreferences("screen_recording", MODE_PRIVATE).edit()
            .putStringSet(ScreenCaptureService.KEY_PACKAGES, selected.toSet()).apply()
        if (enabled) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("monitoring_enabled", true).apply()
        } else if (selected.isEmpty() && ScreenCaptureService.isRunning() &&
            !ScreenCaptureService.isContinuousMode()) {
            ScreenCaptureService.stop(this)
        } else if (ScreenCaptureService.isRunning() && !ScreenCaptureService.isContinuousMode()) {
            ScreenCaptureService.setActivePackage(null)
        }
        MonitorTileService.refreshTile(this)
    }

    private fun startSelectedAppsRecording() {
        if (!accessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("נדרשת הפעלת זיהוי אפליקציות")
                .setMessage("כדי להתחיל ולסיים הסרטה אוטומטית לפי אפליקציות, יש להפעיל את שירות הנגישות של ActivityMonitor בהגדרות Android. לאחר ההפעלה חזור לכאן ולחץ שוב על הכפתור. ההסרטה הכללית אינה דורשת הגדרה זו.")
                .setNegativeButton("ביטול", null)
                .setPositiveButton("פתיחת הגדרות") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                .show()
            return
        }
        val selected = getScreenRecordingPackages()
        if (selected.isEmpty()) {
            Toast.makeText(this, "בחר לפחות אפליקציה אחת", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("screen_recording", MODE_PRIVATE).edit()
            .putBoolean("continuous_recording", false).apply()
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("monitoring_enabled", true).apply()
        MonitorTileService.refreshTile(this)
        if (ScreenCaptureService.isRunning()) {
            pendingContinuousStart = false
            if (ScreenCaptureService.isContinuousMode()) ScreenCaptureService.setContinuousMode(false)
            Toast.makeText(this,
                "המצב לפי אפליקציות נבחר. ההסרטה תתחיל כשתפתח אפליקציה שסימנת.",
                Toast.LENGTH_LONG).show()
            updateDashboardRecordingStatus()
            return
        }
        pendingContinuousStart = false
        requestScreenCapture()
    }

    private fun startContinuousRecording() {
        val screenPrefs = getSharedPreferences("screen_recording", MODE_PRIVATE)
        if (ScreenCaptureService.isRunning() && ScreenCaptureService.isContinuousMode()) {
            Toast.makeText(this, "ההסרטה הכללית כבר פעילה", Toast.LENGTH_SHORT).show()
            return
        }
        screenPrefs.edit().putBoolean("continuous_recording", true).apply()
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("monitoring_enabled", true).apply()
        MonitorTileService.refreshTile(this)
        if (ScreenCaptureService.isRunning()) {
            pendingContinuousStart = false
            ScreenCaptureService.setContinuousMode(true)
            updateDashboardRecordingStatus()
            Toast.makeText(this, "ההסרטה הכללית החלה", Toast.LENGTH_SHORT).show()
        } else {
            pendingContinuousStart = true
            requestScreenCapture()
        }
    }

    private fun stopScreenCaptureFromHome() {
        getSharedPreferences("screen_recording", MODE_PRIVATE).edit()
            .putBoolean("continuous_recording", false).apply()
        pendingContinuousStart = false
        ScreenCaptureService.stop(this)
        MonitorTileService.refreshTile(this)
        updateDashboardRecordingStatus()
        Toast.makeText(this, "ההסרטה נעצרה", Toast.LENGTH_SHORT).show()
    }

    private fun setMonitoringEnabled(enabled: Boolean) {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("monitoring_enabled", enabled).apply()
        MonitorTileService.refreshTile(this)
        if (!enabled) {
            getSharedPreferences("screen_recording", MODE_PRIVATE).edit()
                .putBoolean("continuous_recording", false).apply()
            pendingContinuousStart = false
            ScreenCaptureService.stop(this)
        } else if (!ScreenCaptureService.isRunning()) {
            val continuous = getSharedPreferences("screen_recording", MODE_PRIVATE)
                .getBoolean("continuous_recording", false)
            if (continuous || getScreenRecordingPackages().isNotEmpty()) requestScreenCapture()
        }
        updateDashboardRecordingStatus()
        updatePermissionBanner()
        if (navStack.lastOrNull() == "permissions") updatePermissionScreen()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching {
            startActivityForResult(manager.createScreenCaptureIntent(), screenCaptureRequestCode)
        }.onFailure {
            Toast.makeText(this, "לא ניתן לפתוח את אישור תיעוד המסך במכשיר הזה", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != screenCaptureRequestCode) return

        if (resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
            if (pendingContinuousStart) ScreenCaptureService.setContinuousMode(true)
            pendingContinuousStart = false
            MonitorTileService.refreshTile(this)
            val continuous = getSharedPreferences("screen_recording", MODE_PRIVATE)
                .getBoolean("continuous_recording", false)
            Toast.makeText(
                this,
                if (continuous) "ההסרטה הכללית החלה"
                else "מצב לפי אפליקציות הופעל. פתח אפליקציה שסימנת כדי להתחיל להקליט.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            if (pendingContinuousStart) {
                getSharedPreferences("screen_recording", MODE_PRIVATE).edit()
                    .putBoolean("continuous_recording", false).apply()
            }
            pendingContinuousStart = false
            Toast.makeText(this, "תיעוד המסך לא הופעל", Toast.LENGTH_SHORT).show()
        }

        if (navStack.lastOrNull() == "permissions") updatePermissionScreen()
        updateDashboardRecordingStatus()
    }

    private fun showRecordings() {
        inflateScreen(R.layout.screen_recordings, "כל הסרטונים")

        fun updateList() {
            val files = recordingFiles()
            val totalBytes = files.sumOf { it.length() }
            val sizeText = when {
                totalBytes < 1024L * 1024L -> "${totalBytes / 1024L} KB"
                else -> String.format(java.util.Locale.US, "%.1f MB", totalBytes / 1024f / 1024f)
            }
            findViewById<TextView>(R.id.recordingsSummary).text =
                when (files.size) {
                    0 -> "אין סרטונים"
                    1 -> "סרטון אחד • $sizeText"
                    else -> "${files.size} סרטונים • $sizeText"
                }
            findViewById<TextView>(R.id.recordingsEmpty).visibility =
                if (files.isEmpty()) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.deleteAllRecordings).isEnabled = files.isNotEmpty()
            findViewById<RecyclerView>(R.id.recordingsRecycler).adapter =
                RecordingAdapter(files) { updateList() }
        }

        findViewById<RecyclerView>(R.id.recordingsRecycler).layoutManager =
            LinearLayoutManager(this)

        findViewById<Button>(R.id.deleteAllRecordings).apply {
            isEnabled = recordingFiles().isNotEmpty()
            setOnClickListener {
                val count = recordingFiles().size
                if (count == 0) return@setOnClickListener
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("מחיקת כל הסרטונים")
                    .setMessage("למחוק $count סרטונים? לא ניתן לבטל פעולה זו.")
                    .setNegativeButton("ביטול", null)
                    .setPositiveButton("מחק הכול") { _, _ ->
                        recordingFiles().forEach { it.delete() }
                        updateList()
                        isEnabled = false
                        Toast.makeText(this@MainActivity, "כל הסרטונים נמחקו", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }

        updateList()
    }
    private fun updateBatteryScreenIfVisible(samples: List<BatterySampleEntity>) {
        if (navStack.lastOrNull() != "battery") return
        val latest = samples.firstOrNull() ?: return
        findViewById<TextView>(R.id.batteryLevel).text = "${latest.batteryLevel}%"
        findViewById<TextView>(R.id.batteryCharging).text = if (latest.charging) "מחובר לטעינה" else "לא בטעינה"
        findViewById<TextView>(R.id.batterySampleTime).text = "זמן דגימה: ${Formatters.dateTime(latest.timestamp)}"
        findViewById<TextView>(R.id.batteryTemp).text = latest.temperature?.let { "טמפרטורה: %.1f°C".format(it) } ?: "טמפרטורה: המידע אינו זמין מהמכשיר"
    }

    private fun openEvent(event: ActivityEventEntity) {
        navStack.addLast("event:${event.id}")
        inflateScreen(R.layout.screen_event_detail, "פרטי הפעולה")
        lifecycleScope.launch {
            val item = viewModel.event(event.id)
            if (item == null) { Toast.makeText(this@MainActivity, "האירוע כבר לא קיים", Toast.LENGTH_SHORT).show(); navStack.removeLast(); render(navStack.lastOrNull() ?: "dashboard"); return@launch }
            iconCache.get(item.packageName)?.let { findViewById<ImageView>(R.id.detailIcon).setImageDrawable(it) }
            findViewById<TextView>(R.id.detailApp).text = item.appName
            findViewById<TextView>(R.id.detailWhen).text = Formatters.dateTime(item.timestamp)
            findViewById<TextView>(R.id.detailType).text = item.eventDescription
            findViewById<TextView>(R.id.detailText).text = item.text ?: "המידע אינו זמין מאפליקציית המקור"
            findViewById<TextView>(R.id.detailContentDescription).text = item.contentDescription ?: "המידע אינו זמין מאפליקציית המקור"
            findViewById<TextView>(R.id.detailViewId).text = item.viewId ?: "המידע אינו זמין מאפליקציית המקור"
            findViewById<TextView>(R.id.detailClass).text = item.className ?: "המידע אינו זמין מאפליקציית המקור"
            findViewById<TextView>(R.id.detailActivity).text = item.activityName ?: "המידע אינו זמין מאפליקציית המקור"
            findViewById<TextView>(R.id.detailPackage).text = item.packageName
            findViewById<TextView>(R.id.detailSession).text = item.sessionId?.toString() ?: "המידע אינו זמין מאפליקציית המקור"
            findViewById<TextView>(R.id.detailTechnical).text = CryptoManager(this@MainActivity).decrypt(item.sourceInfo ?: "מידע מקור נוסף אינו זמין")
            val container = findViewById<View>(R.id.technicalContainer)
            findViewById<Switch>(R.id.technicalToggle).setOnCheckedChangeListener { _, checked -> container.visibility = if (checked) View.VISIBLE else View.GONE }
        }
    }

    private fun openAppTimeline(packageName: String, name: String) {
        navStack.addLast("apptimeline:$packageName")
        inflateScreen(R.layout.screen_app_timeline, "פעילות")
        iconCache.get(packageName)?.let { findViewById<ImageView>(R.id.appTimelineIcon).setImageDrawable(it) }
        findViewById<TextView>(R.id.appTimelineTitle).text = name
        findViewById<TextView>(R.id.appTimelineSubtitle).text = "פתיחות ומעברים בין מסכים מרכזיים"
        val list = findViewById<RecyclerView>(R.id.appTimelineRecycler)
        val empty = findViewById<TextView>(R.id.appTimelineEmpty)
        val meta = findViewById<TextView>(R.id.appTimelineMeta)
        val adapter = EventAdapter(iconCache) { openEvent(it) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        lifecycleScope.launch {
            val events = viewModel.packageEvents(packageName, Formatters.dayStart(-29), System.currentTimeMillis() + 1)
            if (navStack.lastOrNull() != "apptimeline:$packageName") return@launch
            adapter.submitList(events)
            meta.text = if (events.isEmpty()) "אין מעברים שנרשמו בטווח" else "${events.size} מעברים • החדש ביותר מוצג ראשון"
            empty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    companion object {
        const val ACTION_START_FROM_TILE = "com.example.activitymonitor.action.START_FROM_TILE"
        const val ACTION_STOP_FROM_TILE = "com.example.activitymonitor.action.STOP_FROM_TILE"
        private const val BACKGROUND_NOTIFICATION_REQUEST = 7421
    }

    private fun accessibilityEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this, com.example.activitymonitor.monitoring.MonitorAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val s = info.resolveInfo.serviceInfo
            ComponentName(s.packageName, s.name) == expected
        }
    }
}
