package com.clonemaster.manager

import android.content.Context
import android.content.pm.PackageInfo
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

/**
 * Experimental on-device APK patcher.
 *
 * - Supports single APKs AND split APKs (base + config splits): every file gets the new package
 *   name in its binary manifest and all files are signed with the SAME temporary key, which
 *   Android requires for a split set.
 * - Only the manifest `package` attribute and package-owned identifiers (provider authorities,
 *   custom permissions) are renamed. Component class names are made fully-qualified with the
 *   ORIGINAL package, because the classes inside DEX keep their original names.
 */
object ApkCloneEngine {
    data class Result(
        val outputs: List<File>,       // outputs[0] is always the base APK
        val bundle: File?,             // .apks archive when the app uses splits
        val originalPackage: String,
        val targetPackage: String,
        val labelApplied: Boolean,
        val iconApplied: Boolean
    ) {
        val splitCount: Int get() = outputs.size - 1
    }

    fun createClone(context: Context, input: File, targetPackage: String, newLabel: String? = null, iconPng: File? = null): Result {
        require(input.isFile && input.extension.equals("apk", true)) { "اختَر ملف APK صالحًا" }
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(targetPackage)) { "Package Name غير صالح" }
        val pm = context.packageManager
        val packageInfo = pm.getPackageArchiveInfo(input.absolutePath, 0)
            ?: throw IllegalArgumentException("تعذرت قراءة معلومات APK")
        val original = packageInfo.packageName ?: throw IllegalArgumentException("لم يتم العثور على Package Name")
        require(original != targetPackage) { "اختَر Package Name مختلفًا عن الأصل" }

        // If the app is installed as base + splits, collect every split so the clone is complete.
        val installed: PackageInfo? = try { pm.getPackageInfo(original, 0) } catch (_: Exception) { null }
        val splitFiles = installed?.applicationInfo?.splitSourceDirs?.filterNotNull()?.map { File(it) }.orEmpty()
        if (splitFiles.isNotEmpty()) {
            if (versionCodeOf(installed) != versionCodeOf(packageInfo)) {
                throw IllegalArgumentException(
                    "ملف APK المختار بإصدار مختلف عن التطبيق المثبت، وملفات split لا تتطابق معه. " +
                    "استخرج APK الأساسي من نفس التطبيق المثبت (من قائمة التطبيقات) ثم اختره هنا."
                )
            }
            val unreadable = splitFiles.filter { !it.canRead() }
            if (unreadable.isNotEmpty()) throw IllegalArgumentException("تعذر قراءة ملفات split: ${unreadable.joinToString { it.name }}")
        }

        val minSdk = packageInfo.applicationInfo?.minSdkVersion ?: 1
        if (minSdk > android.os.Build.VERSION.SDK_INT) {
            throw IllegalArgumentException("هذا APK يتطلب Android API $minSdk بينما جهازك API ${android.os.Build.VERSION.SDK_INT}.")
        }

