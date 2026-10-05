package com.vay.camerasnap

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

private data class CameraOption(val id: String, val label: String)

class SettingsActivity : ComponentActivity() {
    private var mode by mutableIntStateOf(SnapConfig.OFF)
    private var cameraId by mutableStateOf("")
    private var destination by mutableStateOf(MediaSavePath.DEFAULT)
    private var destinationBusy by mutableStateOf(false)
    private var hidden by mutableStateOf(false)
    private var connection by mutableStateOf("")
    private var captureStatus by mutableStateOf("")
    private var cameras by mutableStateOf(listOf(CameraOption("", "自动选择后置主摄")))
    private var pendingMode = SnapConfig.OFF
    private val settingsIo = Executors.newSingleThreadExecutor()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refreshState() }
    }
    private val directoryPicker = registerForActivityResult(object : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri != null) {
            destinationBusy = true
            val appContext = applicationContext
            val started = SystemClock.elapsedRealtime()
            settingsIo.execute {
                val result = runCatching {
                    val resolver = appContext.contentResolver
                    val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
                    val name = resolver.query(document, columns, null, null, null)?.use { cursor ->
                        check(cursor.moveToFirst()) { "所选文件夹不存在" }
                        check(cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR &&
                            cursor.getInt(2) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "所选文件夹不支持写入" }
                        cursor.getString(0)
                    } ?: error("无法读取所选文件夹")
                    resolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    SnapConfig.prefs(appContext).edit().putString("save_tree", uri.toString())
                        .putString("save_tree_name", name).apply()
                    resolver.notifyChange(SnapConfig.URI, null)
                }
                Log.d(HookEntry.TAG, "Directory selection completed in ${SystemClock.elapsedRealtime() - started}ms")
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        destinationBusy = false
                        refreshState()
                        result.fold(
                            onSuccess = { toast("保存文件夹已更新，下次拍摄生效") },
                            onFailure = { error ->
                                Log.w(HookEntry.TAG, "Cannot select save directory", error)
                                toast("选择文件夹失败：${error.message}")
                            }
                        )
                    }
                }
            }
        }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val requested = pendingMode
        pendingMode = SnapConfig.OFF
        if (hasPermissions(requested)) saveMode(requested)
        else {
            SnapConfig.status(this, "需要相机权限；录像还需要麦克风权限，请授权后重新开启。")
            refreshState()
        }
        loadCameras()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingMode = savedInstanceState?.getInt("pending_mode", SnapConfig.OFF) ?: SnapConfig.OFF
        enableEdgeToEdge()
        refreshState()
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { SettingsScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        SnapConfig.prefs(this).registerOnSharedPreferenceChangeListener(preferenceListener)
        val currentMode = SnapConfig.read(this).mode
        if (currentMode != SnapConfig.OFF && !hasPermissions(currentMode)) saveMode(SnapConfig.OFF)
        refreshState()
        loadCameras()
    }

    override fun onPause() {
        SnapConfig.prefs(this).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onPause()
    }

    override fun onDestroy() {
        settingsIo.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("pending_mode", pendingMode)
        super.onSaveInstanceState(outState)
    }

    @Composable
    private fun SettingsScreen() {
        var cameraExpanded by rememberSaveable { mutableStateOf(false) }
        Scaffold(topBar = { TopAppBar(title = "息屏街拍") }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp, end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(title = "系统连接", summary = connection)
                        BasicComponent(title = "最近拍摄", summary = captureStatus)
                    }
                }
                item {
                    SmallTitle("拍摄模式")
                    Card(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                        Choice("关闭", "保留系统原有按键行为", mode == SnapConfig.OFF) { selectMode(SnapConfig.OFF) }
                        Choice("息屏连拍", "每成功保存一张照片短振，最多 100 张", mode == SnapConfig.PHOTO) { selectMode(SnapConfig.PHOTO) }
                        Choice("息屏录像", "录制画面和声音，开始和结束时短振", mode == SnapConfig.VIDEO) { selectMode(SnapConfig.VIDEO) }
                    }
                }
                item {
                    SmallTitle("拍摄设置")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = "摄像头",
                            summary = cameras.firstOrNull { it.id == cameraId }?.label ?: "当前摄像头不可用 · $cameraId",
                            onClick = { cameraExpanded = !cameraExpanded },
                            endActions = { Text(if (cameraExpanded) "收起" else "选择", color = MiuixTheme.colorScheme.primary) }
                        )
                        if (cameraExpanded) {
                            Column(Modifier.selectableGroup()) {
                                cameras.forEach { option ->
                                    Choice(option.label, null, option.id == cameraId) {
                                        SnapConfig.prefs(this@SettingsActivity).edit().putString("camera_id", option.id).apply()
                                        notifyConfigChanged()
                                        refreshState()
                                        cameraExpanded = false
                                    }
                                }
                            }
                        }
                        BasicComponent(
                            title = "保存路径", summary = destination,
                            onClick = ::chooseDestination,
                            endActions = { Text(if (destinationBusy) "保存中" else "修改", color = MiuixTheme.colorScheme.primary) }
                        )
                    }
                }
                item {
                    SmallTitle("应用设置")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = "隐藏桌面图标", checked = hidden,
                            summary = "隐藏后从相机设置的“街拍”进入，关闭开关恢复图标",
                            onCheckedChange = ::setIconHidden
                        )
                    }
                }
                item {
                    TextButton(text = "停止当前拍摄", onClick = { requestStop() }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(title = "使用方法", summary = "熄屏长按音量下键开始，松开停止。亮屏、电源键或音量上键也会停止。单次拍摄最长 1 小时。")
                        BasicComponent(title = "首次使用", summary = "在 LSPosed 启用模块，勾选相机和系统框架并重启。拍照需要相机权限，录像还需要麦克风权限。")
                    }
                    Text("UnlockCameraSnap · ${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.padding(12.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }

    @Composable
    private fun Choice(title: String, summary: String?, checked: Boolean, onClick: () -> Unit) {
        BasicComponent(
            title = title, summary = summary, onClick = onClick,
            modifier = Modifier.semantics { role = Role.RadioButton; selected = checked },
            endActions = { RadioButton(selected = checked, onClick = null) }
        )
    }

    private fun hasPermissions(value: Int) =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            (value != SnapConfig.VIDEO || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

    private fun selectMode(value: Int) {
        if (value == SnapConfig.OFF || hasPermissions(value)) { saveMode(value); return }
        pendingMode = value
        val requested = mutableListOf(Manifest.permission.CAMERA)
        if (value == SnapConfig.VIDEO) requested += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }

    private fun saveMode(value: Int) {
        requestStop()
        SnapConfig.prefs(this).edit().putInt("mode", value).apply()
        notifyConfigChanged()
        refreshState()
    }

    private fun requestStop() {
        try { startService(Intent(this, SnapService::class.java).setAction(SnapConfig.ACTION_STOP)) }
        catch (e: RuntimeException) { Log.w(HookEntry.TAG, "Cannot stop capture", e); toast("停止拍摄失败") }
    }

    private fun notifyConfigChanged() = contentResolver.notifyChange(SnapConfig.URI, null)

    private fun chooseDestination() {
        if (destinationBusy) return
        try {
            val tree = SnapConfig.read(this).saveTree
            directoryPicker.launch(if (tree.isEmpty()) null else Uri.parse(tree))
        } catch (e: RuntimeException) {
            Log.w(HookEntry.TAG, "Cannot open directory picker", e)
            toast("无法打开系统文件夹选择器")
        }
    }

    private fun launcher() = ComponentName(this, SnapConfig.PACKAGE + ".LauncherActivity")

    private fun setIconHidden(value: Boolean) {
        try {
            packageManager.setComponentEnabledSetting(launcher(),
                if (value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)
            toast(if (value) "桌面图标已隐藏" else "桌面图标已恢复")
        } catch (e: RuntimeException) { Log.w(HookEntry.TAG, "Cannot change launcher visibility", e); toast("修改桌面图标失败") }
        refreshState()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun refreshState() {
        val config = SnapConfig.read(this)
        mode = config.mode
        cameraId = config.cameraId
        destination = config.destinationLabel()
        hidden = packageManager.getComponentEnabledSetting(launcher()) in setOf(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
        val prefs = SnapConfig.prefs(this)
        val time = prefs.getLong("system_ready_at", 0)
        connection = if (time == 0L) "等待连接，请启用模块并重启" else "最近连接：${DateFormat.getDateTimeInstance().format(Date(time))}"
        captureStatus = prefs.getString("status", "尚未拍摄") ?: "尚未拍摄"
    }

    private fun loadCameras() {
        val appContext = applicationContext
        settingsIo.execute {
            val options = mutableListOf(CameraOption("", "自动选择后置主摄"))
            try {
                val manager = appContext.getSystemService(CameraManager::class.java)
                manager.cameraIdList.forEach { id ->
                    val facing = when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> "后置"
                        CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                        else -> "外置"
                    }
                    options += CameraOption(id, "$facing · $id")
                }
            } catch (e: Exception) {
                Log.w(HookEntry.TAG, "Cannot enumerate cameras", e)
                SnapConfig.status(appContext, "读取摄像头失败：${e.message}")
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) cameras = options
            }
        }
    }
}
