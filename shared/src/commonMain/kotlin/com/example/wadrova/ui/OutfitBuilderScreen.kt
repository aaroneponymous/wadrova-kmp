package com.example.wadrova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wadrova.data.PhotoSize
import com.example.wadrova.domain.GarmentCategory

@Composable
fun OutfitBuilderScreen(
    state: WardrobeUiState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    onSave: () -> Unit,
) {
    val draft = state.draftItems
    val opened = state.openedLook

    Column(
        Modifier
            .fillMaxSize()
            .background(WadrovaColors.Ivory)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp),
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
        Eyebrow(
            when {
                opened == null -> "Outfit Builder"
                state.isDirty -> "Editing look · unsaved changes"
                else -> "Saved look"
            },
            Modifier.padding(top = 14.dp),
        )
        Box(Modifier.padding(top = 6.dp, bottom = 16.dp)) {
            if (opened == null) {
                EditorialTitle("Create ", "a look", WadrovaColors.Cobalt)
            } else {
                Text(opened.name, style = MaterialTheme.typography.displaySmall, color = WadrovaColors.Charcoal)
            }
        }

        if (draft.isEmpty()) {
            EmptyCanvas(onStart = { category -> update { it.openPicker(category) } })
        } else {
            Moodboard(draft)
            ActionButton("+ Add pieces", Modifier.padding(top = 12.dp)) { update { it.openPicker() } }
            if (opened == null || state.isDirty) {
                ActionButton(
                    "Save look",
                    Modifier.padding(top = 8.dp),
                    dark = true,
                    enabled = state.canSaveDraft,
                ) { update { it.openSaveSheet() } }
            }
        }
        if (state.unknownDraftIds.isNotEmpty()) {
            MissingPiecesNotice(state.unknownDraftIds.size, onRemove = { update { it.removeUnknownPieces() } })
        }
        if (draft.isNotEmpty()) {
            PieceList(draft, onRemove = { item -> update { it.toggle(item) } })
        }
        if (opened != null) {
            ActionButton("Start a new look", Modifier.padding(top = 16.dp)) { update { it.requestNewLook() } }
        }

        Text(
            "Saved looks stay on this device. Your draft lasts while the app is open.",
            Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = WadrovaColors.Ink2,
        )
    }

    if (state.pickerOpen) {
        GarmentPickerSheet(state, update)
    }
    state.saveSheet?.let { sheet -> SaveLookSheet(state, sheet, update, onSave) }
}

@Composable
private fun MissingPiecesNotice(count: Int, onRemove: () -> Unit) {
    Column(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WadrovaColors.Studio)
            .padding(16.dp),
    ) {
        Text(
            if (count == 1) "1 piece is no longer in your wardrobe." else "$count pieces are no longer in your wardrobe.",
            style = MaterialTheme.typography.bodyMedium,
            color = WadrovaColors.Charcoal,
        )
        ActionButton("Remove from this look", Modifier.padding(top = 12.dp), onClick = onRemove)
    }
}

/** Primary pieces lead in a wide column and supporting pieces sit beside them, as in the prototype. */
@Composable
private fun Moodboard(draft: List<WardrobeItem>) {
    val isSupport = { item: WardrobeItem ->
        item.garment.category == GarmentCategory.FOOTWEAR || item.garment.category == GarmentCategory.ACCESSORY
    }
    var left = draft.filterNot(isSupport).ifEmpty { draft }
    var right = if (left == draft) emptyList() else draft.filter(isSupport)
    if (right.isEmpty() && left.size > 2) {
        right = left.drop(1)
        left = left.take(1)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .clip(RoundedCornerShape(26.dp))
            .background(WadrovaColors.Studio)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        Column(
            if (right.isEmpty()) Modifier.fillMaxHeight().fillMaxWidth(0.74f) else Modifier.fillMaxHeight().weight(0.6f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            left.forEach { MoodTile(it) }
        }
        if (right.isNotEmpty()) {
            Column(Modifier.fillMaxHeight().weight(0.4f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Look in progress · ${draft.size.toString().padStart(2, '0')}",
                    Modifier.padding(start = 4.dp, top = 8.dp),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize, lineHeight = MaterialTheme.typography.titleSmall.lineHeight),
                    color = WadrovaColors.Ink2,
                )
                right.forEach { MoodTile(it) }
            }
        }
    }
}

@Composable
private fun ColumnScope.MoodTile(item: WardrobeItem) {
    val weight = when (item.garment.category) {
        GarmentCategory.DRESS -> 8f
        GarmentCategory.FOOTWEAR -> 4f
        GarmentCategory.ACCESSORY -> 3f
        else -> 5f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .weight(weight)
            .clip(RoundedCornerShape(18.dp))
            .background(WadrovaColors.Warm)
            .padding(10.dp),
    ) {
        GarmentVisual(item, Modifier.fillMaxSize(), PhotoSize.DISPLAY)
    }
}

