package com.clonemaster.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class InstalledApp(val label: String, val packageName: String, val isSystem: Boolean)
data class ImportedApkInfo(
    val fileName: String, val path: String, val appLabel: String, val packageName: String,
    val versionName: String, val versionCode: Long, val splits: Int = 0
)

class MainActivity : ComponentActivity() {
    private var tab by mutableIntStateOf(0)
    private var apps by mutableStateOf<List<InstalledApp>>(emptyList())
    private var loadingApps by mutableStateOf(true)
    private var source by mutableStateOf<ImportedApkInfo?>(null)
    private var auditReport by mutableStateOf<String?>(null)
    private var status by mutableStateOf<StatusMsg?>(null)
    private var busy by mutableStateOf(false)
    private var resultFiles by mutableStateOf<List<File>>(emptyList())
    private var resultBundle by mutableStateOf<File?>(null)
    private var customIcon by mutableStateOf<ImageBitmap?>(null)
    private var useCustomIcon by mutableStateOf(false)
    private var storage by mutableStateOf(StorageInfo())
    private var keyFingerprint by mutableStateOf("")
    private var pendingExport: File? = null

    private val iconFile get() = File(filesDir, "custom_icon.png")

    // ───────── install result ─────────
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        legacyConfirmIntent(intent)
                    }
                    confirm?.let { startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> status = StatusMsg("تم تثبيت النسخة بنجاح.", false)
                else -> status = StatusMsg("فشل التثبيت: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "سبب غير معروف"}", true)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyConfirmIntent(intent: Intent): Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)

    // ───────── pickers ─────────
    private fun copyPendingExport(uri: Uri?) {
        val file = pendingExport
        if (uri != null && file != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                Toast.makeText(this, "تم التصدير", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل التصدير: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        pendingExport = null
    }
    private val exportApkPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { copyPendingExport(it) }
    private val exportBundlePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { copyPendingExport(it) }

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val dir = File(filesDir, "imported_apks").apply { mkdirs() }
                    val out = File(dir, "import_${System.currentTimeMillis()}.apk")
                    contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
                        ?: throw IllegalStateException("تعذر قراءة الملف")
                    try { readSource(out) } catch (e: Exception) { out.delete(); throw e }
                }
                updateSource(info)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "تعذر فحص APK: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { makeIcon(uri) }
                customIcon = bmp.asImageBitmap()
                useCustomIcon = true
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "تعذر قراءة الصورة: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ───────── lifecycle ─────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val filter = IntentFilter(ApkInstaller.ACTION_RESULT)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(installReceiver, filter)

        setContent {
            CloneMasterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CloneMasterApp(
                        state = UiState(
                            tab, apps, loadingApps, source, auditReport, status, busy, resultFiles, resultBundle,
                            customIcon, useCustomIcon, storage, keyFingerprint
                        ),
                        actions = UiActions(
                            onTab = { tab = it },
                            onPickApp = { pickInstalledApp(it) },
                            onImportFile = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                            onCreate = { name, pkg -> createModifiedApk(name, pkg) },
                            onPickIcon = { iconPicker.launch("image/*") },
                            onUseCustomIcon = { useCustomIcon = it },
                            onInstall = { installModified(it) },
                            onExportFile = { pendingExport = it; exportApkPicker.launch(it.name) },
                            onExportBundle = { pendingExport = it; exportBundlePicker.launch(it.name) },
                            onExportSource = { info -> pendingExport = File(info.path); exportApkPicker.launch("${safeFileName(info.appLabel)}.apk") },
                            onAudit = { auditReport = auditApk(it) },
                            onClean = { cleanStorage() },
                            onResetKey = { resetKey() }
                        )
                    )
                }
            }
        }

        loadApps()
        refreshStorage()
        lifecycleScope.launch {
            keyFingerprint = withContext(Dispatchers.IO) { try { SigningKeys.fingerprint(this@MainActivity) } catch (_: Exception) { "" } }
            val saved = iconFile
            if (saved.isFile) {
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(saved.absolutePath) }
                if (bmp != null) customIcon = bmp.asImageBitmap()
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(installReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ───────── data loading ─────────
    private fun loadApps() {
        lifecycleScope.launch {
            loadingApps = true
            apps = withContext(Dispatchers.IO) { loadLaunchableApps() }
            loadingApps = false
        }
    }

    private fun loadLaunchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).mapNotNull { resolve ->
            val info = resolve.activityInfo?.applicationInfo ?: return@mapNotNull null
            InstalledApp(packageManager.getApplicationLabel(info).toString(), info.packageName, (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun readSource(file: File): ImportedApkInfo {
        val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IllegalArgumentException("الملف ليس APK صالحًا أو لا يمكن قراءته")
        info.applicationInfo?.sourceDir = file.absolutePath
        info.applicationInfo?.publicSourceDir = file.absolutePath
        val label = info.applicationInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: info.packageName
        val vc = versionCodeOf(info)
        val splits = try {
            val installed = packageManager.getPackageInfo(info.packageName, 0)
            if (versionCodeOf(installed) == vc) installed.applicationInfo?.splitSourceDirs?.size ?: 0 else 0
        } catch (_: Exception) { 0 }
        return ImportedApkInfo(file.name, file.absolutePath, label, info.packageName ?: "", info.versionName ?: "غير معروف", vc, splits)
    }

    private fun updateSource(info: ImportedApkInfo) {
        val old = source
        if (old != null && old.path != info.path) File(old.path).delete()
        source = info
        auditReport = null
        status = null
        resultFiles = emptyList()
        resultBundle = null
        tab = 1
        refreshStorage()
    }

    private fun pickInstalledApp(app: InstalledApp) {
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val appInfo = packageManager.getApplicationInfo(app.packageName, 0)
                    val dir = File(filesDir, "imported_apks").apply { mkdirs() }
                    val out = File(dir, "import_${System.currentTimeMillis()}.apk")
                    FileInputStream(appInfo.sourceDir).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
                    try { readSource(out) } catch (e: Exception) { out.delete(); throw e }
                }
                updateSource(info)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "تعذر تجهيز التطبيق: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ───────── icon ─────────
    private fun makeIcon(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("صورة غير صالحة")
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
        FileOutputStream(iconFile).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return scaled
    }

    // ───────── clone / install ─────────
    private fun createModifiedApk(name: String, targetPackage: String) {
        val item = source ?: return
        busy = true
        status = null
        resultFiles = emptyList()
        resultBundle = null
        val icon = if (useCustomIcon && iconFile.isFile) iconFile else null
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApkCloneEngine.createClone(this@MainActivity, File(item.path), targetPackage, name.ifBlank { null }, icon)
                }
                resultFiles = result.outputs
                resultBundle = result.bundle
                val notes = ArrayList<String>()
                if (name.isNotBlank() && !result.labelApplied) notes += "تعذر تغيير الاسم (التطبيق لا يعرّف اسمًا قابلًا للتعديل)."
                if (icon != null && !result.iconApplied) notes += "تعذر تغيير الأيقونة (بنية الموارد غير مدعومة)."
                val head = if (result.splitCount > 0) "تم إنشاء حزمة كاملة (base + ${result.splitCount} split) وتوقيعها." else "تم إنشاء APK وتوقيعه."
                status = StatusMsg((listOf(head, "المعرّف: ${result.targetPackage}") + notes).joinToString(" "), false)
            } catch (e: Exception) {
                status = StatusMsg("فشل التعديل: ${e.message ?: "خطأ غير معروف"}", true)
            } finally {
                busy = false
                refreshStorage()
            }
        }
    }

    private fun installModified(files: List<File>) {
        try {
            status = StatusMsg("جاري تجهيز التثبيت… سيطلب Android تأكيدك.", false)
            ApkInstaller.install(this, files)
        } catch (e: Exception) {
            status = StatusMsg("فشل التثبيت: ${e.message ?: "خطأ غير معروف"}", true)
        }
    }

    // ───────── storage / key ─────────
    private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    private fun refreshStorage() {
        lifecycleScope.launch {
            storage = withContext(Dispatchers.IO) {
                StorageInfo(
                    modified = dirSize(File(filesDir, "modified_apks")),
                    imported = dirSize(File(filesDir, "imported_apks")) + dirSize(File(filesDir, "extracted_apks")),
                    cache = dirSize(cacheDir)
                )
            }
        }
    }

    private fun cleanStorage() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                File(filesDir, "modified_apks").deleteRecursively()
                File(filesDir, "imported_apks").deleteRecursively()
                File(filesDir, "extracted_apks").deleteRecursively()
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            source = null
            auditReport = null
            resultFiles = emptyList()
            resultBundle = null
            status = StatusMsg("تم تنظيف الملفات.", false)
            refreshStorage()
        }
    }

    private fun resetKey() {
        lifecycleScope.launch {
            try {
                keyFingerprint = withContext(Dispatchers.IO) { SigningKeys.reset(this@MainActivity); SigningKeys.fingerprint(this@MainActivity) }
                Toast.makeText(this@MainActivity, "تم إنشاء مفتاح جديد", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "تعذر إنشاء المفتاح: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ───────── audit ─────────
    private fun auditApk(item: ImportedApkInfo): String {
        return try {
            ZipFile(item.path).use { z ->
                val names = z.entries().asSequence().map { it.name }.toList()
                val dex = names.filter { it.matches(Regex("classes(\\d*)\\.dex")) }
                val nativeLibs = names.count { it.startsWith("lib/") && it.endsWith(".so") }
                val v1 = names.count { it.startsWith("META-INF/") && (it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true) || it.endsWith(".SF", true)) }
                buildString {
                    appendLine("AndroidManifest.xml: ${if (names.contains("AndroidManifest.xml")) "موجود" else "غير موجود"}")
                    appendLine("resources.arsc: ${if (names.contains("resources.arsc")) "موجود" else "غير موجود"}")
                    appendLine("ملفات DEX: ${dex.size}")
                    appendLine("مكتبات native: $nativeLibs")
                    appendLine("ملفات توقيع V1: $v1")
                    append("ملفات split: ${item.splits}")
                }
            }
        } catch (e: Exception) {
            "تعذر فحص الأرشيف: ${e.message ?: "خطأ غير معروف"}"
        }
    }

    private fun safeFileName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(50).ifBlank { "app" }
}