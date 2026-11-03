package com.example.wadrova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wadrova.domain.SavedLook

/** The user's saved looks, most recently saved first, which is the order the repository returns. */
@Composable
fun OutfitsView(
    state: WardrobeUiState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    onRetry: () -> Unit,
    grid: LazyGridState,
) {
    val looks = state.savedLooks
    Column(Modifier.fillMaxSize()) {
        if (state.looksStatus == LooksStatus.READY && looks.isNotEmpty()) {
            Text(
                "${looks.size} saved ${if (looks.size == 1) "look" else "looks"}",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = WadrovaColors.Ink2,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            state = grid,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 84.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            when {
                state.looksStatus == LooksStatus.LOADING -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Message("Loading your looks…", null)
                }
                state.looksStatus == LooksStatus.FAILED -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Message("Couldn't load your looks.", "Your saved looks are still on this device.")
                        ActionButton("Try again", Modifier.padding(top = 12.dp), onClick = onRetry)
                    }
                }
                looks.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Message("Your first look starts here.", "Combine pieces from your wardrobe and save a look.")
                        ActionButton("Build a look", Modifier.padding(top = 12.dp), dark = true) { update { it.requestNewOutfit() } }
                    }
                }
                else -> {
                    items(looks, key = { it.id }) { look ->
                        OutfitCard(
                            state,
                            look,
                            onOpen = { update { it.openLook(look) } },
                            onDelete = { update { it.requestDeleteLook(look) } },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Saved on this device.", style = MaterialTheme.typography.bodySmall, color = WadrovaColors.Ink2)
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, detail: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(WadrovaColors.Studio)
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = WadrovaColors.Charcoal,
            textAlign = TextAlign.Center,
        )
        detail?.let {
            Text(
                it,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = WadrovaColors.Ink2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OutfitCard(state: WardrobeUiState, look: SavedLook, onOpen: () -> Unit, onDelete: () -> Unit) {
    val missing = state.missingPieceCount(look)
    val archived = state.archivedPieceCount(look)
    val pieces = look.garmentIds.size

    Column(Modifier.clickable(role = Role.Button, onClick = onOpen)) {
        Box {
            OutfitPreview(state.itemsFor(look))
            OutfitMenu(look.name, onDelete, Modifier.align(Alignment.TopEnd))
        }
        Text(
            look.name,
            Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            color = WadrovaColors.Charcoal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append("$pieces ${if (pieces == 1) "piece" else "pieces"}")
                if (archived > 0) append(" · $archived archived")
                if (missing > 0) append(" · $missing no longer in your wardrobe")
            },
            Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = WadrovaColors.Ink2,
        )
    }
}

/** Three dots in the same corner and size as a garment card's select circle, with its own touch target. */
@Composable
private fun OutfitMenu(name: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .size(44.dp)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "More options for $name" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(WadrovaColors.Warm.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(14.dp, 4.dp)) {
                    val radius = size.height / 2
                    listOf(radius, size.width / 2, size.width - radius).forEach { x ->
                        drawCircle(WadrovaColors.Charcoal, radius, Offset(x, radius))
                    }
                }
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = WadrovaColors.Warm) {
            DropdownMenuItem(
                text = { Text("Delete look", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/** The look's own garment images: one main piece, with up to two more beside it. */
@Composable
private fun OutfitPreview(items: List<WardrobeItem>) {
    Row(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .clip(RoundedCornerShape(16.dp))
            .background(WadrovaColors.Studio)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val main = items.firstOrNull()
        if (main == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Pieces unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = WadrovaColors.Ink2,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            GarmentVisual(main, Modifier.weight(if (items.size > 1) 1.4f else 1f).fillMaxHeight())
            if (items.size > 1) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.drop(1).take(2).forEach { item ->
                        GarmentVisual(item, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}
