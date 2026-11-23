package dev.wimmboo.omni.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class OmniColorPalette(
    val bg: Color,
    val bgSidebar: Color,
    val bgElevated: Color,
    val bgHover: Color,
    val bgActive: Color,
    val bgComposer: Color,
    val bgChip: Color,
    val bgModal: Color,
    val bgCodeBlock: Color,
    val bgCodeHeader: Color,
    val bgInlineCode: Color,
    val bgUserBubble: Color,
    val bgSuggestionChip: Color,
    val bgInputField: Color,
    val border: Color,
    val borderSoft: Color,
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val success: Color,
    val successSoft: Color,
    val agentActive: Color,
    val agentActiveSoft: Color,
    val speed1: Color,
    val speed2: Color,
    val speed3: Color,
    val speed4: Color,
    val statusGood: Color,
    val statusWarning: Color,
    val statusSerious: Color,
    val statusCritical: Color,
    val incognito: Color,
    val incognitoSoft: Color,
    val emptyTitle: Color,
    val sendReadyBg: Color,
    val sendReadyFg: Color,
    val sendIdleBg: Color,
    val toastErrorBg: Color,
    val toastErrorBorder: Color,
    val toastErrorText: Color,
    val toastInfoBg: Color,
    val toastInfoBorder: Color,
    val toastInfoText: Color,
    val isIncognito: Boolean,
)

val OmniDarkPalette = OmniColorPalette(
    bg = Color(0xFF1F1F1F),
    bgSidebar = Color(0xFF1A1A1A),
    bgElevated = Color(0xFF2A2A2A),
    bgHover = Color(0xFF2C2C2C),
    bgActive = Color(0xFF303030),
    bgComposer = Color(0xFF2C2C2C),
    bgChip = Color(0xFF2A2A2A),
    bgModal = Color(0xFF252525),
    bgCodeBlock = Color(0x52000000),
    bgCodeHeader = Color(0x0AFFFFFF),
    bgInlineCode = Color(0x0FFFFFFF),
    bgUserBubble = Color(0x0DFFFFFF),
    bgSuggestionChip = Color(0x0AFFFFFF),
    bgInputField = Color(0x0AFFFFFF),
    border = Color(0x0FFFFFFF),
    borderSoft = Color(0x14FFFFFF),
    borderStrong = Color(0x1FFFFFFF),
    text = Color(0xFFE8E6E3),
    textSecondary = Color(0xFFB0AEA9),
    textMuted = Color(0xFF7A7874),
    textFaint = Color(0xFF5C5A56),
    accent = Color(0xFFD4A27F),
    accentSoft = Color(0x24D4A27F),
    danger = Color(0xFFD47878),
    dangerSoft = Color(0x26D47878),
    success = Color(0xFF7AAD8A),
    successSoft = Color(0x297AAD8A),
    agentActive = Color(0xFF34D399),
    agentActiveSoft = Color(0x1F34D399),
    speed1 = Color(0xFFC69A70),
    speed2 = Color(0xFFDDB595),
    speed3 = Color(0xFFF0D0B6),
    speed4 = Color(0xFFFDEDDC),
    statusGood = Color(0xFF17B317),
    statusWarning = Color(0xFFFAB219),
    statusSerious = Color(0xFFEC835A),
    statusCritical = Color(0xFFD47878),
    incognito = Color(0xFFA89FD4),
    incognitoSoft = Color(0x24A89FD4),
    emptyTitle = Color(0xFFE8E6E3),
    sendReadyBg = Color(0xFFE8E6E3),
    sendReadyFg = Color(0xFF1F1F1F),
    sendIdleBg = Color(0x1AFFFFFF),
    toastErrorBg = Color(0xFF2A2424),
    toastErrorBorder = Color(0x40D47878),
    toastErrorText = Color(0xFFE0A8A8),
    toastInfoBg = Color(0xFF24242A),
    toastInfoBorder = Color(0x409B8EC4),
    toastInfoText = Color(0xFFC4BCE0),
    isIncognito = false,
)

val OmniIncognitoPalette = OmniDarkPalette.copy(
    bg = Color(0xFF18181B),
    bgSidebar = Color(0xFF141416),
    bgElevated = Color(0xFF232326),
    bgHover = Color(0xFF27272A),
    bgActive = Color(0xFF2C2C30),
    bgComposer = Color(0xFF252528),
    bgChip = Color(0xFF232326),
    bgModal = Color(0xFF222226),
    accent = Color(0xFFA89FD4),
    accentSoft = Color(0x24A89FD4),
    emptyTitle = Color(0xFFCFC9E0),
    sendReadyBg = Color(0xFFA89FD4),
    sendReadyFg = Color(0xFF1A1A1E),
    isIncognito = true,
)

object ModeBadgeColors {
    data class Style(val bg: Color, val fg: Color, val border: Color)

    val purple = Style(Color(0x2EA855F7), Color(0xFFD8B4FE), Color(0x59A855F7))
    val blue = Style(Color(0x2E3B82F6), Color(0xFF93C5FD), Color(0x593B82F6))
    val amber = Style(Color(0x2EF59E0B), Color(0xFFFCD34D), Color(0x59F59E0B))
    val emerald = Style(Color(0x2E10B981), Color(0xFF6EE7B7), Color(0x5910B981))
    val pink = Style(Color(0x2EF472B6), Color(0xFFF472B6), Color(0x59F472B6))

    fun forName(name: String?): Style = when (name?.lowercase()) {
        "purple" -> purple
        "blue" -> blue
        "emerald" -> emerald
        "pink" -> pink
        else -> amber
    }
}

val LocalOmniColors = staticCompositionLocalOf { OmniDarkPalette }
