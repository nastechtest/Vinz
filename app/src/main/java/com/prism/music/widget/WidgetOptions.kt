package com.prism.music.widget

import android.content.Context
import kotlinx.serialization.Serializable

/** A widget's background (1.7.7): as its style draws it, a color of yours, the cover's color, or see-through. */
enum class WidgetBackground(val label: String) { DESIGN("As designed"), COLOR("Your color"), COVER("The cover's color"), CLEAR("See-through") }

/** The widget's words: as its style has them, or always white or black. */
enum class WidgetText(val label: String) { AUTO("As designed"), WHITE("White"), BLACK("Black") }

/**
 * One placed widget's own settings (1.7.7, W1): two Prism widgets on a home screen can look different. Stored per
 * appWidgetId; a widget without any looks as its style was designed, as before 1.7.7.
 */
@Serializable
data class WidgetOptions(
    val background: WidgetBackground = WidgetBackground.DESIGN,
    /** [WidgetBackground.COLOR]'s color. */
    val color: Int = 0xFF1B1B22.toInt(),
    /** How solid the background is (0.2 … 1), for Your color and the cover's color. */
    val opacity: Float = 1f,
    val text: WidgetText = WidgetText.AUTO,
    val cover: Boolean = true,
    val like: Boolean = true,
    val skips: Boolean = true,
    val progress: Boolean = true,
) {
    val isDefault: Boolean get() = this == WidgetOptions()
}

/** Where each widget's [WidgetOptions] are kept ("widget_options" prefs, one JSON per id). */
object WidgetOptionsStore {
    private fun prefs(context: Context) = context.getSharedPreferences("widget_options", Context.MODE_PRIVATE)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun get(context: Context, id: Int): WidgetOptions =
        prefs(context).getString(id.toString(), null)?.let { runCatching { json.decodeFromString(WidgetOptions.serializer(), it) }.getOrNull() }
            ?: WidgetOptions()

    fun set(context: Context, id: Int, o: WidgetOptions) {
        prefs(context).edit().apply { if (o.isDefault) remove(id.toString()) else putString(id.toString(), json.encodeToString(WidgetOptions.serializer(), o)) }.apply()
    }

    /** The widgets taken off the home screen forget their settings. */
    fun remove(context: Context, ids: IntArray) {
        prefs(context).edit().apply { ids.forEach { remove(it.toString()) } }.apply()
    }
}
