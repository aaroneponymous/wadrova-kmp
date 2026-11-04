package com.example.wadrova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wadrova.data.BackendConfig
import com.example.wadrova.data.PhotoSize
import com.example.wadrova.domain.ApprovedModel
import com.example.wadrova.domain.GarmentImageKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val PREVIEW_NOTE = "A visual preview only. It doesn't show how something will fit or what size to choose."

@Composable
internal fun MirrorPage(
    eyebrow: String?,
    lead: String,
    accent: String,
    onBack: (() -> Unit)?,
    onSettings: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(WadrovaColors.Ivory)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .padding(bottom = 14.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(WadrovaColors.Warm)
                    .border(1.dp, WadrovaColors.Line, CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = WadrovaColors.Charcoal)
            }
        }
        eyebrow?.let { Eyebrow(it, Modifier.padding(bottom = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { EditorialTitle(lead, accent, WadrovaColors.Cobalt) }
            onSettings?.let { SettingsButton(it) }
        }
        content()
    }
}

@Composable
internal fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = WadrovaColors.Ink2)
}

@Composable
internal fun Small(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = WadrovaColors.Ink2)
}

@Composable
private fun ErrorText(text: String?) {
    text?.let {
        Text(
            it,
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Generating(label: String, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(WadrovaColors.Studio)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = WadrovaColors.Cobalt)
        Text(
            label,
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
            color = WadrovaColors.Charcoal,
            textAlign = TextAlign.Center,
        )
        Small("This can take a few minutes.", Modifier.padding(top = 4.dp))
        ActionButton("Cancel", Modifier.padding(top = 16.dp), onClick = onCancel)
    }
}

/** A labelled, uncropped image tile. */
@Composable
private fun ImageTile(label: String, modifier: Modifier = Modifier, background: Color = WadrovaColors.Studio, content: @Composable () -> Unit) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        Small(label, Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StoredPhoto(photoId: String, description: String) {
    PhotoOrFallback(photoId, PhotoSize.DISPLAY, description, Modifier.fillMaxSize()) { Small("Image unavailable") }
}

@Composable
private fun StoredGenerated(imageId: String, description: String) {
    GeneratedImageOrFallback(imageId, description, Modifier.fillMaxSize()) { Small("Image unavailable") }
}

@Composable
fun MirrorHomeScreen(state: MirrorUiState, viewModel: MirrorViewModel, items: List<WardrobeItem>) {
    // Settings waits for the load, so it can't show and save a blank configuration over the stored one.
    val loaded = !state.loading && !state.loadFailed
    val onSettings = if (loaded) ({ viewModel.openRoute(MirrorRoute.SETTINGS) }) else null
    MirrorPage(null, "Your ", "mirror", onBack = null, onSettings = onSettings) {
        when {
            state.loading -> Body("Loading…")
            state.loadFailed -> {
                Body("Couldn't load Mirror.")
                ActionButton("Try again", Modifier.padding(top = 12.dp), onClick = viewModel::load)
            }
            else -> {
                val model = state.model
                if (model == null) {
                    EmptyMirror(viewModel::startCreateModel)
                    ErrorText(state.error)
                } else {
                    PopulatedMirror(model, state, viewModel, items)
                }
            }
        }
    }
}

@Composable
private fun PopulatedMirror(model: ApprovedModel, state: MirrorUiState, viewModel: MirrorViewModel, items: List<WardrobeItem>) {
    // The space the header, details, button and bottom bar take on a phone, so all of it fits without scrolling.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    var showPreviews by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Models are generated at 2:3, so this space is reserved before the image loads, and Fit shows all of any other shape.
        Box(
            Modifier
                .heightIn(max = (windowHeight - 356.dp).coerceIn(380.dp, 520.dp))
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(WadrovaColors.Studio),
            contentAlignment = Alignment.Center,
        ) {
            StoredGenerated(model.imageId, "Your approved model")
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Your model", style = MaterialTheme.typography.titleSmall, color = WadrovaColors.Charcoal)
            Small("Version ${model.version}")
        }
        ManageModelMenu(onNewVersion = viewModel::startCreateModel, onRemove = viewModel::requestRemoveModel)
    }
    ActionButton("Try on a piece", Modifier.padding(top = 12.dp), dark = true, onClick = viewModel::startTryOn)
    ErrorText(state.error)

    if (state.tryOns.isNotEmpty()) {
        ActionButton("Your saved previews", Modifier.padding(top = 8.dp)) { showPreviews = !showPreviews }
        if (showPreviews) {
            state.tryOns.forEach { result ->
                val name = items.find { it.garment.id == result.garmentId }?.name ?: "Removed piece"
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(WadrovaColors.Studio),
                    ) {
                        StoredGenerated(result.resultImageId, "Preview with $name")
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleSmall,
                            color = WadrovaColors.Charcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Small("Model version ${result.modelVersion}")
                    }
                    TextLink("Delete", "Delete preview with $name") { viewModel.deleteTryOn(result.id) }
                }
                HorizontalDivider(color = WadrovaColors.Line)
            }
            Small(PREVIEW_NOTE, Modifier.padding(top = 12.dp))
        }
    }
    Small("Generated images stay on this device and are left out of cloud backups.", Modifier.padding(top = 12.dp))
}

