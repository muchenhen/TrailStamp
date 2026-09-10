package com.muchenhen.gpslog

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.muchenhen.gpslog.data.SessionStatus
import com.muchenhen.gpslog.data.RecordingProfiles
import com.muchenhen.gpslog.data.TrackPointEntity
import com.muchenhen.gpslog.data.TrackSessionEntity
import com.muchenhen.gpslog.data.UploadStatus
import com.muchenhen.gpslog.export.ExportFormat
import com.muchenhen.gpslog.export.TrackExporter
import com.muchenhen.gpslog.service.LocationRecordingService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: GPSLogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GPSLogApp(viewModel) }
    }

    fun permissionState(): PermissionState {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationPermission = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val recordingChannel = notificationManager.getNotificationChannel(LocationRecordingService.NOTIFICATION_CHANNEL_ID)
        val notifications = notificationPermission &&
            notificationManager.areNotificationsEnabled() &&
            (recordingChannel == null || recordingChannel.importance != NotificationManager.IMPORTANCE_NONE)
        val locationEnabled = getSystemService(LocationManager::class.java).isLocationEnabled
        return PermissionState(fine, background, notifications, locationEnabled)
    }
}

private val GPSLogColors = lightColorScheme(
    primary = Color(0xFF006D40),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9BF6BD),
    surface = Color(0xFFF7FBF6),
    surfaceVariant = Color(0xFFE0E8E1),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GPSLogApp(viewModel: GPSLogViewModel) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    val snackbar = remember { SnackbarHostState() }

    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.updatePermissions(activity.permissionState())
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.updatePermissions(activity.permissionState())
    }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.updatePermissions(activity.permissionState())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.updatePermissions(activity.permissionState())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.updatePermissions(activity.permissionState())
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.activeSession?.id, state.permissions.readyToRecord) {
        if (state.activeSession != null && state.permissions.readyToRecord) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationRecordingService::class.java).setAction(LocationRecordingService.ACTION_RESUME),
            )
        }
    }

    MaterialTheme(colorScheme = GPSLogColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (route.startsWith("detail")) "会话详情" else "GPSLog") },
                    navigationIcon = {
                        if (route.startsWith("detail")) {
                            IconButton(
                                onClick = { navController.navigateUp() },
                                modifier = Modifier.padding(start = 8.dp).size(40.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "返回",
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!route.startsWith("detail")) {
                    NavigationBar {
                        listOf(
                            Triple("home", "首页", Icons.Default.Home),
                            Triple("history", "历史", Icons.Default.History),
                            Triple("settings", "设置", Icons.Default.Settings),
                        ).forEach { (destination, label, icon) ->
                            NavigationBarItem(
                                selected = route == destination,
                                onClick = { navController.navigate(destination) { launchSingleTop = true } },
                                icon = { androidx.compose.material3.Icon(icon, label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.padding(padding),
            ) {
                composable("home") {
                    HomeScreen(
                        state = state,
                        onStart = {
                            if (state.permissions.readyToRecord) {
                                ContextCompat.startForegroundService(context, LocationRecordingService.startIntent(context))
                            }
                        },
                        onStop = { context.startService(LocationRecordingService.stopIntent(context)) },
                        onPermissions = { navController.navigate("settings") },
                        onDetails = { navController.navigate("detail/${it.id}") },
                    )
                }
                composable("history") {
                    HistoryScreen(
                        sessions = state.sessions,
                        onOpen = { navController.navigate("detail/${it.id}") },
                        onDelete = viewModel::deleteSession,
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        state = state,
                        onProfile = viewModel::setRecordingProfile,
                        onInterval = viewModel::setTargetIntervalMinutes,
                        onAdmin = viewModel::setAdminLookupEnabled,
                        onSaveUpload = viewModel::saveUploadSettings,
                        onClearUploadToken = viewModel::clearUploadToken,
                        onForegroundPermission = {
                            foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        },
                        onNotificationPermission = {
                            val runtimePermissionGranted = Build.VERSION.SDK_INT < 33 ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            if (!runtimePermissionGranted) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openRecordingNotificationSettings(context)
                            }
                        },
                        onBackgroundPermission = {
                            if (Build.VERSION.SDK_INT == 29) backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            else openAppSettings(context)
                        },
                        onLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        onBatterySettings = { openAppSettings(context) },
                    )
                }
                composable(
                    route = "detail/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { entry ->
                    val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                    val session = state.sessions.firstOrNull { it.id == sessionId }
                    if (session == null) Text("会话不存在", Modifier.padding(24.dp))
                    else SessionDetailScreen(session, viewModel, state.uploadTokenConfigured)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: GPSLogUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPermissions: () -> Unit,
    onDetails: (TrackSessionEntity) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.activeSession?.id) {
        while (state.activeSession != null) { delay(1_000); now = System.currentTimeMillis() }
    }
    val session = state.activeSession
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(if (session == null) "准备记录旅程" else "正在后台记录", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (session == null) {
                    recordingProfileSummary(state.settings.recordingProfile, state.settings.targetIntervalSeconds)
                } else {
                    "${recordingProfileLabel(session.profile)} · 锁屏后常驻通知仍会显示状态与控制按钮"
                },
            )
        }
        item {
            Button(
                onClick = if (session == null) onStart else onStop,
                enabled = session != null || state.permissions.readyToRecord,
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) {
                Text(if (session == null) "开始记录" else "停止记录", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (!state.permissions.readyToRecord) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("开始前还需完成权限与系统设置", style = MaterialTheme.typography.titleMedium)
                        Text("精确定位、始终允许、通知和系统定位必须全部就绪。")
                        TextButton(onClick = onPermissions) { Text("前往设置") }
                    }
                }
            }
        }
        session?.let { active ->
            item {
                MetricCard("时长", formatDuration(now - active.startedAtUtc), "点数", "${active.rawPointCount}（可用 ${active.usablePointCount}）")
            }
            item {
                val point = state.latestPoint
                MetricCard(
                    "最近定位",
                    point?.let { "${formatAge(now - it.timestampUtc)}前" } ?: "等待中",
                    "精度",
                    point?.accuracyMeters?.let { "±${it.toInt()} 米" } ?: "—",
                )
            }
            state.latestPoint?.let { point ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${"%.6f".format(point.latitude)}, ${"%.6f".format(point.longitude)}")
                            Text(listOfNotNull(point.province, point.city, point.district, point.name).distinct().joinToString(" ").ifBlank { "行政区未解析" })
                        }
                    }
                }
            }
            item { TextButton(onClick = { onDetails(active) }) { Text("查看当前会话详情") } }
        }
    }
}

@Composable
private fun MetricCard(label1: String, value1: String, label2: String, value2: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(label1, style = MaterialTheme.typography.labelLarge); Text(value1, style = MaterialTheme.typography.titleLarge) }
            Column(horizontalAlignment = Alignment.End) { Text(label2, style = MaterialTheme.typography.labelLarge); Text(value2, style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@Composable
private fun HistoryScreen(
    sessions: List<TrackSessionEntity>,
    onOpen: (TrackSessionEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<TrackSessionEntity?>(null) }
    if (sessions.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有轨迹会话")
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { session ->
                Card(onClick = { onOpen(session) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(formatDate(session.startedAtUtc), style = MaterialTheme.typography.titleMedium)
                        Text("${statusLabel(session.status)} · ${recordingProfileLabel(session.profile)} · ${session.rawPointCount} 点 · 缺口 ${session.gapCount}")
                        if (session.status != SessionStatus.ACTIVE) {
                            Text("上传：${uploadStatusLabel(session.uploadStatus)}")
                        }
                        if (session.status != SessionStatus.ACTIVE) {
                            TextButton(onClick = { deleteTarget = session }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话？") },
            text = { Text("轨迹点将一并删除，已导出的文件不受影响。") },
            confirmButton = { TextButton(onClick = { onDelete(target.id); deleteTarget = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsScreen(
    state: GPSLogUiState,
    onProfile: (String) -> Unit,
    onInterval: (Int) -> Unit,
    onAdmin: (Boolean) -> Unit,
    onSaveUpload: (String, String, Boolean) -> Unit,
    onClearUploadToken: () -> Unit,
    onForegroundPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onBackgroundPermission: () -> Unit,
    onLocationSettings: () -> Unit,
    onBatterySettings: () -> Unit,
) {
    val permissions = state.permissions
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("记录模式", style = MaterialTheme.typography.titleLarge) }
        item {
            RecordingProfileCard(
                title = "耐力模式",
                detail = "平衡精度与功耗；适合全天游览、旅行和长时间锁屏记录。",
                selected = state.settings.recordingProfile == RecordingProfiles.ENDURANCE,
                enabled = state.activeSession == null,
                onClick = { onProfile(RecordingProfiles.ENDURANCE) },
            )
        }
        item {
            RecordingProfileCard(
                title = "精细步行",
                detail = "每 10 秒高精度采样、最短 5 秒、最多延迟 15 秒；适合植物园、公园和短时小范围曲折移动，耗电会明显增加。",
                selected = state.settings.recordingProfile == RecordingProfiles.PRECISION_WALK,
                enabled = state.activeSession == null,
                onClick = { onProfile(RecordingProfiles.PRECISION_WALK) },
            )
        }
        item {
            Column {
                Text("耐力模式目标间隔 ${state.settings.targetIntervalSeconds / 60} 分钟（允许范围 1–5 分钟）")
                Slider(
                    value = (state.settings.targetIntervalSeconds / 60).toFloat(),
                    onValueChange = { onInterval(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                    enabled = state.activeSession == null && state.settings.recordingProfile == RecordingProfiles.ENDURANCE,
                )
                if (state.activeSession != null) Text("录制期间模式和间隔保持会话快照，不可更改。")
            }
        }
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("联网解析行政区", style = MaterialTheme.typography.titleMedium)
                        Text("默认关闭；关闭时不会调用 Android Geocoder。")
                    }
                    Switch(checked = state.settings.adminLookupEnabled, onCheckedChange = onAdmin, enabled = state.activeSession == null)
                }
            }
        }
        item {
            UploadSettingsCard(
                state = state,
                onSave = onSaveUpload,
                onClearToken = onClearUploadToken,
            )
        }
        item { Text("权限与后台存活", style = MaterialTheme.typography.titleLarge) }
        item {
            ReadinessCard(
                "精确定位",
                permissions.preciseLocation,
                "系统已授予精确位置权限",
                "选择“使用应用时”并开启精确位置",
                onForegroundPermission,
            )
        }
        item {
            ReadinessCard(
                "始终允许定位",
                permissions.backgroundLocation,
                "系统已授予后台位置权限",
                "在应用权限 → 位置信息中选择“始终允许”",
                onBackgroundPermission,
            )
        }
        item {
            ReadinessCard(
                "常驻通知",
                permissions.notifications,
                "系统允许 GPSLog 显示记录通知",
                "请允许通知，并开启“持续定位记录”通知类别",
                onNotificationPermission,
            )
        }
        item {
            ReadinessCard(
                "系统定位",
                permissions.locationEnabled,
                "系统定位服务已开启",
                "需要保持系统定位服务开启",
                onLocationSettings,
            )
        }
        item {
            ManualSetupCard(onBatterySettings)
        }
    }
}

@Composable
private fun UploadSettingsCard(
    state: GPSLogUiState,
    onSave: (String, String, Boolean) -> Unit,
    onClearToken: () -> Unit,
) {
    var serverUrl by rememberSaveable(state.settings.processingServerUrl) {
        mutableStateOf(state.settings.processingServerUrl)
    }
    var token by rememberSaveable { mutableStateOf("") }
    var autoUpload by rememberSaveable(state.settings.autoUploadEnabled) {
        mutableStateOf(state.settings.autoUploadEnabled)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Processing 自动上传", style = MaterialTheme.typography.titleMedium)
            Text("仅上传已完成或已中断的会话；JSON、GPX、CSV 手工导出始终保留。")
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("HTTPS 服务器地址") },
                placeholder = { Text("https://processing.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (state.uploadTokenConfigured) "上传 token（已安全保存）" else "上传 token") },
                placeholder = { Text(if (state.uploadTokenConfigured) "留空表示不更换" else "粘贴 tracks:write token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("会话结束后自动上传")
                    Text("断网时由系统指数退避；同一会话使用唯一后台任务。")
                }
                Switch(checked = autoUpload, onCheckedChange = { autoUpload = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onSave(serverUrl, token, autoUpload)
                    token = ""
                }) { Text("保存上传设置") }
                if (state.uploadTokenConfigured) {
                    TextButton(onClick = {
                        token = ""
                        autoUpload = false
                        onClearToken()
                    }) { Text("清除 token") }
                }
            }
            Text("token 由 Android Keystore 加密，不会进入导出、日志或系统备份。")
        }
    }
}

@Composable
private fun RecordingProfileCard(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail)
            }
        }
    }
}

@Composable
private fun ReadinessCard(
    title: String,
    ready: Boolean,
    readyDetail: String,
    missingDetail: String,
    onAction: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("$title · ${if (ready) "已设置" else "未设置"}", style = MaterialTheme.typography.titleMedium)
            Text(if (ready) readyDetail else missingDetail)
            if (!ready) TextButton(onClick = onAction) { Text("去设置") }
        }
    }
}

@Composable
private fun ManualSetupCard(onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Samsung 后台存活 · 需手动确认", style = MaterialTheme.typography.titleMedium)
            Text("系统不允许 GPSLog 可靠读取这两项。请在应用信息 → 电池中选择“不受限制”，并把 GPSLog 加入“从不休眠的应用”。")
            TextButton(onClick = onAction) { Text("打开应用设置") }
        }
    }
}

@Composable
private fun SessionDetailScreen(
    session: TrackSessionEntity,
    viewModel: GPSLogViewModel,
    uploadTokenConfigured: Boolean,
) {
    val context = LocalContext.current
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType)) { uri ->
        uri?.let { viewModel.export(context.contentResolver, session.id, ExportFormat.JSON, it) }
    }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.GPX.mimeType)) { uri ->
        uri?.let { viewModel.export(context.contentResolver, session.id, ExportFormat.GPX, it) }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mimeType)) { uri ->
        uri?.let { viewModel.export(context.contentResolver, session.id, ExportFormat.CSV, it) }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(statusLabel(session.status), style = MaterialTheme.typography.headlineMedium) }
        item { MetricCard("开始", formatDate(session.startedAtUtc), "结束", session.endedAtUtc?.let(::formatDate) ?: "记录中") }
        item { MetricCard("原始点", session.rawPointCount.toString(), "可用点", session.usablePointCount.toString()) }
        item { MetricCard("定位缺口", session.gapCount.toString(), "采样目标", formatInterval(session.targetIntervalSeconds)) }
        item { Text("记录模式：${recordingProfileLabel(session.profile)}") }
        if (session.status != SessionStatus.ACTIVE) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("服务器上传：${uploadStatusLabel(session.uploadStatus)}", style = MaterialTheme.typography.titleMedium)
                        session.uploadRevisionSha256?.let { Text("revision ${it.take(12)}…") }
                        session.uploadLastErrorCode?.let { Text("最近错误：$it") }
                        if (uploadTokenConfigured && session.uploadStatus != UploadStatus.UPLOADING) {
                            TextButton(onClick = { viewModel.retryUpload(session.id) }) { Text("手工重试上传") }
                        }
                    }
                }
            }
        }
        session.interruptionReason?.let { item { Text("中断原因：$it") } }
        item { HorizontalDivider() }
        item { Text("导出", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { jsonLauncher.launch(TrackExporter.suggestedFileName(session, ExportFormat.JSON)) }) { Text("JSON") }
                Button(onClick = { gpxLauncher.launch(TrackExporter.suggestedFileName(session, ExportFormat.GPX)) }) { Text("GPX") }
                Button(onClick = { csvLauncher.launch(TrackExporter.suggestedFileName(session, ExportFormat.CSV)) }) { Text("CSV") }
            }
        }
        item { Text("通过系统文件选择器保存；原始数据库不会因导出而改变。") }
        if (session.status != SessionStatus.ACTIVE) {
            item {
                TextButton(onClick = { viewModel.completeAdministrativeAreas(session.id) }) {
                    Text("补全行政区")
                }
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

private fun openRecordingNotificationSettings(context: Context) {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val recordingChannel = notificationManager.getNotificationChannel(LocationRecordingService.NOTIFICATION_CHANNEL_ID)
    val intent = if (notificationManager.areNotificationsEnabled() && recordingChannel != null) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_CHANNEL_ID, LocationRecordingService.NOTIFICATION_CHANNEL_ID)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }
    context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

private fun formatDate(epochMillis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))

