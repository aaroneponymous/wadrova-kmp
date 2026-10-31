package com.example.wadrova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wadrova.domain.SavedLook

@Composable
fun SavedLooksScreen(
    state: WardrobeUiState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(WadrovaColors.Ivory)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
        Eyebrow("Library", Modifier.padding(top = 14.dp))
        Box(Modifier.padding(top = 6.dp, bottom = 16.dp)) {
            EditorialTitle("Saved ", "looks", WadrovaColors.Cobalt)
        }

        when {
            state.looksStatus == LooksStatus.LOADING -> Message("Loading your looks…", null)
            state.looksStatus == LooksStatus.FAILED -> {
                Message("Couldn't load your looks.", "Your saved looks are still on this device.")
                ActionButton("Try again", Modifier.padding(top = 12.dp), onClick = onRetry)
            }
            state.savedLooks.isEmpty() -> {
                Message("No saved looks yet.", "Build an outfit, then save it to find it here.")
                ActionButton("Back to your wardrobe", Modifier.padding(top = 12.dp)) { update { it.openWardrobe() } }
            }
            else -> {
                HorizontalDivider(color = WadrovaColors.Charcoal)
                state.savedLooks.forEach { look ->
                    SavedLookRow(state, look) { update { it.requestOpen(look) } }
                    HorizontalDivider(color = WadrovaColors.Line)
                }
                Text(
                    "Saved on this device.",
                    Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = WadrovaColors.Ink2,
                )
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
private fun SavedLookRow(state: WardrobeUiState, look: SavedLook, onOpen: () -> Unit) {
    val items = state.itemsFor(look)
    val missing = state.missingPieceCount(look)
    val archived = state.archivedPieceCount(look)
    val pieces = look.garmentIds.size

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = 14.dp),
    ) {
        Text(
            look.name,
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
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.take(5).forEach { item ->
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WadrovaColors.Studio)
                        .padding(6.dp),
                ) {
                    GarmentIllustration(item, Modifier.fillMaxSize())
                }
            }
        }
    }
}