@Composable
private fun ManageModelMenu(onNewVersion: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextLink("Manage", "Manage your model") { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = WadrovaColors.Warm) {
            DropdownMenuItem(
                text = { Text("Create New Version", style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Charcoal) },
                onClick = {
                    open = false
                    onNewVersion()
                },
            )
            HorizontalDivider(color = WadrovaColors.Line)
            DropdownMenuItem(
                text = { Text("Remove Model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun EmptyMirror(onCreate: () -> Unit) {
    // The space the header, copy, button and bottom bar take on a phone, so all of it fits without scrolling.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val mirrorHeight = (windowHeight - 450.dp).coerceIn(200.dp, 340.dp)
    // On tall phones, half the spare height goes above the mirror so the composition doesn't sit high.
    val spare = (windowHeight - 450.dp - mirrorHeight).coerceAtLeast(0.dp)
    Column(Modifier.fillMaxWidth().padding(top = spare / 2), horizontalAlignment = Alignment.CenterHorizontally) {
        FittingMirror(Modifier.fillMaxWidth().height(mirrorHeight))
        Text(
            "A space for your style.",
            Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 30.sp),
            color = WadrovaColors.Charcoal,
            textAlign = TextAlign.Center,
        )
        Text(
            "Create your personal model to start trying on looks.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = WadrovaColors.Ink2,
            textAlign = TextAlign.Center,
        )
        ActionButton("Create your model", Modifier.padding(top = 16.dp), dark = true, onClick = onCreate)
    }
}

/** An arched full-length mirror on a plinth: thin graphite frame, and glass reflecting an empty room. */
@Composable
private fun FittingMirror(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val floor = size.height - 1.dp.toPx()
        val plinthHeight = 4.dp.toPx()
        val mirrorHeight = floor - plinthHeight
        val mirrorWidth = mirrorHeight * 0.46f
        val left = (size.width - mirrorWidth) / 2

        fun arch(inset: Float) = Path().apply {
            val l = left + inset
            val r = left + mirrorWidth - inset
            moveTo(l, mirrorHeight)
            lineTo(l, mirrorWidth / 2)
            arcTo(Rect(l, inset, r, mirrorWidth - inset), 180f, 180f, false)
            lineTo(r, mirrorHeight)
            close()
        }

        drawLine(WadrovaColors.Line, Offset(0f, floor), Offset(size.width, floor), 1.dp.toPx())
        drawRect(
            Brush.radialGradient(
                listOf(Color.Black.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width / 2, floor),
                radius = mirrorWidth * 0.8f,
            ),
            topLeft = Offset(left - mirrorWidth * 0.3f, floor - plinthHeight),
            size = Size(mirrorWidth * 1.6f, plinthHeight + 1.dp.toPx()),
        )

        val metal = Brush.horizontalGradient(
            listOf(WadrovaColors.Charcoal, Color(0xFF6A6E74), Color(0xFF3E4146), WadrovaColors.Charcoal),
            startX = left,
            endX = left + mirrorWidth,
        )
        drawPath(arch(0f), metal)
        drawRect(
            metal,
            topLeft = Offset(left - 6.dp.toPx(), mirrorHeight),
            size = Size(mirrorWidth + 12.dp.toPx(), plinthHeight),
        )

        val frame = 3.dp.toPx()
        val glass = arch(frame)
        clipPath(glass) {
            val horizon = mirrorHeight * 0.8f
            // The reflected wall and floor meet at a crisp line, which is what makes it read as glass.
            drawRect(
                Brush.verticalGradient(
                    listOf(Color(0xFFEAF0F3), Color(0xFFD3DCE2), Color(0xFFBCC7CF)),
                    endY = horizon,
                ),
                size = Size(size.width, horizon),
            )
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF9DA7AE), Color(0xFFC6CDD1)), startY = horizon, endY = mirrorHeight),
                topLeft = Offset(0f, horizon),
                size = Size(size.width, mirrorHeight - horizon),
            )
            // Two sharp streaks of window light, slanting across wall and floor.
            fun streak(start: Float, width: Float, alpha: Float) = drawPath(
                Path().apply {
                    val rise = mirrorHeight * 0.34f
                    moveTo(left + mirrorWidth * start, mirrorHeight)
                    lineTo(left + mirrorWidth * (start + width), mirrorHeight)
                    lineTo(left + mirrorWidth * (start + width) + rise, 0f)
                    lineTo(left + mirrorWidth * start + rise, 0f)
                    close()
                },
                Color.White.copy(alpha = alpha),
            )
            streak(-0.28f, 0.3f, 0.26f)
            streak(0.1f, 0.08f, 0.24f)
            // Glass darkens slightly toward its edges.
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.1f),
                    0.12f to Color.Transparent,
                    0.88f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.14f),
                    startX = left,
                    endX = left + mirrorWidth,
                ),
            )
        }
        drawPath(glass, Color.White.copy(alpha = 0.55f), style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Mirror Settings" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 1.6.dp.toPx()
            val unit = size.width / 24f
            drawCircle(WadrovaColors.Ink2, 3f * unit, style = Stroke(stroke))
            drawCircle(WadrovaColors.Ink2, 7f * unit, style = Stroke(stroke))
            repeat(8) { tooth ->
                val angle = tooth * PI.toFloat() / 4f
                val direction = Offset(cos(angle), sin(angle)) * unit
                drawLine(WadrovaColors.Ink2, center + direction * 7f, center + direction * 9.5f, stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun TextLink(label: String, description: String = label, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = WadrovaColors.Ink2)
    }
}

@Composable
fun CreateModelScreen(state: MirrorUiState, viewModel: MirrorViewModel) {
    val create = state.create ?: return
    val pickReplacement = rememberPhotoPicker(viewModel::onReferencePicked)
    val pickReferences = rememberMultiPhotoPicker(MAX_MODEL_REFERENCES - create.referenceIds.size, viewModel::onReferencesPicked)

    MirrorPage("Create your model", "Your ", "references", viewModel::cancelCreateModel) {
        val candidate = create.candidate
        when {
            create.generating -> Generating("Creating your model…", viewModel::cancelGeneration)
            candidate != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImageTile("Generated model", Modifier.weight(1f)) { StoredGenerated(candidate.imageId, "Generated model") }
                    create.referenceIds.firstOrNull()?.let { id ->
                        ImageTile("Your main photo", Modifier.weight(1f)) { StoredPhoto(id, "Your main reference photo") }
                    }
                }
                Body("Check that this looks like you. It's only used once you approve it.", Modifier.padding(top = 16.dp))
                ErrorText(create.error)
                ActionButton("Approve Model", Modifier.padding(top = 16.dp), dark = true, onClick = viewModel::approveModel)
                ActionButton("Regenerate", Modifier.padding(top = 8.dp)) { viewModel.requestConsent(ConsentRequest.BASE_MODEL) }
                ActionButton("Replace References", Modifier.padding(top = 8.dp), onClick = viewModel::replaceReferences)
                ActionButton("Cancel", Modifier.padding(top = 8.dp), onClick = viewModel::cancelCreateModel)
            }
            else -> {
                Body("Choose $MIN_MODEL_REFERENCES to $MAX_MODEL_REFERENCES photos of the same person. The first is the main reference for body and pose.")
                create.referenceIds.forEachIndexed { index, id ->
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(width = 64.dp, height = 96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(WadrovaColors.Studio),
                        ) {
                            PhotoOrFallback(id, PhotoSize.THUMBNAIL, "Reference photo ${index + 1}", Modifier.fillMaxSize()) {
                                Small("Unavailable")
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                if (index == 0) "Main reference" else "Reference ${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                color = WadrovaColors.Charcoal,
                            )
                            Row {
                                if (index != 0) TextLink("Make main", "Make reference ${index + 1} the main one") { viewModel.makePrimaryReference(id) }
                                TextLink("Replace", "Replace reference ${index + 1}") {
                                    if (!create.addingPhoto) {
                                        viewModel.beginReplaceReference(id)
                                        pickReplacement()
                                    }
                                }
                                TextLink("Remove", "Remove reference ${index + 1}") { viewModel.removeReference(id) }
                            }
                        }
                    }
                }
                if (create.referenceIds.size < MAX_MODEL_REFERENCES) {
                    ActionButton(
                        if (create.addingPhoto) "Adding photos…" else "Add Photos · ${create.referenceIds.size} of $MAX_MODEL_REFERENCES",
                        Modifier.padding(top = 16.dp),
                        enabled = create.canAddReference,
                        onClick = {
                            viewModel.clearReplaceReference()
                            pickReferences()
                        },
                    )
                }
                Row(
                    Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .toggleable(create.confirmedFullBody, role = Role.Checkbox, onValueChange = viewModel::setConfirmedFullBody),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        create.confirmedFullBody,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = WadrovaColors.Cobalt),
                    )
                    Body("These are all me, and at least one is a clear, front-facing, full-body photo.", Modifier.padding(start = 12.dp))
                }
                ErrorText(create.error)
                ActionButton(
                    "Create Model",
                    Modifier.padding(top = 16.dp),
                    dark = true,
                    enabled = create.canGenerate,
                ) { viewModel.requestConsent(ConsentRequest.BASE_MODEL) }
                ActionButton("Cancel", Modifier.padding(top = 8.dp), onClick = viewModel::cancelCreateModel)
            }
        }
    }
}

