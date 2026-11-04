package com.example.wadrova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PrimaryTab(val label: String) {
    HOME("Home"),
    WARDROBE("Wardrobe"),
    MIRROR("Mirror"),
    STYLIST("Stylist"),
    DISCOVER("Discover"),
}

/** Mirror for this milestone, because it's the feature to test. Switch to [PrimaryTab.HOME] once Home exists. */
val START_TAB = PrimaryTab.MIRROR

/** Where Back goes from a placeholder tab: to Wardrobe if its Stylist button opened this tab, otherwise the start tab. */
fun placeholderBackTab(tab: PrimaryTab, openedFromWardrobe: Boolean): PrimaryTab =
    if (tab == PrimaryTab.STYLIST && openedFromWardrobe) PrimaryTab.WARDROBE else START_TAB

/**
 * The bar only shows on a tab's root screen, so a workflow can't be left half-finished behind another tab.
 * [importOpen] is an import page over the Wardrobe; Back leads to the Wardrobe, where the bar is.
 */
fun showsPrimaryBar(tab: PrimaryTab, screen: Screen, mirrorRoute: MirrorRoute, extracting: Boolean, importOpen: Boolean = false): Boolean = when {
    extracting -> false
    tab == PrimaryTab.WARDROBE -> screen == Screen.WARDROBE && !importOpen
    tab == PrimaryTab.MIRROR -> mirrorRoute == MirrorRoute.HOME
    else -> true
}

/** The system areas the bar sits clear of. Screens above it must not reserve the bottom one again. */
val primaryBarInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

@Composable
fun PrimaryNavigationBar(selected: PrimaryTab, onSelect: (PrimaryTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(WadrovaColors.Warm).windowInsetsPadding(primaryBarInsets)) {
        HorizontalDivider(color = WadrovaColors.Line)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            PrimaryTab.entries.forEach { tab ->
                val color = if (tab == selected) WadrovaColors.Cobalt else WadrovaColors.Ink2
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .selectable(tab == selected, role = Role.Tab) { onSelect(tab) }
                        .padding(top = 8.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    TabIcon(tab, color)
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A root destination that isn't built yet: only its title, with the brand lockup on Home. */
@Composable
fun PlaceholderScreen(tab: PrimaryTab) {
    Column(
        Modifier
            .fillMaxSize()
            .background(WadrovaColors.Ivory)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        if (tab == PrimaryTab.HOME) WadrovaLockup(height = 28.dp, modifier = Modifier.padding(bottom = 20.dp))
        Text(tab.label, style = MaterialTheme.typography.displaySmall, color = WadrovaColors.Charcoal)
    }
}

/** Outline icons drawn on a 24-unit grid. */
@Composable
internal fun TabIcon(tab: PrimaryTab, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val unit = size.width / 24f
        fun at(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun outline(build: Path.() -> Unit) = drawPath(Path().apply(build), color, style = stroke)
        fun Path.move(x: Float, y: Float) = moveTo(x * unit, y * unit)
        fun Path.line(x: Float, y: Float) = lineTo(x * unit, y * unit)
        fun Path.curve(cx: Float, cy: Float, x: Float, y: Float) = quadraticTo(cx * unit, cy * unit, x * unit, y * unit)

        when (tab) {
            PrimaryTab.HOME -> outline {
                move(4f, 11f); line(12f, 4f); line(20f, 11f); line(20f, 20f); line(4f, 20f); close()
            }
            PrimaryTab.WARDROBE -> {
                drawArc(color, 180f, 270f, useCenter = false, topLeft = at(10f, 4f), size = Size(4f * unit, 4f * unit), style = stroke)
                outline {
                    move(12f, 8f); line(12f, 10f); line(21f, 17f); curve(21.5f, 19f, 20f, 19f)
                    line(4f, 19f); curve(2.5f, 19f, 3f, 17f); line(12f, 10f)
                }
            }
            PrimaryTab.MIRROR -> {
                drawOval(color, at(7f, 3f), Size(10f * unit, 14f * unit), style = stroke)
                outline { move(12f, 17f); line(12f, 21f); move(8f, 21f); line(16f, 21f) }
            }
            PrimaryTab.STYLIST -> outline {
                move(12f, 4f); curve(13f, 11f, 20f, 12f); curve(13f, 13f, 12f, 20f)
                curve(11f, 13f, 4f, 12f); curve(11f, 11f, 12f, 4f); close()
            }
            PrimaryTab.DISCOVER -> {
                drawCircle(color, 9f * unit, at(12f, 12f), style = stroke)
                outline { move(15.5f, 8.5f); line(13.5f, 13.5f); line(8.5f, 15.5f); line(10.5f, 10.5f); close() }
            }
        }
    }
}
