package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal expect val nuvioPlatformExtraTopPadding: Dp
internal expect val nuvioPlatformExtraBottomPadding: Dp
internal expect val nuvioBottomNavigationExtraVerticalPadding: Dp
@Composable
internal expect fun nuvioBottomNavigationBarInsets(): WindowInsets
@Composable
internal expect fun platformPhysicalTopInset(): Dp

internal val LocalNuvioBottomNavigationOverlayPadding = staticCompositionLocalOf { 0.dp }
val LocalNuvioNavBarScrollState = staticCompositionLocalOf<NuvioNavBarScrollState?> { null }

@Composable
internal fun nuvioSafeBottomPadding(extra: Dp = 0.dp): Dp {
	val navigationBarBottom = nuvioBottomNavigationBarInsets()
		.asPaddingValues()
		.calculateBottomPadding()
	return navigationBarBottom.coerceAtLeast(nuvioPlatformExtraBottomPadding) +
		LocalNuvioBottomNavigationOverlayPadding.current +
		extra
}


/**
 * Horizontal inset that keeps content clear of a camera cutout, and of the gesture bar when it
 * sits on a side edge.
 *
 * Zero in portrait on every phone, so applying it unconditionally costs nothing there. It only
 * becomes non-zero in landscape on devices whose notch or Dynamic Island eats into the side of
 * the display, which is exactly where text and posters were running underneath it.
 *
 * Derived from safeDrawing rather than a hardcoded value: the inset differs per device and per
 * rotation direction (the cutout is on the left in one landscape orientation and the right in
 * the other), and only the platform knows which.
 */
@Composable
internal fun nuvioCutoutHorizontalPadding(): Pair<Dp, Dp> {
	val layoutDirection = LocalLayoutDirection.current
	val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
	return safeDrawing.calculateStartPadding(layoutDirection) to
		safeDrawing.calculateEndPadding(layoutDirection)
}
