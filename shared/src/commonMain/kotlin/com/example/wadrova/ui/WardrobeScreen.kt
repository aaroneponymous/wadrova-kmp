package com.example.wadrova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun WardrobeScreen(state: WardrobeUiState, update: ((WardrobeUiState) -> WardrobeUiState) -> Unit) {
    val visible = state.itemsIn(state.wardrobeFilter)

    BoxWithConstraints(Modifier.fillMaxSize().background(WadrovaColors.Ivory)) {
        // On short screens, such as landscape phones, the grid needs the header's space.
        val showHeader = maxHeight >= 520.dp
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (showHeader) WardrobeHeader(state.items) else Spacer(Modifier.height(8.dp))
            CategoryChips(state.items, state.wardrobeFilter) { filter -> update { it.filterWardrobe(filter) } }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(visible, key = { it.garment.id }) { item ->
                    GarmentCard(item, state, onToggle = { update { it.toggle(item) } })
                }
                if (visible.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        WardrobeEmptyState(state.wardrobeFilter, onViewAll = { update { it.filterWardrobe(null) } })
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Sample wardrobe · illustrated pieces, not your clothes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WadrovaColors.Ink2,
                        )
                    }
                }
            }
        }
        SelectionDock(
            count = state.draftIds.size,
            onBuild = { update { it.openBuilder() } },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun WardrobeHeader(items: List<WardrobeItem>) {
    val categories = items.map { it.garment.category }.distinct().size
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)) {
        Eyebrow("Digital Wardrobe")
        Box(Modifier.padding(top = 8.dp)) { EditorialTitle("Your ", "collection") }
        HorizontalDivider(Modifier.padding(top = 10.dp), color = WadrovaColors.Charcoal)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${items.size} ${if (items.size == 1) "piece" else "pieces"}",
                style = MaterialTheme.typography.titleSmall,
                color = WadrovaColors.Charcoal,
            )
            if (categories > 0) {
                Text(
                    "across $categories ${if (categories == 1) "category" else "categories"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WadrovaColors.Ink2,
                )
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
            if (count > 0) "$count selected" else "Tap pieces to select",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = WadrovaColors.Warm,
        )
        Box(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(WadrovaColors.Cobalt)
                .alpha(if (count > 0) 1f else 0.4f)
                .clickable(enabled = count > 0, onClick = onBuild)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Build a Look", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Warm)
        }
    }
}
