package com.fold18.frame

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** "light", "dark" or "transparent". */
    var background: String
        get() = prefs.getString("background", DEFAULT_BACKGROUND)!!
        set(v) = prefs.edit().putString("background", v).apply()

    /** Outer margin as a fraction of the screenshot's short side. */
    var padding: Float
        get() = prefs.getFloat("padding", DEFAULT_PADDING)
        set(v) = prefs.edit().putFloat("padding", v).apply()

    var shadow: Boolean
        get() = prefs.getBoolean("shadow", DEFAULT_SHADOW)
        set(v) = prefs.edit().putBoolean("shadow", v).apply()

    var radiusScale: Float
        get() = prefs.getFloat("radiusScale", DEFAULT_RADIUS_SCALE)
        set(v) = prefs.edit().putFloat("radiusScale", v).apply()

    var finish: Finish
        get() = Finish.of(prefs.getString("finish", null))
        set(v) = prefs.edit().putString("finish", v.name).apply()

    /** Post a "加壳" prompt whenever a new screenshot appears. */
    var watchScreenshots: Boolean
        get() = prefs.getBoolean("watchScreenshots", false)
        set(v) = prefs.edit().putBoolean("watchScreenshots", v).apply()

    /** After a share goes out, move the original screenshots to the system trash. */
    var trashAfterShare: Boolean
        get() = prefs.getBoolean("trashAfterShare", true)
        set(v) = prefs.edit().putBoolean("trashAfterShare", v).apply()

    /** Same, after 保存到相册. */
    var trashAfterSave: Boolean
        get() = prefs.getBoolean("trashAfterSave", false)
        set(v) = prefs.edit().putBoolean("trashAfterSave", v).apply()

    /** Highest MediaStore id already prompted for, so each screenshot is offered once. */
    var lastPromptedId: Long
        get() = prefs.getLong("lastPromptedId", 0)
        set(v) = prefs.edit().putLong("lastPromptedId", v).apply()

    /** Back to the out-of-the-box look; entry-point switches are left alone. */
    fun resetAppearance() {
        prefs.edit()
            .remove("background")
            .remove("padding")
            .remove("shadow")
            .remove("radiusScale")
            .remove("finish")
            .apply()
    }

    fun frameOptions() = FrameOptions(
        background = when (background) {
            "dark" -> 0xFF1B1C1E.toInt()
            "transparent" -> null
            else -> 0xFFF4F5F5.toInt()
        },
        padding = padding,
        shadow = shadow,
        radiusScale = radiusScale,
        finish = finish,
    )

    companion object {
        private const val DEFAULT_BACKGROUND = "light"
        private const val DEFAULT_PADDING = 0.12f
        private const val DEFAULT_SHADOW = true
        private const val DEFAULT_RADIUS_SCALE = 1f
    }
}
