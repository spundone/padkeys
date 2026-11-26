package dev.padkeys

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates: checks the latest GitHub release, downloads its APK and hands it to Android's installer.
 * The only network traffic PadKeys makes, only from the settings screen (never the keyboard), and only to
 * api.github.com and GitHub's release download host.
 * The APK must be the same package, a higher version and signed with the same key as the installed app,
 * and must match the SHA-256 digest GitHub publishes for the asset.
 */
object Updater {
    private const val TAG = "PadKeys"
    private const val API = "https://api.github.com/repos/spundone/padkeys/releases/latest"
    private const val ACTION_INSTALLED = "dev.padkeys.UPDATE_STATUS"
    const val SOURCE = "GitHub releases"

    private val main = Handler(Looper.getMainLooper())
    private var apkUrl: String? = null
    private var apkDigest: String? = null
    private var apkVersion: String? = null
    private var apkNotes = ""
    @Volatile private var busy = false

    private fun post(s: UpdateState) = main.post { Updates.state = s }

    private fun currentVersion(ctx: Context) =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Debug builds have their own package (.debug), so release APKs can't update them. */
    private fun enabled(ctx: Context) = !BuildConfig.DEBUG

    /** On app start: at most once a day, and only if the user hasn't turned it off. */
    fun autoCheck(activity: Activity) {
        if (!enabled(activity) || !Prefs.autoUpdateCheck) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < 20 * 3600_000L) return
        check(activity, manual = false)
    }

    fun check(activity: Activity, manual: Boolean) {
        val ctx = activity.applicationContext
        if (!enabled(ctx)) { if (manual) post(UpdateState.Failed("Development builds don't update themselves.")); return }
        if (busy) return
        networkProblem(ctx)?.let { if (manual) post(it); return }
        busy = true
        if (manual) post(UpdateState.Checking)
        Thread {
            try {
                val json = JSONObject(get(API))
                Prefs.lastUpdateCheck = System.currentTimeMillis()
                val tag = json.getString("tag_name")
                val version = tag.removePrefix("v")
                val asset = json.getJSONArray("assets").let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.getString("name") == "PadKeys-$tag.apk" }
                        ?: (0 until arr.length()).map { arr.getJSONObject(it) }
                            .firstOrNull { it.getString("name").endsWith(".apk") }
                }
                if (!Updates.newer(version, currentVersion(ctx)) || asset == null) {
                    post(if (manual) UpdateState.UpToDate else UpdateState.Idle)
                } else {
                    apkUrl = asset.getString("browser_download_url")
                    apkDigest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                    apkVersion = version
                    val notes = json.optString("body").trim()
                    apkNotes = notes
                    if (!manual && Prefs.skippedUpdate == version) post(UpdateState.Idle)
                    else post(UpdateState.Available(version, notes))
                }
            } catch (e: Exception) {
                // Log.w hides UnknownHostException traces, so log the exception itself too.
                Log.w(TAG, "update check failed: $e", e)
                post(if (manual) UpdateState.Failed(reason(e)) else UpdateState.Idle)
            } finally { busy = false }
        }.start()
    }

    /** Download the APK found by [check], verify it, and open Android's install prompt. */
    fun start(activity: Activity) {
        val url = apkUrl ?: return check(activity, manual = true)
        val version = apkVersion ?: return
        val ctx = activity.applicationContext
        if (busy) return
        busy = true
        post(UpdateState.Downloading(version, 0f))
        Thread {
            try {
                val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val apk = File(dir, "PadKeys-$version.apk")
                download(url, apk) { post(UpdateState.Downloading(version, it)) }
                verify(ctx, apk)
                post(UpdateState.Ready(version))
                install(ctx, apk)
            } catch (e: Exception) {
                Log.w(TAG, "update download failed: $e", e)
                post(UpdateState.Failed(
                    if (e is SecurityException || e is IllegalStateException) e.message ?: "The update couldn't be downloaded."
                    else reason(e)
                ))
            } finally { busy = false }
        }.start()
    }

    /**
     * Android reports no default network to an app whose network access is turned off (App info › data usage ›
     * "Allow network access", or restricted networking mode). Some ROMs leave that off for apps installed without
     * the INTERNET permission, even after an update adds it (PadKeys before 1.2.0 had none).
     */
    /** Why PadKeys has no network: null when it has one, else the state to show. */
    private fun networkProblem(ctx: Context): UpdateState.Failed? {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        return try {
            if (cm.activeNetwork != null) return null
            @Suppress("DEPRECATION")
            val phoneOnline = cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            if (phoneOnline) UpdateState.Failed(
                "Network access is turned off for PadKeys. Tap to open App info, then under mobile data / network " +
                    "turn on Allow network access and Wi-Fi.",
                openAppInfo = true,
            ) else UpdateState.Failed("You're offline. Connect to the internet and try again.")
        } catch (_: Exception) { null }
    }

    private fun reason(e: Exception): String = when (e) {
        is java.net.UnknownHostException ->
            "Couldn't look up GitHub. If you're online, the phone may be blocking PadKeys's network access " +
                "(App info › PadKeys › data usage / network)."
        is java.net.SocketTimeoutException -> "GitHub didn't answer in time. Try again."
        is javax.net.ssl.SSLException -> "Couldn't make a secure connection to GitHub (${e.javaClass.simpleName})."
        is IllegalStateException -> "GitHub answered with an error (${e.message}). Try again later."
        is org.json.JSONException -> "GitHub's answer couldn't be read. Try again later."
        else -> "Couldn't reach GitHub: ${e.javaClass.simpleName}${e.message?.let { " ($it)" } ?: ""}"
    }

    fun skip(version: String) {
        Prefs.skippedUpdate = version
        Updates.state = UpdateState.Idle
    }

    // ------------------------------------------------------------------ network

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "PadKeys-Android")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun download(url: String, to: File, progress: (Float) -> Unit) {
        val c = open(url)
        c.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (c.responseCode != 200) throw IllegalStateException("Download failed (HTTP ${c.responseCode}).")
            val total = c.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L; var lastShown = 0L
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        if (done - lastShown > 256 * 1024) {
                            lastShown = done
                            progress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = apkDigest
            if (expected != null && !expected.equals(hex, ignoreCase = true)) {
                to.delete(); throw SecurityException("The download was corrupted (checksum mismatch). Try again.")
            }
        } finally { c.disconnect() }
    }

    // ------------------------------------------------------------------ verify + install

    @Suppress("DEPRECATION")
    private fun signers(pm: PackageManager, info: android.content.pm.PackageInfo?): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else info?.signatures
        return sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun verify(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw SecurityException("The downloaded file isn't a valid app.")
        if (archive.packageName != ctx.packageName) throw SecurityException("The download is a different app.")
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) throw IllegalStateException("You already have this version.")
        // Signing certificate history includes the current key, so any overlap means the same publisher.
        if (signers(pm, archive).intersect(signers(pm, installed)).isEmpty()) {
            throw SecurityException("The download isn't signed with PadKeys's key, so it wasn't installed.")
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            registerStatusReceiver(ctx)
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(ACTION_INSTALLED).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
        }
    }

    private var receiverOn = false
    private fun registerStatusReceiver(ctx: Context) {
        if (receiverOn) return
        receiverOn = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else i.getParcelableExtra(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                            try { c.startActivity(it) } catch (e: Exception) { Log.w(TAG, "install prompt", e) }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> Updates.state = UpdateState.Idle // the app restarts
                    else -> {
                        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        Log.w(TAG, "install failed: $msg")
                        val v = apkVersion
                        // Cancelled (or a permission screen was backed out of): keep the offer so the banner's
                        // Update button can retry, instead of hiding it.
                        Updates.state = if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) == PackageInstaller.STATUS_FAILURE_ABORTED && v != null)
                            UpdateState.Available(v, apkNotes)
                        else UpdateState.Failed("Android couldn't install the update${msg?.let { ": $it" } ?: "."}")
                    }
                }
            }
        }
        val f = IntentFilter(ACTION_INSTALLED)
        // Not exported on every API level: the status carries an Intent we launch, so only our own
        // PendingIntent (sent by the installer on our behalf) may reach it.
        androidx.core.content.ContextCompat.registerReceiver(ctx, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