/** Shown over the garment form, which stays open underneath. */
@Composable
fun ExtractionScreen(state: MirrorUiState, viewModel: MirrorViewModel) {
    val extraction = state.extraction ?: return
    val item = extraction.item

    MirrorPage(item.name, "Remove ", "background", viewModel::keepOriginal) {
        val candidate = extraction.candidate
        when {
            extraction.generating -> Generating("Removing the background…", viewModel::cancelGeneration)
            candidate != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImageTile("Original photograph", Modifier.weight(1f)) {
                        item.photoId?.let { StoredPhoto(it, "Original photograph") }
                    }
                    ImageTile("Extracted · neutral", Modifier.weight(1f)) {
                        StoredGenerated(candidate.imageId, "Extracted garment on a neutral background")
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImageTile("Extracted · dark", Modifier.weight(1f), WadrovaColors.Charcoal) {
                        StoredGenerated(candidate.imageId, "Extracted garment on a dark background")
                    }
                    ImageTile("Extracted · bright", Modifier.weight(1f), WadrovaColors.Cobalt) {
                        StoredGenerated(candidate.imageId, "Extracted garment on a bright background")
                    }
                }
                Body("Check the edges and details. Your original photo stays either way.", Modifier.padding(top = 16.dp))
                ErrorText(extraction.error)
                ActionButton("Use Extracted Image", Modifier.padding(top = 16.dp), dark = true, onClick = viewModel::useExtractedImage)
                ActionButton("Regenerate", Modifier.padding(top = 8.dp)) { viewModel.requestConsent(ConsentRequest.GARMENT_EXTRACTION) }
                ActionButton("Keep Original", Modifier.padding(top = 8.dp), onClick = viewModel::keepOriginal)
            }
            else -> {
                ImageTile("Original photograph", Modifier.fillMaxWidth(0.6f)) {
                    item.photoId?.let { StoredPhoto(it, "Original photograph") }
                }
                ErrorText(extraction.error)
                ActionButton("Remove Background", Modifier.padding(top = 16.dp), dark = true) {
                    viewModel.requestConsent(ConsentRequest.GARMENT_EXTRACTION)
                }
                ActionButton("Keep Original", Modifier.padding(top = 8.dp), onClick = viewModel::keepOriginal)
            }
        }
    }
}

