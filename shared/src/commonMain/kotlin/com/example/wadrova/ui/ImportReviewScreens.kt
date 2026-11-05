package com.example.wadrova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.wadrova.data.PhotoSize
import com.example.wadrova.domain.Candidate
import com.example.wadrova.domain.Lineage
import com.example.wadrova.domain.fitTapToImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Review your pieces: the real cutouts of one import, chosen and described before anything reaches the wardrobe. */
@Composable
fun ImportReviewScreen(state: ReviewUiState, viewModel: GarmentReviewViewModel, onBack: () -> Unit, onGoToWardrobe: () -> Unit) {
    val detail = state.detail
    if (detail != null && state.savedCount == null) {
        PieceDetailScreen(state, detail, viewModel)
        return
    }
    Column(Modifier.fillMaxSize().background(WadrovaColors.Ivory)) {
        Box(Modifier.weight(1f)) {
            MirrorPage("Add a piece", "Review your ", "pieces", onBack) {
                when {
                    state.savedCount != null -> SavedNotice(state.savedCount, onGoToWardrobe)
                    state.loading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = WadrovaColors.Cobalt, strokeWidth = 2.dp)
                    }
                    state.loadError != null -> {
                        Body(state.loadError)
                        ActionButton("Try again", Modifier.padding(top = 16.dp), dark = true, onClick = viewModel::retryLoad)
                    }
                    else -> ReviewGrid(state, viewModel)
                }
            }
        }
        if (state.savedCount == null && !state.loading && state.loadError == null) SaveBar(state, viewModel)
    }
    if (state.confirmingFinishEmpty) {
        AlertDialog(
            onDismissRequest = viewModel::keepReviewing,
            title = { Text("Finish without saving?") },
            text = { Text("None of these pieces will be added to your wardrobe.") },
            confirmButton = { TextButton(onClick = viewModel::save) { Text("Finish") } },
            dismissButton = { TextButton(onClick = viewModel::keepReviewing) { Text("Keep reviewing") } },
        )
    }
}

@Composable
private fun ReviewGrid(state: ReviewUiState, viewModel: GarmentReviewViewModel) {
    state.photoId?.let { photoId ->
        Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(WadrovaColors.Studio)) {
            PhotoOrFallback(photoId, PhotoSize.DISPLAY, "Your photo", Modifier.fillMaxSize()) {}
        }
    }
    if (state.pieces.isEmpty()) {
        Body("Wadrova didn't find any pieces in this photo.", Modifier.padding(top = 16.dp))
        return
    }
    Body(
        "Tap the pieces you want to keep. Names and categories are suggestions; check them before saving.",
        Modifier.padding(top = 16.dp, bottom = 12.dp),
    )
    if (state.failedImages.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Small("Some images couldn't be loaded.", Modifier.weight(1f))
            TextButton(onClick = viewModel::retryImages) { Text("Try again") }
        }
    }
    state.pieces.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { lineage -> PieceCard(state, lineage, viewModel, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PieceCard(state: ReviewUiState, lineage: Lineage, viewModel: GarmentReviewViewModel, modifier: Modifier) {
    val choice = state.choice(lineage)
    val shown = state.shown(lineage)
    val needsDetails = choice.selected && !state.isComplete(lineage)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .background(WadrovaColors.Studio)
                .border(2.dp, if (choice.selected) WadrovaColors.Cobalt else Color.Transparent, RoundedCornerShape(16.dp))
                .clickable(role = Role.Checkbox) { viewModel.toggle(lineage.id) }
                .semantics { contentDescription = "${state.name(lineage)}, ${if (choice.selected) "selected" else "not selected"}" },
            contentAlignment = Alignment.Center,
        ) {
            CutoutImage(state, shown, Modifier.fillMaxSize().padding(12.dp))
            SelectionMark(choice.selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (choice.correction != null) {
                CircularProgressIndicator(Modifier.align(Alignment.BottomStart).padding(10.dp).size(16.dp), color = WadrovaColors.Cobalt, strokeWidth = 2.dp)
            }
        }
        Text(state.name(lineage), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, color = WadrovaColors.Charcoal, maxLines = 1)
        Text(
            if (needsDetails) "Needs details" else state.category(lineage)?.singularLabel ?: "Choose a category",
            style = MaterialTheme.typography.bodySmall,
            color = if (needsDetails) MaterialTheme.colorScheme.error else WadrovaColors.Ink2,
        )
        Text(
            "Details",
            Modifier.padding(top = 2.dp).clickable(role = Role.Button) { viewModel.openDetail(lineage.id) }.padding(vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = WadrovaColors.Cobalt,
        )
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier) {
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (selected) WadrovaColors.Cobalt else WadrovaColors.Warm)
            .border(1.dp, if (selected) WadrovaColors.Cobalt else WadrovaColors.Line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Warm)
    }
}

/** The fixed bottom bar: a truthful count and the one action that saves. */
@Composable
private fun SaveBar(state: ReviewUiState, viewModel: GarmentReviewViewModel) {
    val count = state.selected.size
    Column(
        Modifier
            .fillMaxWidth()
            .background(WadrovaColors.Warm)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        HorizontalDivider(Modifier.padding(bottom = 10.dp), color = WadrovaColors.Line)
        (state.saveError ?: state.saveProgress)?.let {
            Text(
                it,
                Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.saveError != null) MaterialTheme.colorScheme.error else WadrovaColors.Ink2,
            )
        }
        Small(
            when {
                state.pieces.isEmpty() -> "Nothing to save from this photo."
                count == 0 -> "No pieces selected"
                count == 1 -> "1 piece selected"
                else -> "$count pieces selected"
            },
            Modifier.padding(bottom = 8.dp),
        )
        val label = when {
            state.saving -> "Saving…"
            count == 0 -> "Finish without saving pieces"
            count == 1 -> "Save 1 piece to Wardrobe"
            else -> "Save $count pieces to Wardrobe"
        }
        ActionButton(label, dark = true, enabled = !state.saving && !state.correcting) {
            if (count == 0) viewModel.requestFinishEmpty() else viewModel.save()
        }
    }
}

