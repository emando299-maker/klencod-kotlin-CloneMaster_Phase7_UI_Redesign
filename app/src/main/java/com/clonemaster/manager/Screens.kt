@file:OptIn(ExperimentalMaterial3Api::class)

package com.clonemaster.manager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class StatusMsg(val text: String, val isError: Boolean)
data class StorageInfo(val modified: Long = 0, val imported: Long = 0, val cache: Long = 0) {
    val total: Long get() = modified + imported + cache
}

data class UiState(
    val tab: Int,
    val apps: List<InstalledApp>,
    val loadingApps: Boolean,
    val source: ImportedApkInfo?,
    val auditReport: String?,
    val status: StatusMsg?,
    val busy: Boolean,
    val resultFiles: List<File>,
    val resultBundle: File?,
    val customIcon: ImageBitmap?,
    val useCustomIcon: Boolean,
    val storage: StorageInfo,
    val keyFingerprint: String
)

data class UiActions(
    val onTab: (Int) -> Unit,
    val onPickApp: (InstalledApp) -> Unit,
    val onImportFile: () -> Unit,
    val onCreate: (String, String) -> Unit,
    val onPickIcon: () -> Unit,
    val onUseCustomIcon: (Boolean) -> Unit,
    val onInstall: (List<File>) -> Unit,
    val onExportFile: (File) -> Unit,
    val onExportBundle: (File) -> Unit,
    val onExportSource: (ImportedApkInfo) -> Unit,
    val onAudit: (ImportedApkInfo) -> Unit,
    val onClean: () -> Unit,
    val onResetKey: () -> Unit
)

private val packageRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

// ───────────────────────── root ─────────────────────────

@Composable
fun CloneMasterApp(state: UiState, actions: UiActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF081225),
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp
            ) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color(0xFF69B5FF),
                    indicatorColor = Color(0xFF123C83),
                    unselectedIconColor = Color(0xFF8294B5),
                    unselectedTextColor = Color(0xFF8294B5)
                )
                NavigationBarItem(selected = state.tab == 0, onClick = { actions.onTab(0) }, icon = { Icon(Icons.Default.Apps, null) }, label = { Text("التطبيقات") }, colors = navColors)
                NavigationBarItem(selected = state.tab == 1, onClick = { actions.onTab(1) }, icon = { Icon(Icons.Default.ContentCopy, null) }, label = { Text("النسخ") }, colors = navColors)
                NavigationBarItem(selected = state.tab == 2, onClick = { actions.onTab(2) }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("الإعدادات") }, colors = navColors)
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.tab) {
                0 -> AppsScreen(state, actions)
                1 -> CloneScreen(state, actions)
                else -> SettingsScreen(state, actions)
            }
        }
    }
}

// ───────────────────────── shared pieces ─────────────────────────

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color(0xFFF3F7FF))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9EAFCE))
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFF203B63)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101F36))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusBanner(msg: StatusMsg) {
    val bg = if (msg.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (msg.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(if (msg.isError) Icons.Default.Warning else Icons.Default.CheckCircle, null, tint = fg, modifier = Modifier.size(20.dp))
            Text(msg.text, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun drawableToBitmap(d: Drawable, size: Int = 108): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    d.setBounds(0, 0, size, size)
    d.draw(canvas)
    return bmp
}

@Composable
fun AppIcon(packageName: String, size: Dp) {
    val pm = LocalContext.current.packageManager
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            try { drawableToBitmap(pm.getApplicationIcon(packageName)).asImageBitmap() } catch (_: Exception) { null }
        }
    }
    val shape = RoundedCornerShape(size * 0.24f)
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size).clip(shape), contentScale = ContentScale.Fit)
    } else {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
        }
    }
}

// ───────────────────────── apps tab ─────────────────────────

@Composable
private fun AppsScreen(state: UiState, actions: UiActions) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val filtered = remember(state.apps, query, showSystem) {
        state.apps.filter { (showSystem || !it.isSystem) && (it.label.contains(query, true) || it.packageName.contains(query, true)) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { BrandHeader(onSettings = { actions.onTab(2) }) }
        item { CloneHero(onStart = { actions.onTab(0) }, onImport = actions.onImportFile) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("اختر تطبيقًا للاستنساخ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Muted("أنشئ نسخة مستقلة باسم وأيقونة مختلفين")
                }
                Surface(color = Color(0xFF142C53), shape = RoundedCornerShape(50)) {
                    Text("${filtered.size} تطبيق", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Color(0xFF83BFFF), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text("ابحث عن تطبيق بالاسم أو الحزمة") },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF72B8FF)) }
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("تطبيقات النظام") })
                Spacer(Modifier.weight(1f))
                Muted(if (state.loadingApps) "جارٍ التحميل…" else "التطبيقات المثبتة")
            }
        }
        if (state.loadingApps) {
            item { Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF32A4FF)) } }
        } else if (filtered.isEmpty()) {
            item {
                Panel {
                    Icon(Icons.Default.Search, null, tint = Color(0xFF6FB5FF), modifier = Modifier.size(32.dp))
                    Text("لا توجد نتائج", fontWeight = FontWeight.SemiBold)
                    Muted("جرّب اسمًا آخر أو فعّل عرض تطبيقات النظام.")
                }
            }
        } else {
            items(filtered, key = { it.packageName }) { app -> AppRow(app) { actions.onPickApp(app) } }
        }
    }
}

