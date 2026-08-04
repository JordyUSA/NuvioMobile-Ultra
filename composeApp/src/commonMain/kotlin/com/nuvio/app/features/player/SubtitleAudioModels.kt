package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_track_number
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

data class AudioTrack(
    val index: Int,
    val id: String,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
)

data class SubtitleTrack(
    val index: Int,
    val id: String,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
    val isForced: Boolean = false,
)

data class AddonSubtitle(
    val id: String,
    val url: String,
    val language: String,
    val display: String,
    val addonName: String? = null,
    val isSelected: Boolean = false,
)

enum class SubtitleTab {
    BuiltIn,
    Addons,
    Sync,
    Style,
}

enum class AddonSubtitleStartupMode {
    FAST_STARTUP,
    PREFERRED_ONLY,
    ALL_SUBTITLES,
}

enum class SubtitleFontFamily {
    System,
    SansSerif,
    Serif,
    Monospace,
    Rounded,
    Custom,
}

const val SUBTITLE_DELAY_MIN_MS = -600_000
const val SUBTITLE_DELAY_MAX_MS = 600_000
const val SUBTITLE_DELAY_STEP_MS = 100

/**
 * How subtitle glyphs are separated from the picture behind them.
 *
 * This replaces a plain outline on/off switch. Outline is the safe default but it is not always
 * the right one — over bright, busy footage a drop shadow reads better, and on a dark grade a
 * raised edge is cleaner than a hard black ring.
 */
enum class SubtitleEdgeStyle {
    None,
    Outline,
    DropShadow,
    OutlineAndShadow,
    Raised,
    Depressed,
    ;

    /** Whether this style paints anything using the outline colour. */
    val drawsEdge: Boolean
        get() = this != None
}

data class SubtitleStyleState(
    val textColor: Color = Color.White,
    val backgroundColor: Color = Color.Transparent,
    val outlineColor: Color = Color.Black,
    val edgeStyle: SubtitleEdgeStyle = SubtitleEdgeStyle.Outline,
    val outlineWidth: Int = 2,
    val bold: Boolean = false,
    val fontSizeSp: Int = 18,
    val fontFamily: SubtitleFontFamily = SubtitleFontFamily.System,
    val customFontName: String? = null,
    val customFontPath: String? = null,
    val bottomOffset: Int = 20,
    val useForcedSubtitles: Boolean = false,
    val showOnlyPreferredLanguages: Boolean = false,
    /** Applied on top of [textColor] and [backgroundColor] rather than baked into the swatches. */
    val textOpacity: Float = 1f,
    val backgroundOpacity: Float = 1f,
) {
    val outlineEnabled: Boolean
        get() = edgeStyle.drawsEdge

    /** [textColor] with [textOpacity] applied, which is what renderers should actually use. */
    val effectiveTextColor: Color
        get() = textColor.copy(alpha = (textColor.alpha * textOpacity).coerceIn(0f, 1f))

    /** [backgroundColor] with [backgroundOpacity] applied. */
    val effectiveBackgroundColor: Color
        get() = backgroundColor.copy(
            alpha = (backgroundColor.alpha * backgroundOpacity).coerceIn(0f, 1f),
        )

    companion object {
        val DEFAULT = SubtitleStyleState()

        /** Smallest and largest selectable subtitle size, in sp. */
        const val MIN_FONT_SIZE_SP = 4
        const val MAX_FONT_SIZE_SP = 40
        const val FONT_SIZE_STEP_SP = 2
    }
}

data class SubtitleFontImportResult(
    val displayName: String,
    val path: String,
)

data class SubtitleSyncCue(
    val startTimeMs: Long,
    val text: String,
)

data class SubtitleAutoSyncUiState(
    val capturedPositionMs: Long? = null,
    val cues: List<SubtitleSyncCue> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

val SubtitleColorSwatches = listOf(
    Color.White,
    Color(0xFFFFD700),
    Color(0xFF00E5FF),
    Color(0xFFFF5C5C),
    Color(0xFF00FF88),
    Color(0xFF9B59B6),
    Color(0xFFF97316),
    Color(0xFF22C55E),
    Color(0xFF3B82F6),
    Color.Black,
)

val SubtitleBackgroundColorSwatches = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.55f),
    Color(0xFF111827).copy(alpha = 0.72f),
    Color(0xFF7F1D1D).copy(alpha = 0.68f),
    Color(0xFF064E3B).copy(alpha = 0.68f),
    Color(0xFF1E3A8A).copy(alpha = 0.68f),
)

fun Color.toStorageHexString(): String {
    fun component(value: Float): String =
        (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()

    return buildString {
        append('#')
        append(component(alpha))
        append(component(red))
        append(component(green))
        append(component(blue))
    }
}

fun subtitleColorFromStorage(value: String?): Color? {
    val normalized = value
        ?.trim()
        ?.removePrefix("#")
        ?.takeIf { it.length == 6 || it.length == 8 }
        ?: return null

    val argb = if (normalized.length == 6) {
        "FF$normalized"
    } else {
        normalized
    }

    val parsed = argb.toLongOrNull(16) ?: return null
    return Color(
        red = ((parsed shr 16) and 0xFF).toFloat() / 255f,
        green = ((parsed shr 8) and 0xFF).toFloat() / 255f,
        blue = (parsed and 0xFF).toFloat() / 255f,
        alpha = ((parsed shr 24) and 0xFF).toFloat() / 255f,
    )
}

data class SubtitleAudioUiState(
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val addonSubtitles: List<AddonSubtitle> = emptyList(),
    val isLoadingAddonSubtitles: Boolean = false,
    val addonSubtitleError: String? = null,
    val selectedAudioIndex: Int = -1,
    val selectedSubtitleIndex: Int = -1,
    val selectedAddonSubtitleId: String? = null,
    val useCustomSubtitles: Boolean = false,
    val subtitleStyle: SubtitleStyleState = SubtitleStyleState.DEFAULT,
    val showAudioModal: Boolean = false,
    val showSubtitleModal: Boolean = false,
    val activeSubtitleTab: SubtitleTab = SubtitleTab.BuiltIn,
)

@Composable
fun localizedTrackDisplayName(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    if (!language.isNullOrBlank()) return languageLabelForCode(language)
    return stringResource(Res.string.compose_player_track_number, index + 1)
}
