package com.example.wadrova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wadrova.data.PhotoSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarmentFormScreen(
    state: WardrobeUiState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    onSubmit: () -> Unit,
    onSetArchived: (Boolean) -> Unit,
    onRequestCancel: () -> Unit,
    onDiscard: () -> Unit,
    onPhotoPicked: (PhotoPickResult) -> Unit,
    onRemovePhoto: () -> Unit,
    onRemoveBackground: (WardrobeItem) -> Unit,
    onClearExtracted: (WardrobeItem) -> Unit,
) {
    val form = state.garmentForm ?: return
    val pickPhoto = rememberPhotoPicker(onPhotoPicked)
    val edit = { change: (GarmentFormState) -> GarmentFormState -> update { it.updateGarmentForm(change) } }
    var choosingColor by rememberSaveable { mutableStateOf(false) }
    var confirmingArchive by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(WadrovaColors.Ivory)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(WadrovaColors.Warm)
                    .border(1.dp, WadrovaColors.Line, CircleShape)
                    .clickable(enabled = !form.saving, role = Role.Button, onClick = onRequestCancel)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = WadrovaColors.Charcoal)
            }
            EditorialTitle(if (form.editing == null) "Add " else "Edit ", "piece", WadrovaColors.Cobalt)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 20.dp),
        ) {
            PhotoSection(form, onPickPhoto = pickPhoto, onRemovePhoto, onRemoveBackground, onClearExtracted)

            FormTextField(
                label = "Name",
                value = form.name,
                enabled = !form.saving,
                placeholder = "e.g. Field Jacket",
                error = if (form.showErrors && form.nameMissing) "Give this piece a name." else null,
            ) { name -> edit { it.copy(name = name) } }

            SectionLabel("Category")
            ChipGroup {
                filterCategories.forEach { category ->
                    FormChip(
                        label = category.singularLabel,
                        selected = form.category == category,
                        enabled = !form.saving && !form.categoryLocked,
                    ) { edit { it.copy(category = category) } }
                }
            }
            if (form.categoryLocked) {
                Hint("This piece is in a look, so its category can't change.")
            } else if (form.showErrors && form.category == null) {
                FieldError("Choose a category.")
            }

            SectionLabel("Color")
            ColorRow(form.color, enabled = !form.saving) { choosingColor = true }
            if (form.showErrors && form.color == null) FieldError("Choose a color.")

            SectionLabel("Details (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormTextField("Brand", form.brand, !form.saving, Modifier.weight(1f)) { brand -> edit { it.copy(brand = brand) } }
                FormTextField("Material", form.material, !form.saving, Modifier.weight(1f)) { material ->
                    edit { it.copy(material = material) }
                }
            }

            form.editing?.let { item ->
                HorizontalDivider(Modifier.padding(top = 28.dp, bottom = 4.dp), color = WadrovaColors.Line)
                TextAction(
                    if (item.archived) "Restore to wardrobe" else "Archive piece",
                    enabled = !form.saving,
                    color = WadrovaColors.Ink2,
                ) {
                    if (item.archived && !form.isDirty) onSetArchived(false) else confirmingArchive = true
                }
                Text(
                    if (item.archived) {
                        "Restoring makes this piece available for new looks."
                    } else {
                        "Archived pieces leave your wardrobe, but saved looks keep them."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = WadrovaColors.Ink2,
                )
            }
        }

        // The save bar sits above the keyboard, and its messages stay visible however far the form is scrolled.
        Column(
            Modifier
                .fillMaxWidth()
                .background(WadrovaColors.Ivory)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            HorizontalDivider(color = WadrovaColors.Line)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                val message = form.error ?: "Complete the required fields above.".takeIf { form.showErrors && !form.isValid }
                message?.let {
                    Text(
                        it,
                        Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ActionButton(
                    when {
                        form.saving -> "Saving…"
                        form.editing == null -> "Save piece"
                        else -> "Save changes"
                    },
                    dark = true,
                    enabled = !form.saving && !form.importingPhoto,
                    onClick = onSubmit,
                )
            }
        }
    }

    if (choosingColor) {
        ModalBottomSheet(
            onDismissRequest = { choosingColor = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = WadrovaColors.Warm,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    "Choose a color",
                    Modifier.padding(bottom = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = WadrovaColors.Charcoal,
                )
                ChipGroup {
                    garmentColors.forEach { (name, tint) ->
                        FormChip(label = name, selected = form.color == name, enabled = true, swatch = tint) {
                            edit { it.copy(color = name) }
                            choosingColor = false
                        }
                    }
                }
            }
        }
    }

    if (form.confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { update { it.keepEditingGarmentForm() } },
            title = { Text("Discard your changes?") },
            text = { Text("Your edits to this piece haven't been saved.") },
            confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { update { it.keepEditingGarmentForm() } }) { Text("Keep editing") } },
        )
    }

    form.editing?.takeIf { confirmingArchive }?.let { item ->
        val archiving = !item.archived
        AlertDialog(
            onDismissRequest = { confirmingArchive = false },
            title = { Text(if (archiving) "Archive this piece?" else "Restore this piece?") },
            text = {
                val effect = if (archiving) "It leaves your wardrobe, but saved looks keep it." else "It returns to your wardrobe."
                Text(if (form.isDirty) "$effect Your unsaved edits will be discarded." else effect)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingArchive = false
                    onSetArchived(archiving)
                }) { Text(if (archiving) "Archive" else "Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmingArchive = false }) { Text("Keep editing") } },
        )
    }
}

