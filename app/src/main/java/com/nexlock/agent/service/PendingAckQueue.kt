package com.nexlock.agent.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingAck(
    val deviceToken: String,
    val commandId: String,
    val status: String,
    val error: String?
)

/**
 * Persists ACKs that failed to send (network down, backend unreachable) so the command's
 * outcome isn't silently lost — the Agent already executed the lock/unlock locally by the
 * time an ACK is attempted, so losing the ACK would leave the backend's record out of sync
 * with the device's real state. RetryWorker flushes this queue once connectivity returns.
 *
 * Heartbeats/telemetry are deliberately NOT queued here — a missed heartbeat is just a stale
 * snapshot, superseded by the next periodic or event-triggered one, nothing to recover.
 */
object PendingAckQueue {
    private const val PREFS_NAME = "nexlock_agent_pending_acks"
    private const val KEY_QUEUE = "queue"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(context: Context, ack: PendingAck) {
        val current = readAll(context)
        current.add(ack)
        writeAll(context, current)
    }

    @Synchronized
    fun getAll(context: Context): List<PendingAck> = readAll(context)

    @Synchronized
    fun remove(context: Context, commandId: String) {
        val remaining = readAll(context).filterNot { it.commandId == commandId }
        writeAll(context, remaining)
    }

    @Synchronized
    fun isEmpty(context: Context): Boolean = readAll(context).isEmpty()

    private fun readAll(context: Context): MutableList<PendingAck> {
        val raw = prefs(context).getString(KEY_QUEUE, null) ?: return mutableListOf()
        val result = mutableListOf<PendingAck>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    PendingAck(
                        deviceToken = obj.getString("deviceToken"),
                        commandId = obj.getString("commandId"),
                        status = obj.getString("status"),
                        error = if (obj.isNull("error")) null else obj.getString("error")
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupt queue state — safer to drop it than get stuck retrying garbage forever.
            return mutableListOf()
        }
        return result
    }

    private fun writeAll(context: Context, items: List<PendingAck>) {
        val arr = JSONArray()
        items.forEach { ack ->
            val obj = JSONObject()
            obj.put("deviceToken", ack.deviceToken)
            obj.put("commandId", ack.commandId)
            obj.put("status", ack.status)
            obj.put("error", ack.error)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_QUEUE, arr.toString()).apply()
    }
}