/** Ghost composition with a few starting points. */
@Composable
private fun EmptyCanvas(onStart: (GarmentCategory) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .clip(RoundedCornerShape(26.dp))
            .background(WadrovaColors.Studio)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Start with a piece you love.",
            style = MaterialTheme.typography.headlineSmall,
            color = WadrovaColors.Charcoal,
            textAlign = TextAlign.Center,
        )
        Text(
            "Build the look around it.",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = WadrovaColors.Ink2,
        )
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "A top" to GarmentCategory.TOP,
                "A dress" to GarmentCategory.DRESS,
                "A jacket" to GarmentCategory.OUTERWEAR,
            ).forEach { (label, category) ->
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(WadrovaColors.Warm)
                        .border(1.dp, WadrovaColors.Line, CircleShape)
                        .clickable(role = Role.Button) { onStart(category) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Charcoal)
                }
            }
        }
    }
}

@Composable
private fun PieceList(draft: List<WardrobeItem>, onRemove: (WardrobeItem) -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        Eyebrow("In this look")
        HorizontalDivider(Modifier.padding(top = 8.dp), color = WadrovaColors.Charcoal)
        draft.forEach { item ->
            Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WadrovaColors.Studio)
                        .padding(6.dp),
                ) {
                    GarmentVisual(item, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = WadrovaColors.Charcoal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${item.garment.category.singularLabel} · ${if (item.archived) "Archived" else item.colorName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = WadrovaColors.Ink2,
                    )
                }
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) { onRemove(item) }
                        .semantics { contentDescription = "Remove ${item.name}" }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Remove", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Ink2)
                }
            }
            HorizontalDivider(color = WadrovaColors.Line)
        }
    }
}

/** Disabled is its own pair of colors, so a button is never dimmed against whatever sits behind it. */
internal fun actionButtonColors(dark: Boolean, enabled: Boolean): Pair<Color, Color> = when {
    !enabled -> WadrovaColors.Studio to WadrovaColors.Ink2
    dark -> WadrovaColors.Charcoal to WadrovaColors.Warm
    else -> WadrovaColors.CobaltSoft to WadrovaColors.Cobalt
}

@Composable
internal fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val (background, content) = actionButtonColors(dark, enabled)
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GarmentPickerSheet(state: WardrobeUiState, update: ((WardrobeUiState) -> WardrobeUiState) -> Unit) {
    val visible = state.itemsIn(state.pickerFilter)
    val count = state.draftIds.size

    ModalBottomSheet(
        onDismissRequest = { update { it.closePicker() } },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WadrovaColors.Warm,
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                Text("From your wardrobe", style = MaterialTheme.typography.titleLarge, color = WadrovaColors.Charcoal)
                Text(
                    "$count ${if (count == 1) "piece" else "pieces"} in this look",
                    style = MaterialTheme.typography.bodySmall,
                    color = WadrovaColors.Ink2,
                )
            }
            CategoryChips(state.availableItems, state.pickerFilter) { filter -> update { it.filterPicker(filter) } }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(visible, key = { it.garment.id }) { item ->
                    GarmentCard(item, state, onToggle = { update { it.toggle(item) } })
                }
                if (visible.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        WardrobeEmptyState(state.pickerFilter, onViewAll = { update { it.filterPicker(null) } })
                    }
                }
            }
            ActionButton("Done · View look", Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(), dark = true) {
                update { it.closePicker() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveLookSheet(
    state: WardrobeUiState,
    sheet: SaveSheetState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    onSave: () -> Unit,
) {
    val opened = state.openedLook

    ModalBottomSheet(
        onDismissRequest = { update { it.closeSaveSheet() } },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !sheet.saving }),
        containerColor = WadrovaColors.Warm,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Text("Save this look", style = MaterialTheme.typography.titleLarge, color = WadrovaColors.Charcoal)
            if (opened != null) {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaveModeChip("Save as new", sheet.mode == SaveMode.NEW, Modifier.weight(1f)) {
                        update { it.setSaveMode(SaveMode.NEW) }
                    }
                    SaveModeChip("Update existing", sheet.mode == SaveMode.UPDATE, Modifier.weight(1f)) {
                        update { it.setSaveMode(SaveMode.UPDATE) }
                    }
                }
            }
            OutlinedTextField(
                value = sheet.name,
                onValueChange = { name -> update { it.setSaveName(name) } },
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                label = { Text("Name") },
                placeholder = { Text("e.g. Quiet Monday") },
                singleLine = true,
                enabled = !sheet.saving,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(14.dp),
            )
            sheet.error?.let { error ->
                Text(
                    error,
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ActionButton(
                when {
                    sheet.saving -> "Saving…"
                    sheet.mode == SaveMode.UPDATE -> "Update look"
                    else -> "Save look"
                },
                Modifier.padding(top = 16.dp),
                dark = true,
                enabled = state.canSubmitSave,
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun SaveModeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (selected) WadrovaColors.Charcoal else WadrovaColors.Warm)
            .border(1.dp, if (selected) WadrovaColors.Charcoal else WadrovaColors.Line, CircleShape)
            .selectable(selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) WadrovaColors.Warm else WadrovaColors.Charcoal,
        )
    }
}
