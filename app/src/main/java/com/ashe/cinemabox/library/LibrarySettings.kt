package com.ashe.cinemabox.library

import android.content.Context

/** Which channels the user marked as libraries; a small id set, so plain preferences suffice. */
class LibrarySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)
    private var accountId = 0L
    private val channelKey get() = "$KEY_CHANNELS-$accountId"
    fun bindAccount(id: Long) {
        accountId = id
        if (prefs.contains(KEY_CHANNELS)) {
            val old = prefs.getStringSet(KEY_CHANNELS, emptySet()).orEmpty().toSet()
            prefs.edit().putStringSet(channelKey, old).remove(KEY_CHANNELS).apply()
        }
    }
    fun useCount(chatId: Long) = prefs.getInt("uses-$accountId-$chatId", 0)
    fun recordUse(chatId: Long) { prefs.edit().putInt("uses-$accountId-$chatId", useCount(chatId) + 1).apply() }

    /** "Identificar canciones automáticamente" in Settings; on by default. */
    var autoIdentify: Boolean
        get() = prefs.getBoolean(KEY_AUTO_IDENTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_IDENTIFY, value).apply()

    var channelIds: Set<Long>
        get() = prefs.getStringSet(channelKey, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet(channelKey, value.map { it.toString() }.toSet()).apply()

    private companion object {
        const val KEY_CHANNELS = "channels"
        const val KEY_AUTO_IDENTIFY = "auto-identify"
    }
}