        val root = File(context.filesDir, "modified_apks").apply { mkdirs() }
        val safe = targetPackage.replace('.', '_')
        val stamp = System.currentTimeMillis()
        val runDir = File(root, "${safe}_$stamp")
        val created = ArrayList<File>()
        try {
            val signer = SigningKeys.signerConfig(context)
            val iconBytes = iconPng?.takeIf { it.isFile }?.readBytes()
            var baseIconIds: Set<Int> = emptySet()
            var labelApplied = false
            var iconApplied = false
            // (output name, source file)
            val jobs: List<Pair<String, File>> = if (splitFiles.isEmpty()) {
                listOf("${safe}_$stamp.apk" to input)
            } else {
                runDir.mkdirs()
                listOf("base.apk" to input) + splitFiles.map { it.name to it }
            }
            val outputs = ArrayList<File>()
            for ((index, job) in jobs.withIndex()) {
                val (name, source) = job
                val parent = if (splitFiles.isEmpty()) root else runDir
                val unsigned = File(context.cacheDir, "unsigned_${stamp}_${outputs.size}.apk")
                val signed = File(parent, name)
                created += signed
                try {
                    val info = rewriteZip(source, unsigned, original, targetPackage, newLabel, iconBytes, baseIconIds)
                    if (index == 0) { baseIconIds = info.iconIds; labelApplied = info.labelApplied }
                    if (info.iconApplied) iconApplied = true
                    signApk(unsigned, signed, signer)
                } finally {
                    unsigned.delete()
                }
                outputs += signed
            }

            // Verify the base file is recognised with the new package name.
            val verifyInfo = pm.getPackageArchiveInfo(outputs.first().absolutePath, 0)
                ?: throw IllegalStateException("تم إنشاء الملف لكن Android لم يتعرف عليه كـ APK")
            val actual = verifyInfo.packageName ?: ""
            if (actual != targetPackage) throw IllegalStateException("فشل التحقق: المعرّف داخل الملف هو $actual وليس $targetPackage")

            val bundle = if (splitFiles.isNotEmpty()) {
                File(root, "${safe}_$stamp.apks").also { created += it; makeBundle(outputs, it) }
            } else null
            return Result(outputs, bundle, original, targetPackage, labelApplied, iconApplied)
        } catch (e: Exception) {
            created.forEach { it.delete() }
            runDir.deleteRecursively()
            throw e
        }
    }

    private fun versionCodeOf(info: PackageInfo?): Long {
        if (info == null) return -1
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    /** Plain zip of the signed APKs; installable with split installers (SAI etc.). */
    private fun makeBundle(files: List<File>, output: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private fun alignmentFor(name: String) = if (name.endsWith(".so")) 16384 else 4

    /** Extra field used only to pad a STORED entry so its data starts on an aligned offset. */
    private fun paddingExtra(length: Int): ByteArray {
        val extra = ByteArray(length)
        extra[0] = 0x35; extra[1] = 0xD9.toByte()          // header id 0xD935
        val data = length - 4
        extra[2] = (data and 0xff).toByte(); extra[3] = ((data shr 8) and 0xff).toByte()
        return extra
    }

    internal data class RewriteInfo(val labelApplied: Boolean, val iconIds: Set<Int>, val iconApplied: Boolean)

    private const val ICON_PATH = "res/cm_icon.png"

    private fun startStoredEntry(out: ZipOutputStream, counter: CountingOutputStream, name: String, size: Long, crc: Long) {
        val newEntry = ZipEntry(name)
        newEntry.method = ZipEntry.STORED
        newEntry.size = size
        newEntry.compressedSize = size
        newEntry.crc = crc
        val align = alignmentFor(name)
        val base = counter.count + 30 + name.toByteArray(Charsets.UTF_8).size
        var pad = ((align - base % align) % align).toInt()
        if (pad in 1..3) pad += align
        if (pad > 0) newEntry.extra = paddingExtra(pad)
        out.putNextEntry(newEntry)
    }

    private fun rewriteZip(
        input: File, output: File, oldPackage: String, newPackage: String,
        newLabel: String?, iconBytes: ByteArray?, baseIconIds: Set<Int>
    ): RewriteInfo {
        return ZipFile(input).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalArgumentException("AndroidManifest.xml غير موجود في ${input.name}")
            val manifest = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val patch = BinaryManifestEditor.patch(manifest, oldPackage, newPackage, newLabel)

            // Icon: point the icon resource entries to a PNG we add to the archive.
            val iconIds = if (patch.iconIds.isNotEmpty()) patch.iconIds else baseIconIds
            var arscPatched: ByteArray? = null
            var arscStored = false
            if (iconBytes != null && iconIds.isNotEmpty()) {
                zip.getEntry("resources.arsc")?.let { e ->
                    val bytes = zip.getInputStream(e).use { it.readBytes() }
                    arscPatched = ArscIconPatcher.patch(bytes, iconIds, ICON_PATH)
                    arscStored = e.method == ZipEntry.STORED
                }
            }

            var found = false
            val counter = CountingOutputStream(BufferedOutputStream(FileOutputStream(output), 1 shl 16))
            ZipOutputStream(counter).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (isSignatureFile(entry.name)) continue
                    val patchedArsc = arscPatched
                    when {
                        entry.name == "AndroidManifest.xml" -> {
                            val newEntry = ZipEntry(entry.name)
                            newEntry.method = ZipEntry.DEFLATED
                            out.putNextEntry(newEntry)
                            out.write(patch.bytes)
                            found = true
                        }
                        entry.name == "resources.arsc" && patchedArsc != null -> {
                            if (arscStored) {
                                val crc = CRC32().apply { update(patchedArsc) }.value
                                startStoredEntry(out, counter, entry.name, patchedArsc.size.toLong(), crc)
                            } else {
                                out.putNextEntry(ZipEntry(entry.name))
                            }
                            out.write(patchedArsc)
                        }
                        entry.isDirectory -> out.putNextEntry(ZipEntry(entry.name))
                        entry.method == ZipEntry.STORED -> {
                            // Keep STORED entries uncompressed and aligned (resources.arsc, native libs).
                            // Android 11+ refuses apps whose resources.arsc is compressed/unaligned.
                            startStoredEntry(out, counter, entry.name, entry.size, entry.crc)
                            zip.getInputStream(entry).use { it.copyTo(out) }
                        }
                        else -> {
                            out.putNextEntry(ZipEntry(entry.name))
                            zip.getInputStream(entry).use { it.copyTo(out) }
                        }
                    }
                    out.closeEntry()
                }
                if (arscPatched != null && iconBytes != null) {
                    out.putNextEntry(ZipEntry(ICON_PATH))
                    out.write(iconBytes)
                    out.closeEntry()
                }
            }
            if (!found) throw IllegalStateException("لم يتم تحديث AndroidManifest.xml")
            RewriteInfo(patch.labelApplied, patch.iconIds, arscPatched != null)
        }
    }

    private fun isSignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        val leaf = upper.substringAfterLast('/')
        return leaf == "MANIFEST.MF" || leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA") || leaf.endsWith(".EC")
    }

    private fun signApk(input: File, output: File, signerConfig: ApkSigner.SignerConfig) {
        output.delete()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}