private fun formatDuration(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1_000
    return "%02d:%02d:%02d".format(seconds / 3_600, (seconds % 3_600) / 60, seconds % 60)
}

private fun formatAge(value: Long): String = when {
    value < 60_000 -> "${value.coerceAtLeast(0) / 1_000} 秒"
    else -> "${value / 60_000} 分钟"
}

private fun formatInterval(seconds: Int): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "${seconds / 60} 分 ${seconds % 60} 秒"
}

private fun recordingProfileLabel(profile: String): String = when (RecordingProfiles.normalize(profile)) {
    RecordingProfiles.PRECISION_WALK -> "精细步行"
    else -> "耐力模式"
}

private fun recordingProfileSummary(profile: String, enduranceIntervalSeconds: Int): String =
    if (RecordingProfiles.normalize(profile) == RecordingProfiles.PRECISION_WALK) {
        "精细步行每 10 秒高精度采样"
    } else {
        "耐力模式每 ${enduranceIntervalSeconds / 60} 分钟采样"
    }

private fun statusLabel(status: String): String = when (status) {
    SessionStatus.ACTIVE -> "记录中"
    SessionStatus.COMPLETED -> "已完成"
    else -> "已中断"
}

private fun uploadStatusLabel(status: String): String = when (status) {
    UploadStatus.PENDING -> "等待网络/重试"
    UploadStatus.UPLOADING -> "上传中"
    UploadStatus.UPLOADED -> "已上传"
    UploadStatus.FAILED -> "失败"
    else -> "未配置"
}
