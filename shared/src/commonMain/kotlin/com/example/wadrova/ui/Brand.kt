package com.example.wadrova.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import wadrova.shared.generated.resources.Res
import wadrova.shared.generated.resources.wadrova_compact_symbol_graphite
import wadrova.shared.generated.resources.wadrova_compact_symbol_ivory
import wadrova.shared.generated.resources.wadrova_lockup_horizontal_graphite
import wadrova.shared.generated.resources.wadrova_lockup_horizontal_ivory
import wadrova.shared.generated.resources.wadrova_symbol_graphite
import wadrova.shared.generated.resources.wadrova_symbol_ivory

/**
 * The ground a brand mark sits on. Each colourway in the brand collection is drawn for one ground,
 * so a light ground takes the "ivory" art and a dark ground the "graphite" art.
 */
enum class BrandGround { LIGHT, DARK }

/** Below this width the primary symbol's lens thins out, so the brand calls for the compact symbol. */
private val COMPACT_BELOW = 48.dp

/** The horizontal lockup, symbol and wordmark, sized by its height. The art comes from the brand SVGs unchanged. */
@Composable
fun WadrovaLockup(height: Dp, modifier: Modifier = Modifier, ground: BrandGround = BrandGround.LIGHT) {
    BrandMark(
        if (ground == BrandGround.LIGHT) Res.drawable.wadrova_lockup_horizontal_ivory else Res.drawable.wadrova_lockup_horizontal_graphite,
        modifier.height(height),
    )
}

/** The Wadrova symbol at [width], switching to the compact symbol where the primary would be too small. */
@Composable
fun WadrovaSymbol(width: Dp, modifier: Modifier = Modifier, ground: BrandGround = BrandGround.LIGHT) {
    val compact = width < COMPACT_BELOW
    val art = when (ground) {
        BrandGround.LIGHT -> if (compact) Res.drawable.wadrova_compact_symbol_ivory else Res.drawable.wadrova_symbol_ivory
        BrandGround.DARK -> if (compact) Res.drawable.wadrova_compact_symbol_graphite else Res.drawable.wadrova_symbol_graphite
    }
    BrandMark(art, modifier.width(width))
}

/** Keeps the art's own proportions: the modifier fixes one side and the other follows. */
@Composable
private fun BrandMark(art: DrawableResource, modifier: Modifier) {
    val painter = painterResource(art)
    val size = painter.intrinsicSize
    Image(painter, contentDescription = "Wadrova", modifier = modifier.aspectRatio(size.width / size.height))
}
