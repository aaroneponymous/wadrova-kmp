package com.example.wadrova.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.wadrova.data.PhotoSize

/** A single piece. Brand and material only appear when the piece has them. */
@Composable
fun GarmentDetailsScreen(
    state: WardrobeUiState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    scroll: ScrollState,
    recovery: CutoutRecoveryUi? = null,
    onRecoverCutout: () -> Unit = {},
) {
    val item = state.detailItem
    Column(
        Modifier
            .fillMaxSize()
            .background(WadrovaColors.Ivory)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(WadrovaColors.Warm)
                    .border(1.dp, WadrovaColors.Line, CircleShape)
                    .clickable(role = Role.Button) { update { it.openWardrobe() } }
                    .semantics { contentDescription = "Back to wardrobe" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = WadrovaColors.Charcoal)
            }
            Spacer(Modifier.weight(1f))
            if (item != null) {
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { update { it.openEditGarment(item) } }
                        .semantics { contentDescription = "Edit ${item.name}" }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Edit", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Cobalt)
                }
            }
        }
        if (item == null) {
            Text(
                "This piece is no longer in your wardrobe.",
                Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = WadrovaColors.Ink2,
            )
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            // Clean presents the cutout the wardrobe shows, so it's offered only when no Mirror extraction takes precedence.
            val cleanCutout = item.cutoutImageId?.takeIf { item.extractedImageId == null }
            var clean by remember(item.garment.id) { mutableStateOf(false) }
            if (cleanCutout != null) {
                Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormChip("Original", selected = !clean, enabled = true) { clean = false }
                    FormChip("Clean", selected = clean, enabled = true) { clean = true }
                }
            }
            val original: @Composable () -> Unit = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 5f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(WadrovaColors.Studio)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GarmentVisual(item, Modifier.fillMaxSize(), PhotoSize.DISPLAY)
                }
            }
            if (clean && cleanCutout != null) {
                StudioCleanVisual(
                    item,
                    cleanCutout,
                    Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(16.dp)),
                    fallback = original,
                )
                Text(
                    "Clean frames this piece's own cutout on a studio background at a consistent size. Its pixels aren't changed, and nothing is saved.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = WadrovaColors.Ink2,
                )
            } else {
                original()
            }
            if (item.archived) Eyebrow("Archived", Modifier.padding(top = 16.dp))
            Text(
                item.name,
                Modifier.padding(top = if (item.archived) 4.dp else 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.displaySmall,
                color = WadrovaColors.Charcoal,
            )
            HorizontalDivider(color = WadrovaColors.Charcoal)
            DetailRow("Category", item.garment.category.singularLabel)
            DetailRow("Color", item.colorName)
            item.brand?.takeIf { it.isNotBlank() }?.let { DetailRow("Brand", it) }
            item.material?.takeIf { it.isNotBlank() }?.let { DetailRow("Material", it) }
            recovery?.let { CutoutRecoverySection(it, onRecoverCutout) }
        }
        // Archived pieces stay out of new looks. Editing the piece can restore it.
        if (!item.archived) {
            ActionButton(
                "Style this piece",
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                dark = true,
            ) { update { it.requestStylePiece(item) } }
        }
    }
}

/** Offered only for an imported piece saved before cutouts were kept (or whose cutout file is missing). */
@Composable
private fun CutoutRecoverySection(recovery: CutoutRecoveryUi, onRecover: () -> Unit) {
    if (!recovery.available && recovery.message == null) return
    Column(Modifier.padding(top = 16.dp)) {
        if (recovery.available) {
            Text(
                "This piece shows its photo on a white background. Its transparent cutout can be downloaded again from Wadrova.",
                style = MaterialTheme.typography.bodySmall,
                color = WadrovaColors.Ink2,
            )
            ActionButton(
                when {
                    recovery.working -> "Restoring cutout…"
                    recovery.canRetry -> "Try again"
                    else -> "Restore transparent cutout"
                },
                Modifier.padding(top = 8.dp),
                enabled = !recovery.working,
                onClick = onRecover,
            )
        }
        recovery.message?.let {
            Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = WadrovaColors.Charcoal)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = WadrovaColors.Ink2)
        Text(value, Modifier.weight(2f), style = MaterialTheme.typography.titleSmall, color = WadrovaColors.Charcoal)
    }
    HorizontalDivider(color = WadrovaColors.Line)
}