/**
 * The preview and everything that changes it. Extraction works on the saved photo,
 * so its actions are offered once a photo change has been saved.
 */
@Composable
private fun PhotoSection(
    form: GarmentFormState,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onRemoveBackground: (WardrobeItem) -> Unit,
    onClearExtracted: (WardrobeItem) -> Unit,
) {
    val busy = form.saving || form.importingPhoto
    val saved = form.editing?.takeIf { it.photoId != null && it.photoId == form.photoId }
    val extractedId = saved?.extractedImageId
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .size(width = 112.dp, height = 140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WadrovaColors.Studio),
            contentAlignment = Alignment.Center,
        ) {
            val photoId = form.photoId
            when {
                extractedId != null -> GeneratedImageOrFallback(extractedId, "Extracted image of this piece", Modifier.fillMaxSize()) {
                    Hint("Unavailable")
                }
                photoId != null -> PhotoOrFallback(photoId, PhotoSize.DISPLAY, "Photo of this piece", Modifier.fillMaxSize()) {
                    Hint("Photo unavailable")
                }
                else -> Text(
                    if (form.importingPhoto) "Adding…" else "No photo",
                    style = MaterialTheme.typography.bodySmall,
                    color = WadrovaColors.Ink2,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(WadrovaColors.Warm)
                    .border(1.dp, WadrovaColors.Line, CircleShape)
                    .clickable(enabled = !busy, role = Role.Button, onClick = onPickPhoto)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        form.importingPhoto -> "Adding photo…"
                        form.photoId != null -> "Change photo"
                        else -> "Add photo"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (busy) WadrovaColors.Ink2 else WadrovaColors.Charcoal,
                )
            }
            if (form.photoId != null) {
                TextAction("Remove photo", enabled = !busy, color = WadrovaColors.Ink2, onClick = onRemovePhoto)
            }
            if (saved != null) {
                TextAction("Remove background", enabled = !busy) { onRemoveBackground(saved) }
                if (extractedId != null) {
                    TextAction("Use original photo", enabled = !busy) { onClearExtracted(saved) }
                }
            }
        }
    }
    Hint(
        if (extractedId != null) {
            "Extracted image in use. Your original photo is kept."
        } else {
            "Stored on this device and left out of cloud backups."
        },
    )
    Spacer(Modifier.height(16.dp))
}

/** The chosen color. The full palette opens in a sheet, so it doesn't take over the form. */
@Composable
private fun ColorRow(color: String?, enabled: Boolean, onChange: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(WadrovaColors.Warm)
            .border(1.dp, WadrovaColors.Line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onChange)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        garmentColors.find { it.first == color }?.let { (_, tint) -> Swatch(tint, 18.dp) }
        Text(
            color ?: "No color chosen",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (color == null) WadrovaColors.Ink2 else WadrovaColors.Charcoal,
            maxLines = 1,
        )
        Text(
            if (color == null) "Choose color" else "Change color",
            style = MaterialTheme.typography.labelLarge,
            color = WadrovaColors.Cobalt,
        )
    }
}

@Composable
private fun TextAction(label: String, enabled: Boolean, color: Color = WadrovaColors.Charcoal, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

@Composable
private fun FormTextField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Eyebrow(text, Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = WadrovaColors.Ink2,
    )
}

@Composable
private fun FieldError(text: String) {
    Text(
        text,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun FormChip(label: String, selected: Boolean, enabled: Boolean, swatch: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .alpha(if (enabled || selected) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (selected) WadrovaColors.Charcoal else WadrovaColors.Warm)
            .border(1.dp, if (selected) WadrovaColors.Charcoal else WadrovaColors.Line, CircleShape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        swatch?.let { Swatch(it, 14.dp) }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) WadrovaColors.Warm else WadrovaColors.Charcoal,
        )
    }
}

@Composable
private fun Swatch(tint: Color, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint)
            .border(1.dp, WadrovaColors.Charcoal.copy(alpha = 0.25f), CircleShape),
    )
}
