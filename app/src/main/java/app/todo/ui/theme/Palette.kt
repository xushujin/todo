package app.todo.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import app.todo.R

/**
 * A neumorphic palette. Both the light and the dark shadows are explicit colours,
 * because soft UI does not use black drop shadows: the light source is top-left,
 * so the surface casts a light highlight up-left and a tinted shadow down-right.
 */
data class TodoPalette(
    val id: String,
    @param:StringRes val labelRes: Int,
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val accent: Color,
    val onAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val shadowLight: Color,
    val shadowDark: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
)

object Palettes {

    val cloud = TodoPalette(
        id = "cloud",
        labelRes = R.string.theme_cloud,
        isDark = false,
        background = Color(0xFFE6E9EF),
        surface = Color(0xFFEDF0F5),
        surfaceSunken = Color(0xFFDCE1E9),
        accent = Color(0xFF4C5BD4),
        onAccent = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF2C3140),
        textSecondary = Color(0xFF6B7385),
        shadowLight = Color(0xFFFFFFFF),
        shadowDark = Color(0xFFA9B1C3),
        danger = Color(0xFFD1495B),
        success = Color(0xFF2E9E6B),
        warning = Color(0xFFD98324),
    )

    val mint = TodoPalette(
        id = "mint",
        labelRes = R.string.theme_mint,
        isDark = false,
        background = Color(0xFFE3EFE8),
        surface = Color(0xFFEAF4EE),
        surfaceSunken = Color(0xFFD5E5DB),
        accent = Color(0xFF2F8F6B),
        onAccent = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF1F3A2E),
        textSecondary = Color(0xFF5D7A6C),
        shadowLight = Color(0xFFF9FFFB),
        shadowDark = Color(0xFFA8C4B5),
        danger = Color(0xFFC1464F),
        success = Color(0xFF2E9E6B),
        warning = Color(0xFFC98325),
    )

    val sand = TodoPalette(
        id = "sand",
        labelRes = R.string.theme_sand,
        isDark = false,
        background = Color(0xFFF0E7DC),
        surface = Color(0xFFF6EEE5),
        surfaceSunken = Color(0xFFE4D9CB),
        accent = Color(0xFFB4693A),
        onAccent = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF3A2E24),
        textSecondary = Color(0xFF7A6A5A),
        shadowLight = Color(0xFFFFFAF4),
        shadowDark = Color(0xFFC6B49E),
        danger = Color(0xFFB4443C),
        success = Color(0xFF5C8A4A),
        warning = Color(0xFFC98A25),
    )

    val lavender = TodoPalette(
        id = "lavender",
        labelRes = R.string.theme_lavender,
        isDark = false,
        background = Color(0xFFE8E4F3),
        surface = Color(0xFFEFECF9),
        surfaceSunken = Color(0xFFDCD7EC),
        accent = Color(0xFF6B5BD4),
        onAccent = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF2F2A45),
        textSecondary = Color(0xFF6E688A),
        shadowLight = Color(0xFFFCFAFF),
        shadowDark = Color(0xFFB6AFCE),
        danger = Color(0xFFC1445F),
        success = Color(0xFF3D8F7A),
        warning = Color(0xFFC78328),
    )

    val midnight = TodoPalette(
        id = "midnight",
        labelRes = R.string.theme_midnight,
        isDark = true,
        background = Color(0xFF23262E),
        surface = Color(0xFF2A2E38),
        surfaceSunken = Color(0xFF1D2027),
        accent = Color(0xFF7C8CFF),
        onAccent = Color(0xFF0E1116),
        textPrimary = Color(0xFFE8EBF2),
        textSecondary = Color(0xFF9AA3B5),
        shadowLight = Color(0xFF353B47),
        shadowDark = Color(0xFF12141A),
        danger = Color(0xFFFF6B7A),
        success = Color(0xFF4FD1A1),
        warning = Color(0xFFFFB86B),
    )

    val all: List<TodoPalette> = listOf(cloud, mint, sand, lavender, midnight)

    fun byId(id: String?): TodoPalette = all.firstOrNull { it.id == id } ?: cloud
}