/** Reads strings from a binary string pool chunk without touching it. */
internal class PoolReader(private val pool: ByteArray) {
    val headerSize = StringPools.u16(pool, 2)
    val stringCount = StringPools.i32(pool, 8)
    val styleCount = StringPools.i32(pool, 12)
    val flags = StringPools.i32(pool, 16)
    val stringsStart = StringPools.i32(pool, 20)
    val stylesStart = StringPools.i32(pool, 24)
    val utf8 = (flags and 0x100) != 0
    private val cache = HashMap<Int, String>()

    init {
        if (pool.size < 28 || headerSize < 28 || stringCount < 0 || styleCount < 0 || stringsStart < headerSize || stringsStart > pool.size) {
            throw IllegalArgumentException("رؤوس String Pool غير صالحة")
        }
        if (headerSize + 4L * (stringCount + styleCount) > pool.size) throw IllegalArgumentException("جدول String Pool غير صالح")
    }

    operator fun get(index: Int): String {
        if (index < 0 || index >= stringCount) return ""
        return cache.getOrPut(index) {
            StringPools.decode(pool, stringsStart + StringPools.i32(pool, headerSize + index * 4), utf8)
        }
    }
}

internal object StringPools {
    /** Returns (new pool chunk, index of the first appended string). Existing data is untouched. */
    fun append(pool: ByteArray, strings: List<String>): Pair<ByteArray, Int> {
        val r = PoolReader(pool)
        if (strings.isEmpty()) return pool to r.stringCount
        val stringsEnd = if (r.stylesStart > 0) r.stylesStart else pool.size
        if (stringsEnd < r.stringsStart || stringsEnd > pool.size) throw IllegalArgumentException("حدود النصوص غير صالحة")
        val data = ByteArrayOutputStream()
        data.write(pool.copyOfRange(r.stringsStart, stringsEnd))
        while (data.size() % 4 != 0) data.write(0)
        val newOffsets = IntArray(strings.size)
        strings.forEachIndexed { i, s -> newOffsets[i] = data.size(); data.write(encode(s, r.utf8)) }
        while (data.size() % 4 != 0) data.write(0)
        val dataBytes = data.toByteArray()
        val styleBytes = if (r.stylesStart > 0) pool.copyOfRange(r.stylesStart, pool.size) else ByteArray(0)

        val total = r.stringCount + strings.size
        val newStringsStart = r.headerSize + 4 * (total + r.styleCount)
        val newStylesStart = if (r.stylesStart > 0 && r.styleCount > 0) newStringsStart + dataBytes.size else 0
        val out = ByteArray(newStringsStart + dataBytes.size + styleBytes.size)
        pool.copyInto(out, 0, 0, r.headerSize)
        putI32(out, 4, out.size)
        putI32(out, 8, total)
        putI32(out, 20, newStringsStart)
        putI32(out, 24, newStylesStart)
        var pos = r.headerSize
        for (i in 0 until r.stringCount) { putI32(out, pos, i32(pool, r.headerSize + i * 4)); pos += 4 }
        for (o in newOffsets) { putI32(out, pos, o); pos += 4 }
        for (i in 0 until r.styleCount) { putI32(out, pos, i32(pool, r.headerSize + 4 * r.stringCount + i * 4)); pos += 4 }
        dataBytes.copyInto(out, newStringsStart)
        styleBytes.copyInto(out, newStringsStart + dataBytes.size)
        return out to r.stringCount
    }

