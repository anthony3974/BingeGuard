package com.tony3974.bingeguard.monitor

/** The usage events the tracker cares about (mapped from UsageEvents.Event by the service). */
enum class TrackerEvent {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    ACTIVITY_STOPPED,
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP,
}

/**
 * Turns a stream of usage events into "which package is on screen now". Plain Kotlin, no
 * Android types, so it's unit-tested.
 *
 * It keeps the set of resumed activities in the order they resumed; the foreground package is
 * the owner of the most recently resumed one. Replaying a suffix of events it has already seen
 * gives the same state, so the service can query with an overlap and not worry about duplicates.
 */
class ForegroundTracker {
    /** "package/class" -> package, oldest resume first. */
    private val resumed = LinkedHashMap<String, String>()
    private var screenOn = true
    private var locked = false

    fun onEvent(event: TrackerEvent, packageName: String?, className: String?) {
        when (event) {
            TrackerEvent.ACTIVITY_RESUMED -> {
                if (packageName == null) return
                val key = key(packageName, className)
                resumed.remove(key)
                resumed[key] = packageName
            }
            TrackerEvent.ACTIVITY_PAUSED, TrackerEvent.ACTIVITY_STOPPED -> {
                if (packageName == null) return
                resumed.remove(key(packageName, className))
            }
            TrackerEvent.SCREEN_INTERACTIVE -> screenOn = true
            TrackerEvent.SCREEN_NON_INTERACTIVE -> screenOn = false
            TrackerEvent.KEYGUARD_SHOWN -> locked = true
            TrackerEvent.KEYGUARD_HIDDEN -> locked = false
            TrackerEvent.DEVICE_SHUTDOWN, TrackerEvent.DEVICE_STARTUP -> {
                resumed.clear()
                screenOn = true
                locked = false
            }
        }
    }

    /** The package on screen, or null when the screen is off or locked or nothing is resumed. */
    val foregroundPackage: String?
        get() = if (!screenOn || locked) null else resumed.values.lastOrNull()

    private fun key(packageName: String, className: String?) = "$packageName/${className.orEmpty()}"
}
