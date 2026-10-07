package com.example.activitymonitor

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.text.InputType
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
import com.example.activitymonitor.ui.AppAdapter
import com.example.activitymonitor.ui.AppRow
import com.example.activitymonitor.ui.BarChartView
import com.example.activitymonitor.ui.EventAdapter
import com.example.activitymonitor.ui.EventTranslator
import com.example.activitymonitor.ui.Formatters
import com.example.activitymonitor.ui.MainViewModel
import com.example.activitymonitor.ui.RecentAppAdapter
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
    private lateinit var appAdapter: AppAdapter
    private lateinit var recentAppAdapter: RecentAppAdapter
    private lateinit var passwordManager: PasswordManager
    private lateinit var toolbarBack: ImageButton
    private var mainUiInitialized = false
    private var permissionPromptShown = false
    private val navStack = ArrayDeque<String>()
    private var searchOffset = 0
    private var searchCustomStart: Long? = null
    private var searchCustomEnd: Long? = null
    private var searchPackageFilter: String? = null
    private var searchEventTypeFilter: Int? = null
    private var pendingRecordPackage: String? = null
    private val screenCaptureRequestCode = 7401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onResume() {
        super.onResume()
        if (mainUiInitialized && navStack.lastOrNull() == "dashboard") refreshDashboard()
        when (navStack.lastOrNull()) {
            "permissions" -> updatePermissionScreen()
            "dashboard" -> updatePermissionBanner()
        }
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
                if (passwordManager.verify(input.text.toString())) initMainUi()
                else showPasswordError(error, "הסיסמה שגויה.")
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
        render("dashboard")
        maybePromptUsageAccess()
    }

    private fun maybePromptUsageAccess() {
        if (permissionPromptShown || usageStats.hasUsageAccess()) return
        permissionPromptShown = true
        AlertDialog.Builder(this)
            .setTitle("נדרשת גישה לנתוני שימוש")
            .setMessage("כדי שהאפליקציה תוכל להציג זמן שימוש ופתיחות בצורה מלאה, יש להפעיל גישה לנתוני שימוש בהגדרות Android.")
            .setNegativeButton("אחר כך", null)
            .setPositiveButton("פתיחת ההגדרה") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            .show()
    }

    private fun setupNav() {
        mapOf(
            R.id.navDashboard to "dashboard",
            R.id.navApps to "apps",
            R.id.navStats to "stats",
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
        findViewById<Button>(R.id.navApps).isSelected = key == "apps" || key.startsWith("apptimeline:")
        findViewById<Button>(R.id.navStats).isSelected = key == "stats" || key == "battery"
        findViewById<Button>(R.id.navPermissions).isSelected = key == "permissions"
    }

    private fun setupAdapters() {
        eventAdapter = EventAdapter(iconCache) { openEvent(it) }
        searchAdapter = EventAdapter(iconCache) { openEvent(it) }
        recentAppAdapter = RecentAppAdapter(iconCache) { session -> openAppTimeline(session.packageName, session.appName) }
        appAdapter = AppAdapter(
            icons = iconCache,
            onToggle = { pkg, enabled ->
                lifecycleScope.launch {
                    repository.upsertTracking(pkg, enabled)
                    getSharedPreferences("settings", MODE_PRIVATE)
                        .edit()
                        .putBoolean("track_$pkg", enabled)
                        .apply()
                }
            },
            onRecordingToggle = { pkg, enabled -> setScreenRecordingForApp(pkg, enabled) },
            onClick = { row -> openAppTimeline(row.packageName, row.name) }
        )
    }

    private fun setupCollectors() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.events.collect { eventAdapter.submitList(it) } }
                launch { viewModel.searchResults.collect { searchAdapter.submitList(it); rootContent.findViewById<View>(R.id.searchEmpty)?.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE } }
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
        inflateScreen(R.layout.screen_dashboard, "ראשי")
        findViewById<RecyclerView>(R.id.dashboardRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = recentAppAdapter
            setHasFixedSize(true)
        }
        findViewById<Button>(R.id.dashboardPermissions).setOnClickListener { render("permissions") }
        findViewById<Button>(R.id.dashboardSearch).setOnClickListener { render("search") }
        findViewById<Button>(R.id.dashboardRecordings).setOnClickListener { render("recordings") }
        refreshDashboard()
        updatePermissionBanner()
    }

    private fun refreshDashboard() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis() + 1
            val today = Formatters.dayStart()
            val sessions = repository.recentSessions(40)
            val openings = repository.sessionCount(today, now)
            val events = repository.eventCount(today, now)
            val usage = if (usageStats.hasUsageAccess()) {
                usageStats.queryAggregated(today, now).values.sum()
            } else {
                repository.totalDuration(today, now)
            }
            if (navStack.lastOrNull() != "dashboard") return@launch

            recentAppAdapter.submitList(sessions)
            findViewById<TextView>(R.id.dashboardOpenings).text = openings.toString()
            findViewById<TextView>(R.id.dashboardEvents).text = events.toString()
            findViewById<TextView>(R.id.dashboardUsage).text = Formatters.duration(usage)
            findViewById<TextView>(R.id.recentCount).text =
                if (sessions.isEmpty()) "אין היסטוריה עדיין" else "${sessions.size} פתיחות אחרונות"
            findViewById<TextView>(R.id.recentEmpty).visibility =
                if (sessions.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updatePermissionBanner() {
        val banner = rootContent.findViewById<TextView>(R.id.permissionBanner) ?: return
        if (!banner.isAttachedToWindow) return
        val a = accessibilityEnabled()
        val u = usageStats.hasUsageAccess()
        val statusTitle = findViewById<TextView>(R.id.dashboardStatusTitle)
        val hint = findViewById<TextView>(R.id.permissionHint)
        val dot = findViewById<View>(R.id.permissionDot)

        when {
            a && u -> {
                statusTitle?.text = "הניטור פעיל"
                banner.text = "שירות הנגישות ונתוני השימוש זמינים."
                hint?.text = "הפתיחות והפעולות האחרונות ממשיכות להיאסף."
                dot?.setBackgroundResource(R.drawable.bg_permission_dot_ok)
            }
            a -> {
                statusTitle?.text = "הניטור פעיל חלקית"
                banner.text = "שירות הנגישות פעיל, אבל גישה לנתוני שימוש חסרה."
                hint?.text = "הפעל אותה בהגדרות כדי לקבל גם זמני שימוש."
                dot?.setBackgroundResource(R.drawable.bg_permission_dot)
            }
            u -> {
                statusTitle?.text = "הניטור מוגבל"
                banner.text = "נתוני שימוש זמינים, אבל שירות הנגישות כבוי."
                hint?.text = "הפעל אותו כדי לקבל גם פעולות בתוך אפליקציות."
                dot?.setBackgroundResource(R.drawable.bg_permission_dot)
            }
            else -> {
                statusTitle?.text = "נדרשת השלמת הרשאות"
                banner.text = "שני מקורות המידע המרכזיים אינם פעילים."
                hint?.text = "לחץ על \"הרשאות\" כדי לפתוח את ההגדרות הרלוונטיות."
                dot?.setBackgroundResource(R.drawable.bg_permission_dot)
            }
        }
    }

    private fun showApps() {
        inflateScreen(R.layout.screen_apps, "אפליקציות")
        findViewById<RecyclerView>(R.id.appsRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = appAdapter
        }
        findViewById<Button>(R.id.openSearch).setOnClickListener { render("search") }
        lifecycleScope.launch { loadApps() }
    }

    private suspend fun loadApps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }.filter { it.packageName != packageName }.take(150)
        val now = System.currentTimeMillis() + 1; val today = Formatters.dayStart()
        val week = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayUsage = usageStats.queryAggregated(today, now); val weekUsage = usageStats.queryAggregated(week, now)
        val settings = repository.trackingSettings().associateBy { it.packageName }
        val sessionCounts = repository.packageSessionCounts(week, now).associate { it.packageName to it.count }
        val eventRows = repository.eventsBetween(week, now, 5000)
        val eventCounts = eventRows.groupingBy { it.packageName }.eachCount()
        val lastActions = eventRows
            .groupBy { it.packageName }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.timestamp }?.eventDescription }
        val rows = apps.map { info ->
            val pkg = info.packageName
            val name = pm.getApplicationLabel(info).toString().ifBlank { pkg }
            AppRow(
                pkg,
                name,
                todayUsage[pkg] ?: 0L,
                weekUsage[pkg] ?: 0L,
                sessionCounts[pkg] ?: 0,
                eventCounts[pkg] ?: 0,
                lastActions[pkg],
                settings[pkg]?.enabled ?: true,
                getScreenRecordingPackages().contains(pkg)
            )
        }.sortedWith(
            compareByDescending<AppRow> { it.screenRecord }
                .thenByDescending { it.enabled }
                .thenByDescending { it.todayMs }
                .thenBy { it.name.lowercase() }
        )
        if (navStack.lastOrNull() == "apps") {
            appAdapter.submitList(rows)
            findViewById<TextView>(R.id.appsCount).text = "${rows.size} אפליקציות זמינות למעקב"
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
        val input = findViewById<EditText>(R.id.searchInput); val range = findViewById<TextView>(R.id.searchRange)
        searchOffset = 0; searchCustomStart = null; searchCustomEnd = null; searchPackageFilter = null; searchEventTypeFilter = null
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
        val types = listOf(null, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED)
        val labels = listOf("כל הפעולות", "לחיצה על רכיב", "בחירת רכיב", "שינוי טקסט", "מעבר למסך", "שינוי תוכן המסך", "גלילה")
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
        inflateScreen(R.layout.screen_permissions, "הרשאות ואפשרויות ניטור")
        updatePermissionScreen()
        findViewById<Button>(R.id.openAccessibility).setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        findViewById<Button>(R.id.openUsage).setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
        findViewById<Button>(R.id.changePassword).setOnClickListener { showChangePasswordDialog() }
        findViewById<Button>(R.id.openRecordings).setOnClickListener { render("recordings") }
        val advancedToggle = findViewById<Button>(R.id.advancedToggle)
        val advanced = findViewById<View>(R.id.advancedContainer)
        advancedToggle.setOnClickListener {
            val open = advanced.visibility != View.VISIBLE
            advanced.visibility = if (open) View.VISIBLE else View.GONE
            advancedToggle.text = if (open) "אפשרויות מתקדמות  ↓" else "אפשרויות מתקדמות  ›"
        }
        findViewById<Button>(R.id.startScreenCapture).setOnClickListener { requestScreenCapture() }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        findViewById<Switch>(R.id.captureTextSwitch).apply { isChecked = prefs.getBoolean("capture_text", true); setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("capture_text", c).apply() } }
        findViewById<Switch>(R.id.captureSourceSwitch).apply { isChecked = prefs.getBoolean("capture_source", true); setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("capture_source", c).apply() } }
        findViewById<Switch>(R.id.encryptTechnicalSwitch).apply { isChecked = prefs.getBoolean("encrypt_technical", false); setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("encrypt_technical", c).apply() } }
        val retention = findViewById<EditText>(R.id.retentionDays); retention.setText(prefs.getInt("retention_days", 30).toString())
        findViewById<Button>(R.id.saveRetention).setOnClickListener {
            val days = retention.text.toString().toIntOrNull()?.coerceIn(1, 3650) ?: 30
            prefs.edit().putInt("retention_days", days).apply(); lifecycleScope.launch { repository.cleanup(days) }
            Toast.makeText(this, "הגדרת השמירה נשמרה", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.deleteHistory).setOnClickListener {
            AlertDialog.Builder(this).setTitle("מחיקת כל ההיסטוריה")
                .setMessage("כל האירועים, הסשנים ודגימות הסוללה במכשיר יימחקו. אי אפשר לבטל פעולה זו.")
                .setNegativeButton("ביטול", null)
                .setPositiveButton("מחק") { _, _ -> lifecycleScope.launch { repository.deleteAllHistory(); Toast.makeText(this@MainActivity, "ההיסטוריה נמחקה", Toast.LENGTH_SHORT).show() } }
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
        val a = accessibilityEnabled(); val u = usageStats.hasUsageAccess()
        findViewById<TextView>(R.id.accessibilityStatus).text = if (a) "מצב נוכחי: פעיל" else "מצב נוכחי: לא פעיל"
        findViewById<TextView>(R.id.usageStatus).text = if (u) "מצב נוכחי: פעיל" else "מצב נוכחי: לא פעיל"
        findViewById<TextView>(R.id.accessibilityDetails).text = "נדרש כדי לקבל אירועי ממשק שהמערכת ואפליקציות המקור חושפות ל־Accessibility. מידע שלא נחשף לא יוכל להיקלט."
        findViewById<TextView>(R.id.usageDetails).text = "משמש למדידת זמן שימוש באפליקציות דרך UsageStatsManager. Android עשויה להגביל או לשנות את הנתונים הזמינים."
        val captureEnabled = ScreenCaptureService.isRunning()
        findViewById<TextView>(R.id.screenCaptureStatus).text =
            if (captureEnabled) "תיעוד מסך: פעיל" else "תיעוד מסך: כבוי"
        findViewById<TextView>(R.id.screenCaptureDetails).text =
            if (captureEnabled) "הקלטה תתחיל רק כאשר אפליקציה שסומנה לתיעוד נמצאת בחזית."
            else "סמן אפליקציות במסך \"אפליקציות\" והפעל את תיעוד המסך. Android יציג בקשת אישור."
    }

    private fun getScreenRecordingPackages(): MutableSet<String> {
        val prefs = getSharedPreferences("screen_recording", MODE_PRIVATE)
        return (prefs.getStringSet(ScreenCaptureService.KEY_PACKAGES, emptySet()) ?: emptySet()).toMutableSet()
    }

    private fun setScreenRecordingForApp(packageName: String, enabled: Boolean) {
        val selected = getScreenRecordingPackages()
        if (enabled) {
            selected.add(packageName)
            getSharedPreferences("screen_recording", MODE_PRIVATE)
                .edit()
                .putStringSet(ScreenCaptureService.KEY_PACKAGES, selected)
                .apply()
            if (!ScreenCaptureService.isRunning()) {
                pendingRecordPackage = packageName
                requestScreenCapture()
            }
        } else {
            selected.remove(packageName)
            getSharedPreferences("screen_recording", MODE_PRIVATE)
                .edit()
                .putStringSet(ScreenCaptureService.KEY_PACKAGES, selected)
                .apply()
            if (ScreenCaptureService.isRunning()) {
                if (selected.isEmpty()) ScreenCaptureService.stop(this)
                else ScreenCaptureService.setActivePackage(null)
            }
        }
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
            Toast.makeText(this, "תיעוד המסך הופעל", Toast.LENGTH_SHORT).show()
        } else {
            pendingRecordPackage?.let {
                val selected = getScreenRecordingPackages()
                selected.remove(it)
                getSharedPreferences("screen_recording", MODE_PRIVATE)
                    .edit()
                    .putStringSet(ScreenCaptureService.KEY_PACKAGES, selected)
                    .apply()
            }
            Toast.makeText(this, "תיעוד המסך לא הופעל", Toast.LENGTH_SHORT).show()
        }
        pendingRecordPackage = null
        if (navStack.lastOrNull() == "permissions") updatePermissionScreen()
    }

    private fun showRecordings() {
        inflateScreen(R.layout.screen_recordings, "הקלטות")
        val root = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "ActivityMonitor")
        val files = root.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        findViewById<RecyclerView>(R.id.recordingsRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = RecordingAdapter(files)
        }
        findViewById<TextView>(R.id.recordingsEmpty).visibility =
            if (files.isEmpty()) View.VISIBLE else View.GONE
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
        findViewById<TextView>(R.id.appTimelineSubtitle).text = "הפעולות שנקלטו ב־30 הימים האחרונים"
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
            meta.text = if (events.isEmpty()) "אין פעולות שנקלטו בטווח" else "${events.size} פעולות • החדשה ביותר מוצגת ראשונה"
            empty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        }
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