    fun decode(data: ByteArray, offset: Int, utf8: Boolean): String {
        if (offset !in data.indices) throw IllegalArgumentException("String Pool offset غير صالح")
        return if (utf8) {
            var p = offset
            p += readLength8(data, p).second
            val (byteLength, used) = readLength8(data, p); p += used
            if (p + byteLength > data.size) throw IllegalArgumentException("نص UTF-8 غير مكتمل")
            String(data, p, byteLength, Charsets.UTF_8)
        } else {
            val (length, used) = readLength16(data, offset)
            val p = offset + used
            if (p + length * 2 > data.size) throw IllegalArgumentException("نص UTF-16 غير مكتمل")
            String(data, p, length * 2, Charsets.UTF_16LE)
        }
    }

    private fun encode(value: String, utf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeLength8(out, value.length)
            writeLength8(out, bytes.size)
            out.write(bytes); out.write(0)
        } else {
            writeLength16(out, value.length)
            out.write(value.toByteArray(Charsets.UTF_16LE)); out.write(0); out.write(0)
        }
        return out.toByteArray()
    }

    private fun readLength8(b: ByteArray, p: Int): Pair<Int, Int> {
        val first = b[p].toInt() and 0xff
        return if ((first and 0x80) != 0) (((first and 0x7f) shl 8) or (b[p + 1].toInt() and 0xff)) to 2 else first to 1
    }
    private fun readLength16(b: ByteArray, p: Int): Pair<Int, Int> {
        val first = u16(b, p)
        return if ((first and 0x8000) != 0) (((first and 0x7fff) shl 16) or u16(b, p + 2)) to 4 else first to 2
    }
    private fun writeLength8(out: ByteArrayOutputStream, value: Int) {
        if (value > 0x7f) { out.write(((value shr 8) or 0x80) and 0xff); out.write(value and 0xff) } else out.write(value)
    }
    private fun writeLength16(out: ByteArrayOutputStream, value: Int) {
        if (value > 0x7fff) {
            val high = (value shr 16) or 0x8000
            out.write(high and 0xff); out.write((high shr 8) and 0xff)
            out.write(value and 0xff); out.write((value shr 8) and 0xff)
        } else { out.write(value and 0xff); out.write((value shr 8) and 0xff) }
    }
    fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)
    fun i32(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
    fun putI32(b: ByteArray, p: Int, value: Int) { ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value) }
}

internal class ManifestPatch(val bytes: ByteArray, val labelApplied: Boolean, val iconIds: Set<Int>)

/**
 * Minimal Android binary XML editor. It never rewrites existing strings: new strings are appended
 * to the string pool and only the specific attributes that need a new value are re-pointed.
 */
internal object BinaryManifestEditor {
    private const val RES_XML = 0x0003
    private const val RES_STRING_POOL = 0x0001
    private const val RES_START_ELEMENT = 0x0102
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val LAUNCHER = "android.intent.category.LAUNCHER"

    private val componentElements = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
    private val permissionElements = setOf("permission", "uses-permission", "uses-permission-sdk-23", "permission-group", "permission-tree")
    private val classRefAttrs = setOf("targetActivity", "backupAgent", "manageSpaceActivity", "parentActivityName", "appComponentFactory")
    private val permissionRefAttrs = setOf("permission", "readPermission", "writePermission")

