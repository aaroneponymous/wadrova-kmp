package com.example.wadrova.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun WardrobeScreen(
    state: WardrobeUiState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    onRetryLooks: () -> Unit,
    itemsGrid: LazyGridState,
    outfitsGrid: LazyGridState,
    onStylist: () -> Unit,
) {
    val archivedMode = state.isShowingArchived

    BoxWithConstraints(Modifier.fillMaxSize().background(WadrovaColors.Ivory)) {
        // On short screens, such as landscape phones, the grid needs the title's space.
        val showTitle = maxHeight >= 520.dp
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            if (showTitle) {
                // The same top padding as Mirror's page, so the titles line up across tabs.
                Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) { EditorialTitle("Your ", "collection") }
            }
            WardrobeViewSwitcher(state.wardrobeView) { view -> update { it.showWardrobeView(view) } }
            Crossfade(state.wardrobeView, Modifier.fillMaxSize(), tween(150)) { view ->
                when (view) {
                    WardrobeView.ITEMS -> ItemsView(state, update, archivedMode, itemsGrid)
                    WardrobeView.OUTFITS -> OutfitsView(state, update, onRetryLooks, outfitsGrid)
                }
            }
        }
        when {
            state.wardrobeView == WardrobeView.OUTFITS -> FloatingActions(
                onStylist = null,
                addDescription = "Build a new look",
                onAdd = { update { it.requestNewOutfit() } },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            archivedMode -> Unit
            state.draftIds.isNotEmpty() -> SelectionDock(
                count = state.draftIds.size,
                onBuild = { update { it.openBuilder() } },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            else -> FloatingActions(
                onStylist = onStylist,
                addDescription = "Add a piece",
                onAdd = { update { it.openAddGarment() } },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun WardrobeViewSwitcher(selected: WardrobeView, onSelect: (WardrobeView) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = WadrovaColors.Line)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            WardrobeView.entries.forEach { view ->
                val active = view == selected
                val textColor by animateColorAsState(if (active) WadrovaColors.Charcoal else WadrovaColors.Ink2, tween(150))
                val lineColor by animateColorAsState(if (active) WadrovaColors.Cobalt else Color.Transparent, tween(150))
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .selectable(active, role = Role.Tab) { onSelect(view) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (view == WardrobeView.ITEMS) "Your Items" else "Your Outfits",
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        maxLines = 1,
                    )
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(lineColor))
                }
            }
        }
    }
}

@Composable
private fun ItemsView(
    state: WardrobeUiState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    archivedMode: Boolean,
    grid: LazyGridState,
) {
    val visible = if (archivedMode) state.archivedItems else state.itemsIn(state.wardrobeFilter)
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        if (archivedMode) {
            ArchivedBar(state.archivedItems.size) { update { it.showArchivedPieces(false) } }
        } else if (state.availableItems.isNotEmpty()) {
            CategoryChips(state.availableItems, state.wardrobeFilter) { filter -> update { it.filterWardrobe(filter) } }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            // A fixed gap, not top content padding, so pieces clip clear of the filters instead of against them.
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            state = grid,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 84.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(visible, key = { it.garment.id }) { item ->
                GarmentCard(
                    item,
                    state,
                    onToggle = if (archivedMode) null else ({ update { it.requestToggle(item) } }),
                    onEdit = { update { it.openEditGarment(item) } },
                    onOpen = { update { it.openGarmentDetails(item) } },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (visible.isEmpty()) {
                        val wardrobeEmpty = state.availableItems.isEmpty()
                        WardrobeEmptyState(
                            if (wardrobeEmpty) null else state.wardrobeFilter,
                            onViewAll = { update { it.filterWardrobe(null) } },
                            onAdd = if (wardrobeEmpty) ({ update { it.openAddGarment() } }) else null,
                        )
                    }
                    // Also under the empty state, so pieces stay restorable when every one is archived.
                    if (!archivedMode && state.archivedItems.isNotEmpty()) {
                        ActionButton("Archived pieces · ${state.archivedItems.size}", Modifier.padding(top = if (visible.isEmpty()) 16.dp else 0.dp, bottom = 16.dp)) {
                            update { it.showArchivedPieces(true) }
                        }
                    }
                    if (visible.isNotEmpty()) {
                        Text(
                            "Pieces use a generic illustration until you add a photo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WadrovaColors.Ink2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedBar(count: Int, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count archived. Saved looks keep them.",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = WadrovaColors.Ink2,
        )
        Box(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .border(1.dp, WadrovaColors.Line, CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Back", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Charcoal)
        }
    }
}

@Composable
private fun FloatingActions(onStylist: (() -> Unit)?, addDescription: String, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onStylist != null) {
            Row(
                Modifier
                    .height(44.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(WadrovaColors.Warm)
                    .clickable(role = Role.Button, onClick = onStylist)
                    .padding(start = 10.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TabIcon(PrimaryTab.STYLIST, WadrovaColors.Cobalt)
                Text("Stylist", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Charcoal)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(56.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(WadrovaColors.Charcoal)
                .clickable(role = Role.Button, onClick = onAdd)
                .semantics { contentDescription = addDescription },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(WadrovaColors.Warm, Offset(center.x, 0f), Offset(center.x, size.height), stroke, StrokeCap.Round)
                drawLine(WadrovaColors.Warm, Offset(0f, center.y), Offset(size.width, center.y), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SelectionDock(count: Int, onBuild: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(CircleShape)
            .background(WadrovaColors.Charcoal)
            .padding(6.dp)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count selected",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = WadrovaColors.Warm,
        )
        Box(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(WadrovaColors.Cobalt)
                .clickable(onClick = onBuild)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Build a Look", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Warm)
        }
    }
}
