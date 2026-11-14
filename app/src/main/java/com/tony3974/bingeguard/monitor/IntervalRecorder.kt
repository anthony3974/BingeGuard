package com.tony3974.bingeguard.monitor

import com.tony3974.bingeguard.data.UsageDao
import com.tony3974.bingeguard.data.UsageInterval

/**
 * Writes foreground time for tracked apps to Room as intervals. The open interval's end is
 * saved every [SAVE_EVERY_MS], so a killed process loses at most that much.
 */
class IntervalRecorder(private val dao: UsageDao) {
    data class Open(val id: Long, val packageName: String, val startMs: Long, var savedEndMs: Long)

    var open: Open? = null
        private set

    /** [packageName] is the tracked app that may be on screen now, or null to record nothing. */
    suspend fun update(packageName: String?, nowMs: Long) {
        val current = open
        if (current != null && current.packageName != packageName) close(nowMs)
        if (packageName == null) return
        val stillOpen = open
        if (stillOpen == null) {
            val id = dao.insert(UsageInterval(packageName = packageName, startMs = nowMs, endMs = nowMs))
            open = Open(id, packageName, nowMs, nowMs)
        } else if (nowMs - stillOpen.savedEndMs >= SAVE_EVERY_MS) {
            dao.setEnd(stillOpen.id, nowMs)
            stillOpen.savedEndMs = nowMs
        }
    }

    suspend fun close(nowMs: Long) {
        val current = open ?: return
        dao.setEnd(current.id, nowMs)
        open = null
    }

    companion object {
        const val SAVE_EVERY_MS = 5_000L
    }
}