    fun patch(xml: ByteArray, oldPackage: String, newPackage: String, newLabel: String?): ManifestPatch {
        if (xml.size < 8 || StringPools.u16(xml, 0) != RES_XML) throw IllegalArgumentException("Manifest ليس بصيغة Android الثنائية المتوقعة")
        val rootSize = minOf(StringPools.i32(xml, 4), xml.size)
        if (rootSize < 8) throw IllegalArgumentException("حجم Manifest غير صالح")

        var poolOffset = -1
        var poolSize = 0
        var offset = 8
        while (offset + 8 <= rootSize) {
            val type = StringPools.u16(xml, offset)
            val size = StringPools.i32(xml, offset + 4)
            if (size < 8 || offset + size > rootSize) throw IllegalArgumentException("بنية Manifest غير سليمة")
            if (type == RES_STRING_POOL) { poolOffset = offset; poolSize = size; break }
            offset += size
        }
        if (poolOffset < 0) throw IllegalArgumentException("لم يتم العثور على String Pool داخل Manifest")

        val pool = xml.copyOfRange(poolOffset, poolOffset + poolSize)
        val reader = PoolReader(pool)
        val stringCount = reader.stringCount

        val appended = ArrayList<String>()
        val appendedIndex = HashMap<String, Int>()
        fun intern(value: String): Int = appendedIndex.getOrPut(value) { appended += value; stringCount + appended.size - 1 }

        fun qualify(value: String): String = when {
            value.startsWith(".") -> oldPackage + value
            value.isNotEmpty() && !value.contains('.') -> "$oldPackage.$value"
            else -> value
        }
        fun renamePrefix(value: String): String = when {
            value == oldPackage -> newPackage
            value.startsWith("$oldPackage.") -> newPackage + value.substring(oldPackage.length)
            else -> value
        }

        val out = xml.copyOf()
        var packageChanged = false
        var labelApplied = false
        val iconIds = LinkedHashSet<Int>()
        var appLabelType = -1
        var appLabelData = 0
        var curLabelAttr = -1
        val curIconRefs = ArrayList<Int>()

        fun applyLabel(a: Int) {
            val label = newLabel ?: return
            val idx = intern(label)
            StringPools.putI32(out, a + 8, idx)
            out[a + 15] = TYPE_STRING.toByte()
            StringPools.putI32(out, a + 16, idx)
            labelApplied = true
        }

        offset = poolOffset + poolSize
        while (offset + 8 <= rootSize) {
            val type = StringPools.u16(xml, offset)
            val size = StringPools.i32(xml, offset + 4)
            if (size < 8 || offset + size > rootSize) throw IllegalArgumentException("بنية Manifest غير سليمة")
            if (type == RES_START_ELEMENT && size >= 36) {
                val element = reader[StringPools.i32(xml, offset + 20)]
                val attrStart = StringPools.u16(xml, offset + 24)
                val attrSize = StringPools.u16(xml, offset + 26)
                val attrCount = StringPools.u16(xml, offset + 28)
                if (element in componentElements && element != "application") { curLabelAttr = -1; curIconRefs.clear() }
                for (i in 0 until attrCount) {
                    val a = offset + 16 + attrStart + i * attrSize
                    if (a + 20 > offset + size) break
                    val dataType = xml[a + 15].toInt() and 0xff
                    val attr = reader[StringPools.i32(xml, a + 4)]
                    val data = StringPools.i32(xml, a + 16)

                    // Provider authorities are globally unique on Android. Handle this attribute
                    // BEFORE the TYPE_STRING filter: some APK toolchains leave a usable raw string
                    // while the typed value is represented differently. Always give every clone
                    // its own namespace, including authorities that do not start with oldPackage.
                    if (element == "provider" && attr == "authorities") {
                        val rawIndex = StringPools.i32(xml, a + 8)
                        val sourceValue = when {
                            rawIndex >= 0 && rawIndex < reader.stringCount -> reader[rawIndex]
                            dataType == TYPE_STRING && data >= 0 && data < reader.stringCount -> reader[data]
                            else -> ""
                        }
                        if (sourceValue.isNotEmpty()) {
                            val rewritten = sourceValue.split(';').joinToString(";") { authority ->
                                val trimmed = authority.trim()
                                if (trimmed.isEmpty()) trimmed else {
                                    val digestInput = "$oldPackage|$trimmed"
                                    val digest = MessageDigest.getInstance("SHA-256")
                                        .digest(digestInput.toByteArray(Charsets.UTF_8))
                                        .take(10).joinToString("") { b -> "%02x".format(b) }
                                    "$newPackage.cmprovider.$digest"
                                }
                            }
                            val idx = intern(rewritten)
                            StringPools.putI32(out, a + 8, idx) // raw string value
                            out[a + 15] = TYPE_STRING.toByte()
                            StringPools.putI32(out, a + 16, idx) // typed string value
                        }
                        continue
                    }

                    if (attr == "label" && element in componentElements) {
                        if (element == "application") {
                            appLabelType = dataType; appLabelData = data
                            applyLabel(a)
                        } else {
                            curLabelAttr = a
                            if (appLabelType == dataType && appLabelData == data) applyLabel(a)
                        }
                    }
                    if ((attr == "icon" || attr == "roundIcon") && dataType == TYPE_REFERENCE && element in componentElements) {
                        if (element == "application") iconIds += data else curIconRefs += data
                    }
                    if (dataType != TYPE_STRING) continue
                    val value = reader[data]

                    if (element == "category" && attr == "name" && value == LAUNCHER) {
                        if (curLabelAttr >= 0) applyLabel(curLabelAttr)
                        iconIds += curIconRefs
                        continue
                    }
                    val replacement: String = when {
                        element == "manifest" && attr == "package" -> { packageChanged = true; newPackage }
                        attr == "name" && element in componentElements -> qualify(value)
                        attr in classRefAttrs && element in componentElements -> qualify(value)
                        attr == "name" && element in permissionElements -> renamePrefix(value)
                        attr in permissionRefAttrs -> renamePrefix(value)
                        (attr == "taskAffinity" || attr == "process") && element in componentElements -> renamePrefix(value)
                        else -> value
                    }
                    if (replacement != value) {
                        val idx = intern(replacement)
                        StringPools.putI32(out, a + 8, idx)    // raw value
                        StringPools.putI32(out, a + 16, idx)   // typed data
                    }
                }
            }
            offset += size
        }
        if (!packageChanged) throw IllegalArgumentException("لم يتم العثور على سمة package في Manifest؛ ربما الملف غير قياسي")

        val (newPool, _) = StringPools.append(pool, appended)
        val result = ByteArray(out.size - poolSize + newPool.size)
        out.copyInto(result, 0, 0, poolOffset)
        newPool.copyInto(result, poolOffset)
        out.copyInto(result, poolOffset + newPool.size, poolOffset + poolSize, out.size)
        StringPools.putI32(result, 4, rootSize - poolSize + newPool.size)
        return ManifestPatch(result, labelApplied, iconIds)
    }
}

