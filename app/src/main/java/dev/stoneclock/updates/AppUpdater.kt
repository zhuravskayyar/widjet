package dev.stoneclock.updates

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class AppRelease(val versionCode: Long, val versionName: String, val apkUrl: String,
    val sha256: String, val size: Long) {
    companion object {
        fun parse(json: String): AppRelease {
            val value = JSONObject(json)
            val release = AppRelease(value.getLong("versionCode"), value.getString("versionName"),
                value.getString("apkUrl"), value.getString("sha256"), value.getLong("size"))
            val url = URL(release.apkUrl)
            require(url.protocol == "https" && url.host == "github.com" &&
                url.path.startsWith("/zhuravskayyar/widjet/releases/download/") && url.path.endsWith("/stone-clock.apk"))
            require(release.versionCode > 0 && release.size in 1..150_000_000 && release.sha256.matches(Regex("[0-9a-f]{64}")))
            return release
        }
    }
}

internal enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY }
internal data class AppUpdateState(val currentCode: Long, val currentName: String, val latest: AppRelease? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE, val progress: Int = 0, val checked: Boolean = false,
    val error: String? = null) {
    val available get() = latest?.versionCode?.let { it > currentCode } == true
}

/** Public release metadata, verified APKs and an explicit Android installation confirmation. */
internal class AppUpdater private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val installed = app.packageManager.getPackageInfo(app.packageName, 0)
    private val directory = File(app.cacheDir, "updates")
    private val cached = runCatching { preferences.getString("release", null)?.let(AppRelease::parse) }.getOrNull()
    private val mutableState = MutableStateFlow(AppUpdateState(versionCode(installed), installed.versionName ?: "",
        latest = cached, phase = if (cached != null && cached.versionCode > versionCode(installed) && apkFile(cached).isFile)
            UpdatePhase.READY else UpdatePhase.IDLE))
    val state: StateFlow<AppUpdateState> = mutableState
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    fun checkForUpdates(force: Boolean = false) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        val age = System.currentTimeMillis() - preferences.getLong("checked_at", 0)
        if (!force && age in 0 until 60 * 60 * 1_000L) return
        checkJob = scope.launch(start = CoroutineStart.LAZY) {
            val previous = mutableState.value.phase
            mutableState.update { it.copy(phase = UpdatePhase.CHECKING, error = null) }
            try {
                val json = withContext(Dispatchers.IO) { fetchMetadata() }
                val latest = json?.let(AppRelease::parse)
                preferences.edit().putLong("checked_at", System.currentTimeMillis()).putString("release", json).apply()
                mutableState.update { it.copy(latest = latest, checked = true, phase =
                    if (latest != null && latest.versionCode > it.currentCode && apkFile(latest).isFile) UpdatePhase.READY else UpdatePhase.IDLE) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(phase = previous) }; throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(phase = previous, error = "Не вдалося перевірити оновлення. Перевір інтернет і спробуй ще раз.") }
            }
        }.also { it.start() }
    }

    fun downloadUpdate() {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        val release = mutableState.value.latest?.takeIf { mutableState.value.available } ?: return
        downloadJob = scope.launch(start = CoroutineStart.LAZY) {
            mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, error = null) }
            try {
                withContext(Dispatchers.IO) { download(release) }
                mutableState.update { it.copy(phase = UpdatePhase.READY, progress = 100) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(phase = UpdatePhase.IDLE) }; throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.IDLE,
                    error = "Не вдалося отримати перевірений APK. Спробуй завантажити оновлення ще раз.") }
            }
        }.also { it.start() }
    }

    suspend fun installationIntent(): Intent = withContext(Dispatchers.IO) {
        val release = checkNotNull(mutableState.value.latest)
        check(release.versionCode > mutableState.value.currentCode)
        val file = apkFile(release)
        verify(file, release)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Stone Clock update", uri) }
    }

    private fun fetchMetadata(): String? {
        val connection = connection(METADATA_URL)
        try {
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200 && connection.url.protocol == "https")
            return connection.inputStream.use { stream ->
                val bytes = stream.readBytesLimited(64_000)
                bytes.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private fun download(release: AppRelease) {
        check(directory.isDirectory || directory.mkdirs())
        check(directory.usableSpace > release.size + 10_000_000)
        val partial = File(directory, "${release.versionCode}.part")
        val connection = connection(release.apkUrl)
        try {
            check(connection.responseCode == 200 && connection.url.protocol == "https")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32_768)
                    var total = 0L
                    var reported = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= release.size)
                        output.write(buffer, 0, count)
                        val progress = (total * 100 / release.size).toInt()
                        if (progress != reported) {
                            reported = progress
                            mutableState.update { it.copy(progress = progress) }
                        }
                    }
                }
            }
            verify(partial, release)
            val target = apkFile(release)
            if (target.exists()) check(target.delete())
            check(partial.renameTo(target))
            directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
        } finally { partial.delete(); connection.disconnect() }
    }

    @Suppress("DEPRECATION")
    private fun verify(file: File, release: AppRelease) {
        check(file.length() == release.size)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32_768)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = checkNotNull(app.packageManager.getPackageArchiveInfo(file.path, flags))
        check(archive.packageName == app.packageName && versionCode(archive) == release.versionCode && archive.versionName == release.versionName)
        val current = app.packageManager.getPackageInfo(app.packageName, flags)
        check(signers(archive) == signers(current) && signers(current).isNotEmpty())
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun apkFile(release: AppRelease) = File(directory, "stone-clock-${release.versionCode}.apk")
    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "StoneClock/${mutableState.value.currentName}")
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = read(buffer); if (count < 0) break
            check(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val EXTRA_OPEN_UPDATES = "open_updates"
        const val REPOSITORY_URL = "https://github.com/zhuravskayyar/widjet"
        private const val METADATA_URL = "$REPOSITORY_URL/releases/latest/download/update.json"
        @SuppressLint("StaticFieldLeak") @Volatile private var instance: AppUpdater? = null
        fun get(context: Context): AppUpdater = instance ?: synchronized(this) {
            instance ?: AppUpdater(context).also { instance = it }
        }
        @Suppress("DEPRECATION")
        private fun versionCode(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
}