@Composable
private fun BrandHeader(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(
                Brush.linearGradient(listOf(Color(0xFF08A5FF), Color(0xFF4935E8), Color(0xFF8B36F4)))
            ), contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.ContentCopy, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("Clone", color = Color(0xFFF2F6FF), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("Master", color = Color(0xFF36A4FF), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            }
            Text("استنسخ التطبيقات بسهولة", color = Color(0xFF9EAFCE), style = MaterialTheme.typography.bodySmall)
        }
        Surface(onClick = onSettings, color = Color(0xFF122440), shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Color(0xFF243F68))) {
            Icon(Icons.Default.Settings, "الإعدادات", tint = Color(0xFFB9D4FF), modifier = Modifier.padding(12.dp).size(23.dp))
        }
    }
}

@Composable
private fun CloneHero(onStart: () -> Unit, onImport: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, Color(0xFF235DCC)),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF063E9E), Color(0xFF172D91), Color(0xFF4B19A8)))).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF8EEBFF), modifier = Modifier.size(18.dp))
                        Text("استنسخ أي تطبيق", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    Text("احصل على نسخة مستقلة واستخدمها بحساب آخر.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE1E9FF))
                    Button(
                        onClick = onStart,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF168BFF), contentColor = Color.White),
                        shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("ابدأ الاستنساخ")
                    }
                    TextButton(onClick = onImport) { Icon(Icons.Default.FileOpen, null, tint = Color.White); Spacer(Modifier.width(5.dp)); Text("استيراد APK", color = Color.White) }
                }
                Box(Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x3323C5FF)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF9BE8FF), modifier = Modifier.size(56.dp))
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF1D3558)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1A2E))
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, 46.dp)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (app.isSystem) Muted("نظام")
        }
    }
}

// ───────────────────────── clone tab ─────────────────────────