/**
 * Re-points the file path of icon resources (inside resources.arsc) to a PNG we add to the APK.
 * Works for every configuration (density/anydpi) of the given resource ids.
 */
internal object ArscIconPatcher {
    private const val RES_TABLE = 0x0002
    private const val RES_STRING_POOL = 0x0001
    private const val RES_PACKAGE = 0x0200
    private const val RES_TYPE = 0x0201
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03

    /** Returns the patched table, or null when none of the ids is a plain file resource. */
    fun patch(arsc: ByteArray, ids: Set<Int>, newPath: String): ByteArray? {
        if (arsc.size < 12 || StringPools.u16(arsc, 0) != RES_TABLE) return null
        val headerSize = StringPools.u16(arsc, 2)
        val total = minOf(StringPools.i32(arsc, 4), arsc.size)
        if (headerSize + 8 > total || StringPools.u16(arsc, headerSize) != RES_STRING_POOL) return null
        val poolOff = headerSize
        val poolSize = StringPools.i32(arsc, poolOff + 4)
        if (poolSize < 28 || poolOff + poolSize > total) return null

        val targets = LinkedHashSet<Int>()
        val seen = HashSet<Int>()
        var wanted: Set<Int> = ids
        var depth = 0
        while (wanted.isNotEmpty() && depth < 4) {
            val next = HashSet<Int>()
            var c = poolOff + poolSize
            while (c + 8 <= total) {
                val t = StringPools.u16(arsc, c)
                val s = StringPools.i32(arsc, c + 4)
                if (s < 8 || c + s > total) break
                if (t == RES_PACKAGE) scanPackage(arsc, c, s, wanted, targets, next)
                c += s
            }
            seen += wanted
            wanted = next - seen
            depth++
        }
        if (targets.isEmpty()) return null

        val pool = arsc.copyOfRange(poolOff, poolOff + poolSize)
        val (newPool, firstIndex) = StringPools.append(pool, listOf(newPath))
        val out = arsc.copyOf()
        for (t in targets) {
            out[t + 3] = TYPE_STRING.toByte()
            StringPools.putI32(out, t + 4, firstIndex)
        }
        val result = ByteArray(out.size - poolSize + newPool.size)
        out.copyInto(result, 0, 0, poolOff)
        newPool.copyInto(result, poolOff)
        out.copyInto(result, poolOff + newPool.size, poolOff + poolSize, out.size)
        StringPools.putI32(result, 4, total - poolSize + newPool.size)
        return result
    }