@Composable
fun TryOnScreen(state: MirrorUiState, viewModel: MirrorViewModel, items: List<WardrobeItem>) {
    val tryOn = state.tryOn ?: return
    val model = state.model ?: return
    val item = tryOn.item

    MirrorPage("Try on", "One ", "piece", viewModel::discardTryOn) {
        val candidate = tryOn.candidate
        when {
            item == null -> {
                val eligible = items.filter { !it.archived && (it.photoId != null || it.extractedImageId != null) }
                if (eligible.isEmpty()) {
                    Body("Add a photo to one of your own pieces to try it on.")
                } else {
                    Body("Choose one of your pieces.")
                    eligible.forEach { garment ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { viewModel.selectTryOnGarment(garment) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(WadrovaColors.Studio)
                                    .padding(4.dp),
                            ) {
                                GarmentVisual(garment, Modifier.fillMaxSize())
                            }
                            Text(
                                garment.name,
                                Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = WadrovaColors.Charcoal,
                            )
                        }
                        HorizontalDivider(color = WadrovaColors.Line)
                    }
                }
            }
            tryOn.generating -> Generating("Creating your preview…", viewModel::cancelGeneration)
            candidate != null -> {
                ImageTile("Generated try-on", Modifier.fillMaxWidth()) { StoredGenerated(candidate.imageId, "Generated try-on") }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImageTile("Original base model", Modifier.weight(1f)) { StoredGenerated(model.imageId, "Original base model") }
                    ImageTile("Garment reference", Modifier.weight(1f)) { GarmentSource(item, tryOn.sourceKind) }
                }
                Small(PREVIEW_NOTE, Modifier.padding(top = 12.dp))
                ErrorText(tryOn.error)
                ActionButton("Accept Preview", Modifier.padding(top = 16.dp), dark = true, onClick = viewModel::acceptTryOn)
                ActionButton("Regenerate", Modifier.padding(top = 8.dp)) { viewModel.requestConsent(ConsentRequest.TRY_ON) }
                ActionButton("Discard", Modifier.padding(top = 8.dp), onClick = viewModel::discardTryOn)
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImageTile("Your model · version ${model.version}", Modifier.weight(1f)) { StoredGenerated(model.imageId, "Your model") }
                    ImageTile(item.name, Modifier.weight(1f)) { GarmentSource(item, tryOn.sourceKind) }
                }
                if (item.photoId != null && item.extractedImageId != null) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            "Use Extracted",
                            Modifier.weight(1f),
                            dark = tryOn.sourceKind == GarmentImageKind.EXTRACTED,
                        ) { viewModel.setTryOnSource(GarmentImageKind.EXTRACTED) }
                        ActionButton(
                            "Use Photo",
                            Modifier.weight(1f),
                            dark = tryOn.sourceKind == GarmentImageKind.PHOTO,
                        ) { viewModel.setTryOnSource(GarmentImageKind.PHOTO) }
                    }
                }
                Small(PREVIEW_NOTE, Modifier.padding(top = 12.dp))
                ErrorText(tryOn.error)
                ActionButton("Try It On", Modifier.padding(top = 16.dp), dark = true) { viewModel.requestConsent(ConsentRequest.TRY_ON) }
                ActionButton("Back", Modifier.padding(top = 8.dp), onClick = viewModel::startTryOn)
            }
        }
    }
}