@Composable
private fun SavedNotice(count: Int, onGoToWardrobe: () -> Unit) {
    Text(
        when (count) {
            0 -> "Finished"
            1 -> "1 piece saved"
            else -> "$count pieces saved"
        },
        style = MaterialTheme.typography.titleLarge,
        color = WadrovaColors.Charcoal,
    )
    Body(
        if (count == 0) "Nothing from this photo was added to your wardrobe." else "They're in your wardrobe now, ready to style in the Outfit Builder.",
        Modifier.padding(top = 6.dp),
    )
    ActionButton("Go to wardrobe", Modifier.padding(top = 20.dp), dark = true, onClick = onGoToWardrobe)
}

/** One piece: its details, the outline on the original photo, other versions and the corrections Wadrova offers. */
@Composable
private fun PieceDetailScreen(state: ReviewUiState, lineage: Lineage, viewModel: GarmentReviewViewModel) {
    val choice = state.choice(lineage)
    val shown = state.shown(lineage)
    val busy = state.saving || choice.correction != null
    MirrorPage("Review pieces", "Piece ", "details", viewModel::closeDetail) {
        state.photoId?.let { photoId ->
            OutlineOnPhoto(state, photoId, shown, viewModel)
        }
        Small(
            if (state.points.isEmpty()) "Tap the photo to mark what belongs to this piece, then apply." else "${state.points.size} point${if (state.points.size == 1) "" else "s"} placed.",
            Modifier.padding(top = 8.dp),
        )
        ChipGroup {
            FormChip("Add to piece", state.addingPositivePoints, enabled = !busy) { viewModel.setPointMode(true) }
            FormChip("Not part of it", !state.addingPositivePoints, enabled = !busy) { viewModel.setPointMode(false) }
        }
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { viewModel.submitPoints(lineage.id) }, enabled = !busy && state.points.isNotEmpty()) { Text("Apply points") }
            TextButton(onClick = viewModel::clearPoints, enabled = state.points.isNotEmpty()) { Text("Clear") }
        }
        choice.correction?.let {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = WadrovaColors.Cobalt, strokeWidth = 2.dp)
                Small("Wadrova is correcting this piece. You can leave this page.", Modifier.padding(start = 8.dp))
            }
        }
        choice.correctionMessage?.let { Small(it, Modifier.padding(top = 4.dp)) }

        SectionTitle("Name")
        OutlinedTextField(
            value = state.name(lineage),
            onValueChange = { viewModel.rename(lineage.id, it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
        )
        SectionTitle("Category")
        ChipGroup {
            filterCategories.forEach { category ->
                FormChip(category.singularLabel, state.category(lineage) == category, enabled = !state.saving) { viewModel.categorize(lineage.id, category) }
            }
        }
        if (choice.category == null && shown.suggestedCategory != null) Small("Suggested by Wadrova. Change it if it's wrong.", Modifier.padding(top = 6.dp))
        SectionTitle("Color")
        ChipGroup {
            garmentColors.forEach { (name, tint) ->
                FormChip(name, state.color(lineage) == name, enabled = !state.saving, swatch = tint) { viewModel.color(lineage.id, name) }
            }
        }

        if (lineage.revisions.size > 1) {
            SectionTitle("Versions")
            Small("Earlier outlines of this piece. The one you pick is the one saved.")
            ThumbnailRow(state, lineage.revisions, selectedId = shown.id, enabled = !busy) { viewModel.chooseRevision(lineage.id, it.id) }
        }
        val alternatives = state.alternatives(lineage)
        if (alternatives.isNotEmpty()) {
            SectionTitle("Other outlines")
            Small("Other shapes Wadrova considered for this area. Choosing one makes a new version.")
            ThumbnailRow(state, alternatives, selectedId = null, enabled = !busy) { viewModel.selectAlternative(lineage.id, it) }
        }
        SectionTitle("Find it again")
        OutlinedTextField(
            value = state.reprompt,
            onValueChange = viewModel::updateReprompt,
            modifier = Modifier.fillMaxWidth().imePadding(),
            singleLine = true,
            enabled = !busy,
            placeholder = { Text("Describe it, for example \"denim jacket\"") },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
        )
        TextButton(onClick = { viewModel.submitReprompt(lineage.id) }, enabled = !busy && state.reprompt.isNotBlank()) { Text("Find again") }
        state.saveError?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        ActionButton(if (choice.selected) "Done" else "Select this piece", Modifier.padding(top = 20.dp), dark = true) {
            if (!choice.selected) viewModel.toggle(lineage.id)
            viewModel.closeDetail()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Eyebrow(text, Modifier.padding(top = 20.dp, bottom = 8.dp))
}

/**
 * The original photo, dimmed, with the piece's cutout placed at its box, so the outline is judged in context. Taps
 * become correction points in source-photo pixels; the local photo is the exact uploaded image, so frames match.
 */
@Composable
private fun OutlineOnPhoto(state: ReviewUiState, photoId: String, shown: Candidate, viewModel: GarmentReviewViewModel) {
    val loader = LocalPhotoLoader.current
    val photo by produceState<ImageBitmap?>(null, photoId, loader) { value = loader?.load(photoId, PhotoSize.DISPLAY) }
    val cutout = decoded(state.images[shown.cutout.id])
    BoxWithConstraints(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(16.dp)).background(WadrovaColors.Studio)) {
        val image = photo ?: return@BoxWithConstraints
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(image, state.points.size) {
                    detectTapGestures { tap ->
                        fitTapToImage(tap.x, tap.y, size.width.toFloat(), size.height.toFloat(), image.width, image.height)
                            ?.let { (x, y) -> viewModel.addPoint(x, y) }
                    }
                }
                .semantics { contentDescription = "Your photo with the piece outlined" },
        ) {
            val scale = minOf(size.width / image.width, size.height / image.height)
            val left = (size.width - image.width * scale) / 2
            val top = (size.height - image.height * scale) / 2
            drawImage(
                image,
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize((image.width * scale).toInt(), (image.height * scale).toInt()),
                alpha = 0.35f,
            )
            cutout?.let {
                drawImage(
                    it,
                    dstOffset = IntOffset((left + shown.box.x1 * scale).toInt(), (top + shown.box.y1 * scale).toInt()),
                    dstSize = IntSize((shown.box.width * scale).toInt().coerceAtLeast(1), (shown.box.height * scale).toInt().coerceAtLeast(1)),
                )
            }
            state.points.forEach { point ->
                val center = Offset(left + point.x * scale, top + point.y * scale)
                drawCircle(if (point.positive) WadrovaColors.Cobalt else Color(0xFFB6453C), radius = 9.dp.toPx(), center = center)
                drawCircle(Color.White, radius = 9.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun ThumbnailRow(state: ReviewUiState, candidates: List<Candidate>, selectedId: String?, enabled: Boolean, onPick: (Candidate) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        candidates.forEach { candidate ->
            Box(
                Modifier
                    .width(88.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(WadrovaColors.Studio)
                    .border(2.dp, if (candidate.id == selectedId) WadrovaColors.Cobalt else WadrovaColors.Line, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, role = Role.Button) { onPick(candidate) }
                    .semantics { contentDescription = "Version ${candidate.revision}" },
                contentAlignment = Alignment.Center,
            ) {
                CutoutImage(state, candidate, Modifier.fillMaxSize().padding(6.dp))
            }
        }
    }
}

/** A downloaded, checksum-verified cutout, or an honest placeholder while loading or after a failure. */
@Composable
private fun CutoutImage(state: ReviewUiState, candidate: Candidate, modifier: Modifier) {
    val bitmap = decoded(state.images[candidate.cutout.id])
    when {
        bitmap != null -> Image(bitmap, "Extracted ${candidate.phrase}", modifier, contentScale = ContentScale.Fit)
        candidate.cutout.id in state.failedImages -> Small("Image unavailable")
        else -> CircularProgressIndicator(Modifier.size(18.dp), color = WadrovaColors.Cobalt, strokeWidth = 2.dp)
    }
}

@Composable
private fun decoded(bytes: ByteArray?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = bytes?.let {
            try {
                withContext(Dispatchers.Default) { it.decodeToImageBitmap() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    return bitmap
}
