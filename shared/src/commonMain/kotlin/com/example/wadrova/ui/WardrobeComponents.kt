package com.example.wadrova.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.wadrova.domain.GarmentCategory

/** A bold title with a trailing editorial italic accent, the prototype's signature heading. */
@Composable
fun EditorialTitle(lead: String, accent: String, accentColor: Color = WadrovaColors.Charcoal) {
    val typography = MaterialTheme.typography
    Text(
        buildAnnotatedString {
            append(lead)
            withStyle(
                SpanStyle(
                    fontFamily = typography.headlineSmall.fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontStyle = typography.headlineSmall.fontStyle,
                    letterSpacing = typography.headlineSmall.letterSpacing,
                    color = accentColor,
                ),
            ) { append(accent) }
        },
        style = typography.displaySmall,
    )
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier,
        style = MaterialTheme.typography.labelSmall,
        color = WadrovaColors.Ink2,
    )
}

/** "All" followed by each category with its live count. */
@Composable
fun CategoryChips(items: List<WardrobeItem>, selected: GarmentCategory?, onSelect: (GarmentCategory?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CategoryChip("All", items.size, selected == null) { onSelect(null) }
        filterCategories.forEach { category ->
            CategoryChip(category.pluralLabel, items.count { it.garment.category == category }, selected == category) {
                onSelect(category)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val content = if (selected) WadrovaColors.Warm else WadrovaColors.Charcoal
    // A remembered category may sit past the edge of the row, so it scrolls into sight when shown.
    val sight = remember { BringIntoViewRequester() }
    if (selected) LaunchedEffect(Unit) { sight.bringIntoView() }
    Row(
        Modifier
            .bringIntoViewRequester(sight)
            .height(44.dp)
            .clip(CircleShape)
            .background(if (selected) WadrovaColors.Charcoal else WadrovaColors.Warm)
            .border(1.dp, if (selected) WadrovaColors.Charcoal else WadrovaColors.Line, CircleShape)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
        Text(
            count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) WadrovaColors.Warm.copy(alpha = 0.6f) else WadrovaColors.Ink2,
        )
    }
}

/** Image-led selectable card. The second line previews what tapping would do to the draft. */
@Composable
fun GarmentCard(
    item: WardrobeItem,
    state: WardrobeUiState,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
) {
    val selected = state.isInDraft(item)
    val replaced = state.replacedBy(item)
    val shape = RoundedCornerShape(16.dp)
    val status = when {
        selected -> "In this look"
        replaced.size > 1 -> "Replaces ${replaced.size} pieces"
        replaced.size == 1 -> "Replaces ${replaced.single().name}"
        else -> item.colorName
    }

    // With a details page the card opens it and only the circle selects. Otherwise the whole card selects.
    val cardAction = when {
        onOpen != null -> Modifier.clickable(role = Role.Button, onClick = onOpen)
        onToggle != null -> Modifier.toggleable(selected, role = Role.Checkbox) { onToggle() }
        else -> Modifier
    }
    Column(modifier.then(cardAction)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
                .clip(shape)
                .background(if (selected) WadrovaColors.CobaltSoft else WadrovaColors.Studio)
                .then(if (selected) Modifier.border(2.dp, WadrovaColors.Cobalt, shape) else Modifier),
        ) {
            GarmentVisual(item, Modifier.fillMaxSize().padding(if (selected) 22.dp else 16.dp))
            if (onToggle != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(44.dp)
                        .then(
                            if (onOpen == null) {
                                Modifier
                            } else {
                                Modifier
                                    .toggleable(selected, role = Role.Checkbox) { onToggle() }
                                    .semantics { contentDescription = "Select ${item.name}" }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (selected) WadrovaColors.Cobalt else WadrovaColors.Warm.copy(alpha = 0.8f))
                            .then(if (selected) Modifier else Modifier.border(1.dp, WadrovaColors.Charcoal.copy(alpha = 0.15f), CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Text("✓", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Warm)
                    }
                }
            }
            if (onEdit != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .height(44.dp)
                        .clip(RoundedCornerShape(topStart = 10.dp))
                        .clickable(role = Role.Button, onClick = onEdit)
                        .semantics { contentDescription = "Edit ${item.name}" }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // The pill keeps the label readable over any photo.
                    Text(
                        "Edit",
                        Modifier
                            .clip(CircleShape)
                            .background(WadrovaColors.Warm.copy(alpha = 0.92f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = WadrovaColors.Cobalt,
                    )
                }
            }
            if (item.isSample) {
                Text(
                    "Sample".uppercase(),
                    Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.14.em),
                    color = WadrovaColors.Ink2,
                )
            }
        }
        Text(
            item.name,
            Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            color = WadrovaColors.Charcoal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${item.garment.category.singularLabel} · $status",
            Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected || replaced.isNotEmpty()) WadrovaColors.Cobalt else WadrovaColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown when a wardrobe or a filtered category has nothing to display. */
@Composable
fun WardrobeEmptyState(filter: GarmentCategory?, onViewAll: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(WadrovaColors.Studio)
            .padding(PaddingValues(horizontal = 24.dp, vertical = 40.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (filter == null) "Your collection starts here." else "Nothing in this category yet.",
            style = MaterialTheme.typography.headlineSmall,
            color = WadrovaColors.Charcoal,
            textAlign = TextAlign.Center,
        )
        Text(
            if (filter == null) "Pieces you add will appear here." else "Browse the rest of your collection.",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = WadrovaColors.Ink2,
            textAlign = TextAlign.Center,
        )
        if (filter != null) {
            Box(
                Modifier
                    .padding(top = 20.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(WadrovaColors.Sand)
                    .clickable(onClick = onViewAll)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("View all", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Charcoal)
            }
        }
    }
}