@Composable
private fun GarmentSource(item: WardrobeItem, kind: GarmentImageKind) {
    val extractedId = item.extractedImageId
    val photoId = item.photoId
    when {
        kind == GarmentImageKind.EXTRACTED && extractedId != null -> StoredGenerated(extractedId, "Extracted image of ${item.name}")
        photoId != null -> StoredPhoto(photoId, "Photo of ${item.name}")
    }
}

@Composable
fun MirrorSettingsScreen(state: MirrorUiState, viewModel: MirrorViewModel) {
    MirrorPage("Mirror", "Development ", "backend", { viewModel.openRoute(MirrorRoute.HOME) }) {
        Body("Mirror uses your own local backend, which holds the OpenAI key. The key is never stored in this app.")
        OutlinedTextField(
            value = state.config.url,
            onValueChange = { url -> viewModel.updateConfig(state.config.copy(url = url)) },
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            label = { Text("Backend address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
        )
        // Masked, and empty even when a token is saved, so it never shows on screen or in the accessibility tree.
        OutlinedTextField(
            value = state.tokenDraft,
            onValueChange = viewModel::updateTokenDraft,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            label = { Text(if (state.config.token.isNotEmpty()) "Development token (saved; leave blank to keep)" else "Development token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
        )
        ActionButton(
            if (state.checkingConnection) "Checking…" else "Save and Check Connection",
            Modifier.padding(top = 16.dp),
            dark = true,
            enabled = !state.checkingConnection,
            onClick = viewModel::saveConfigAndCheck,
        )
        state.connectionMessage?.let { Body(it, Modifier.padding(top = 12.dp)) }
        Small("Checking only asks your backend about its setup. It doesn't contact OpenAI.", Modifier.padding(top = 12.dp))
    }
}

/** Consent is asked for every send, and only "Send to OpenAI" lets images leave the device. */
@Composable
fun MirrorDialogs(state: MirrorUiState, viewModel: MirrorViewModel) {
    state.consent?.let { request ->
        val detail = when (request) {
            ConsentRequest.BASE_MODEL -> "Your reference photos of yourself will be sent through your backend to OpenAI to create your model."
            ConsentRequest.GARMENT_EXTRACTION -> "This garment photo will be sent through your backend to OpenAI to remove its background."
            ConsentRequest.TRY_ON -> "Your personal model image and this garment image will be sent through your backend to OpenAI to create a preview."
        }
        AlertDialog(
            onDismissRequest = viewModel::declineConsent,
            title = { Text("Send to OpenAI?") },
            text = { Text("$detail This may use paid generation on your API account.") },
            confirmButton = { TextButton(onClick = viewModel::confirmConsent) { Text("Send to OpenAI") } },
            dismissButton = { TextButton(onClick = viewModel::declineConsent) { Text("Don't Send") } },
        )
    }
    if (state.confirmingRemoval) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRemoveModel,
            title = { Text("Remove your model?") },
            text = { Text("This deletes every version of your model and all saved previews from this device.") },
            confirmButton = { TextButton(onClick = viewModel::removeModelAndPreviews) { Text("Remove") } },
            dismissButton = { TextButton(onClick = viewModel::cancelRemoveModel) { Text("Keep") } },
        )
    }
}