@Composable
private fun CloneScreen(state: UiState, actions: UiActions) {
    val src = state.source
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("إنشاء نسخة", "عدّل الاسم والحزمة والأيقونة ثم ثبّت النسخة")
        if (src == null) {
            Panel {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                }
                Text("لم يتم اختيار مصدر بعد", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                Muted("اختر تطبيقًا من تبويب التطبيقات، أو افتح ملف APK من جهازك.")
                Button(onClick = { actions.onTab(0) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("اختيار من التطبيقات") }
                OutlinedButton(onClick = actions.onImportFile, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("فتح ملف APK")
                }
            }
        } else {
            CloneForm(src, state, actions)
        }
        state.status?.let { StatusBanner(it) }
        if (state.resultFiles.isNotEmpty()) ResultPanel(state, actions)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CloneForm(src: ImportedApkInfo, state: UiState, actions: UiActions) {
    var name by remember(src.path) { mutableStateOf("${src.appLabel} 2") }
    var pkg by remember(src.path) { mutableStateOf("${src.packageName}.clone") }
    val trimmed = pkg.trim()
    val pkgValid = packageRegex.matches(trimmed)
    val same = trimmed == src.packageName
    val pkgError: String? = when {
        trimmed.isNotEmpty() && !pkgValid -> "صيغة غير صحيحة. مثال: com.example.app.clone"
        same -> "اختر Package Name مختلفًا عن الأصل"
        else -> null
    }
    var showAudit by remember(src.path) { mutableStateOf(false) }

    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(src.packageName, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(src.appLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(src.packageName)
                Muted("الإصدار ${src.versionName} (${src.versionCode})" + if (src.splits > 0) " · base + ${src.splits} split" else " · APK واحد")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { actions.onExportSource(src) }) { Text("تصدير الأصلي") }
            TextButton(onClick = { actions.onAudit(src); showAudit = true }) { Text("فحص البنية") }
            TextButton(onClick = actions.onImportFile) { Text("ملف آخر") }
        }
        if (showAudit && state.auditReport != null) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                Text(state.auditReport, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Panel {
        PanelTitle("إعدادات النسخة")
        OutlinedTextField(
            value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = MaterialTheme.shapes.medium, label = { Text("اسم النسخة (يظهر على الشاشة)") }
        )
        OutlinedTextField(
            value = pkg, onValueChange = { pkg = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = MaterialTheme.shapes.medium, label = { Text("Package Name الجديد") },
            isError = pkgError != null,
            supportingText = { Text(pkgError ?: "يجب أن يختلف عن الأصل") }
        )
        Text("الأيقونة", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val custom = state.customIcon
            if (state.useCustomIcon && custom != null) {
                Image(bitmap = custom, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
            } else {
                AppIcon(src.packageName, 56.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.useCustomIcon, onClick = { actions.onUseCustomIcon(false) }, label = { Text("الأصلية") })
                    FilterChip(
                        selected = state.useCustomIcon,
                        onClick = { if (custom == null) actions.onPickIcon() else actions.onUseCustomIcon(true) },
                        label = { Text("صورة مخصصة") }
                    )
                }
                if (custom != null) TextButton(onClick = actions.onPickIcon) { Text("تغيير الصورة") }
            }
        }
        Button(
            onClick = { actions.onCreate(name.trim(), trimmed) },
            enabled = !state.busy && pkgValid && !same && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp)); Text("جاري إنشاء النسخة…")
            } else {
                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("إنشاء النسخة")
            }
        }
    }
}

@Composable
private fun ResultPanel(state: UiState, actions: UiActions) {
    Panel {
        PanelTitle("النسخة جاهزة")
        val files = state.resultFiles
        Muted(if (files.size > 1) "${files.size} ملفات (base + ${files.size - 1} split) موقّعة بنفس المفتاح" else files.first().name)
        Button(onClick = { actions.onInstall(files) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Default.Android, null); Spacer(Modifier.width(8.dp)); Text("تثبيت النسخة")
        }
        if (files.size == 1) {
            OutlinedButton(onClick = { actions.onExportFile(files.first()) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("تصدير APK")
            }
        }
        state.resultBundle?.let { b ->
            OutlinedButton(onClick = { actions.onExportBundle(b) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("تصدير حزمة .apks")
            }
        }
    }
}

// ───────────────────────── settings tab ─────────────────────────

@Composable
private fun SettingsScreen(state: UiState, actions: UiActions) {
    var confirmClean by remember { mutableStateOf(false) }
    var confirmKey by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("الإعدادات", "التخزين ومفتاح التوقيع")

        Panel {
            PanelTitle("التخزين")
            StorageRow("النسخ المعدلة", state.storage.modified)
            StorageRow("ملفات APK المستوردة", state.storage.imported)
            StorageRow("ملفات مؤقتة", state.storage.cache)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الإجمالي", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(formatSize(state.storage.total), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = { confirmClean = true }, enabled = state.storage.total > 0, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("تنظيف الملفات")
            }
        }

        Panel {
            PanelTitle("مفتاح التوقيع")
            Muted("كل النسخ توقَّع بنفس المفتاح، لذلك يمكن تحديث نسخة مثبّتة بنسخة أحدث بنفس الـ Package Name.")
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.secondary)
                    Text(state.keyFingerprint.ifBlank { "…" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            OutlinedButton(onClick = { confirmKey = true }, modifier = Modifier.fillMaxWidth()) { Text("إنشاء مفتاح جديد") }
        }

        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                PanelTitle("ملاحظات")
            }
            Muted("• المفتاح محفوظ داخل بيانات التطبيق؛ حذف Clone Master أو مسح بياناته يفقده.")
            Muted("• الأيقونة المخصصة تظهر كأيقونة عادية (غير تكيفية).")
            Muted("• بعض التطبيقات تتحقق من التوقيع أو اسم الحزمة وقد لا تعمل كنسخة.")
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text("تنظيف الملفات؟") },
            text = { Text("سيتم حذف النسخ المعدلة وملفات APK المستوردة والمؤقتة. التطبيقات المثبتة ومفتاح التوقيع لن يتأثرا.") },
            confirmButton = { TextButton(onClick = { confirmClean = false; actions.onClean() }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("إلغاء") } }
        )
    }
    if (confirmKey) {
        AlertDialog(
            onDismissRequest = { confirmKey = false },
            title = { Text("إنشاء مفتاح جديد؟") },
            text = { Text("النسخ المثبّتة حاليًا لن تقبل التحديث من نسخ موقّعة بالمفتاح الجديد؛ ستحتاج لحذفها قبل التثبيت.") },
            confirmButton = { TextButton(onClick = { confirmKey = false; actions.onResetKey() }) { Text("إنشاء") } },
            dismissButton = { TextButton(onClick = { confirmKey = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatSize(bytes))
    }
}