    private fun scanPackage(arsc: ByteArray, pkgOff: Int, pkgSize: Int, wanted: Set<Int>, targets: MutableSet<Int>, next: MutableSet<Int>) {
        val hs = StringPools.u16(arsc, pkgOff + 2)
        val pkgId = StringPools.i32(arsc, pkgOff + 8)
        var c = pkgOff + hs
        val end = pkgOff + pkgSize
        while (c + 8 <= end) {
            val t = StringPools.u16(arsc, c)
            val s = StringPools.i32(arsc, c + 4)
            if (s < 8 || c + s > end) break
            if (t == RES_TYPE) scanType(arsc, c, pkgId, wanted, targets, next)
            c += s
        }
    }

    private fun scanType(arsc: ByteArray, c: Int, pkgId: Int, wanted: Set<Int>, targets: MutableSet<Int>, next: MutableSet<Int>) {
        val hs = StringPools.u16(arsc, c + 2)
        val chunkSize = StringPools.i32(arsc, c + 4)
        val typeId = arsc[c + 8].toInt() and 0xff
        val flags = arsc[c + 9].toInt() and 0xff
        val entryCount = StringPools.i32(arsc, c + 12)
        val entriesStart = StringPools.i32(arsc, c + 16)
        val chunkEnd = c + chunkSize
        for (id in wanted) {
            if ((id ushr 24) != pkgId || ((id ushr 16) and 0xff) != typeId) continue
            val index = id and 0xffff
            val rel = entryOffset(arsc, c + hs, flags, entryCount, index, chunkEnd) ?: continue
            val entry = c + entriesStart + rel
            if (entry + 16 > chunkEnd) continue
            val entrySize = StringPools.u16(arsc, entry)
            val entryFlags = StringPools.u16(arsc, entry + 2)
            if ((entryFlags and 0x1) != 0 || (entryFlags and 0x8) != 0) continue   // complex or compact
            val value = entry + entrySize
            if (value + 8 > chunkEnd) continue
            val dataType = arsc[value + 3].toInt() and 0xff
            val data = StringPools.i32(arsc, value + 4)
            when (dataType) {
                TYPE_STRING -> targets += value
                TYPE_REFERENCE -> next += data
            }
        }
    }

    private fun entryOffset(arsc: ByteArray, table: Int, flags: Int, count: Int, index: Int, limit: Int): Int? {
        if (count < 0) return null
        return when {
            (flags and 0x01) != 0 -> {                                   // sparse: (idx u16, offset/4 u16)
                if (table + 4L * count > limit) return null
                for (i in 0 until count) {
                    val p = table + i * 4
                    if (StringPools.u16(arsc, p) == index) return StringPools.u16(arsc, p + 2) * 4
                }
                null
            }
            (flags and 0x02) != 0 -> {                                   // offsets are u16 (value*4)
                if (index >= count || table + 2L * count > limit) return null
                val v = StringPools.u16(arsc, table + index * 2)
                if (v == 0xFFFF) null else v * 4
            }
            else -> {
                if (index >= count || table + 4L * count > limit) return null
                val v = StringPools.i32(arsc, table + index * 4)
                if (v == -1) null else v
            }
        }
    }
}
