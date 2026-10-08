package com.nexlock.agent.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Silently self-updates the Agent APK via PackageInstaller — a capability only a Device Owner
 * app has (no "Do you want to install this update?" prompt, unlike a normal app update). Driven
 * by HeartbeatWorker: the heartbeat response (see MdmService.processHeartbeat on the backend)
 * tells the agent whether a newer signed build exists and where to download it from — the same
 * release artifact the dealer app's in-app activation flow already fetches via GET /get-app-info.
 *
 * This exists to close a real gap: before this, a new Agent release only ever reached devices
 * enrolled AFTER that release shipped. Anything already enrolled kept running whatever version
 * it was provisioned with forever, with no update path short of full re-enrollment — see the
 * v1.0.23 ADB fix, which an already-enrolled fleet had no way to actually receive.
 */
object AgentSelfUpdater {
    private const val TAG = "AgentSelfUpdater"
    private const val PREFS_NAME = "nexlock_agent_self_update_prefs"
    private const val KEY_LAST_ATTEMPTED_VERSION = "key_last_attempted_version"
    private const val KEY_LAST_ATTEMPTED_AT = "key_last_attempted_at"

    // Don't hammer a failing download/install every ~60s heartbeat — retry the SAME version at
    // most this often. A genuinely newer version released in the meantime is a different value
    // and bypasses this cooldown entirely (see the check below).
    private const val RETRY_COOLDOWN_MS = 4 * 60 * 60 * 1000L

    const val ACTION_INSTALL_RESULT = "com.nexlock.agent.SELF_UPDATE_INSTALL_RESULT"

    suspend fun checkAndUpdate(context: Context, latestVersion: String, downloadUrl: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAttempted = prefs.getString(KEY_LAST_ATTEMPTED_VERSION, null)
        val lastAttemptedAt = prefs.getLong(KEY_LAST_ATTEMPTED_AT, 0)
        if (lastAttempted == latestVersion && System.currentTimeMillis() - lastAttemptedAt < RETRY_COOLDOWN_MS) {
            Log.i(TAG, "Already attempted $latestVersion recently — skipping until cooldown passes")
            return
        }
        prefs.edit()
            .putString(KEY_LAST_ATTEMPTED_VERSION, latestVersion)
            .putLong(KEY_LAST_ATTEMPTED_AT, System.currentTimeMillis())
            .apply()

        try {
            val apkBytes = withContext(Dispatchers.IO) { downloadApk(downloadUrl) }
            if (apkBytes == null || apkBytes.isEmpty()) {
                Log.e(TAG, "Download failed or empty for $latestVersion")
                return
            }
            installSilently(context, apkBytes)
            Log.i(TAG, "Self-update install session committed for $latestVersion (${apkBytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Self-update failed for $latestVersion", e)
        }
    }

    private fun downloadApk(url: String): ByteArray? {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Download HTTP ${response.code} for $url")
                return null
            }
            return response.body?.bytes()
        }
    }

    /**
     * PackageInstaller.Session.commit() normally shows a system "install this update?" dialog —
     * Device Owner apps are exempted from that for both installing other apps and, as here,
     * updating themselves. If a given OEM build doesn't honor that exemption, the result comes
     * back as STATUS_PENDING_USER_ACTION instead of STATUS_SUCCESS (see InstallResultReceiver) —
     * a degraded-but-not-broken outcome (occasional visible prompt instead of fully silent),
     * not a crash.
     */
    private fun installSilently(context: Context, apkBytes: ByteArray) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        try {
            session.openWrite("nexlock-agent-update", 0, apkBytes.size.toLong()).use { out ->
                out.write(apkBytes)
                session.fsync(out)
            }
            val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
        } finally {
            session.close()
        }
    }

    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_INSTALL_RESULT) return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Self-update installed successfully")
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Silent-install exemption didn't apply on this device/OS build — the system
                    // install prompt is sitting unactioned behind this intent. Not pursued here:
                    // launching it would surface an unexpected dialog to whoever's holding the
                    // phone, which is worse than just trying again next heartbeat.
                    Log.e(TAG, "Self-update needs user confirmation on this device — silent install exemption did not apply")
                }
                else -> Log.e(TAG, "Self-update install failed: status=$status message=$message")
            }
        }
    }
}
