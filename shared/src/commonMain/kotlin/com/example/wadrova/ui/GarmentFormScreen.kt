package com.example.wadrova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.wadrova.data.PhotoSize

@Composable
fun GarmentFormScreen(
    state: WardrobeUiState,
    update: ((WardrobeUiState) -> WardrobeUiState) -> Unit,
    onSubmit: () -> Unit,
    onSetArchived: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onPhotoPicked: (PhotoPickResult) -> Unit,
    onRemovePhoto: () -> Unit,
) {
    val form = state.garmentForm ?: return
    val pickPhoto = rememberPhotoPicker(onPhotoPicked)
    val edit = { change: (GarmentFormState) -> GarmentFormState -> update { it.updateGarmentForm(change) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(WadrovaColors.Ivory)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp),
    ) {
        Eyebrow(if (form.editing == null) "Add to wardrobe" else "Your piece")
        Box(Modifier.padding(top = 6.dp, bottom = 16.dp)) {
            EditorialTitle(if (form.editing == null) "A new " else "Edit ", "piece", WadrovaColors.Cobalt)
        }

        PhotoSection(form, onPick = pickPhoto, onRemove = onRemovePhoto)

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
        ChipGroup {
            garmentColors.forEach { (name, tint) ->
                FormChip(
                    label = name,
                    selected = form.color == name,
                    enabled = !form.saving,
                    swatch = tint,
                ) { edit { it.copy(color = name) } }
            }
        }
        if (form.showErrors && form.color == null) FieldError("Choose a color.")

        SectionLabel("Details (optional)")
        FormTextField("Brand", form.brand, !form.saving) { brand -> edit { it.copy(brand = brand) } }
        FormTextField("Material", form.material, !form.saving, modifier = Modifier.padding(top = 8.dp)) { material ->
            edit { it.copy(material = material) }
        }

        form.error?.let { FieldError(it) }

        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Cancel", Modifier.weight(1f), enabled = !form.saving) { onCancel() }
            ActionButton(
                if (form.saving) "Saving…" else "Save piece",
                Modifier.weight(1f),
                dark = true,
                enabled = !form.saving && !form.importingPhoto,
                onClick = onSubmit,
            )
        }

        form.editing?.let { item ->
            ActionButton(
                if (item.archived) "Restore to wardrobe" else "Archive piece",
                Modifier.padding(top = 24.dp),
                enabled = !form.saving,
            ) { onSetArchived(!item.archived) }
            Hint(
                if (item.archived) {
                    "Restoring makes this piece available for new looks."
                } else {
                    "Archived pieces leave your wardrobe, but saved looks keep them."
                },
            )
        }
    }
}

@Composable
private fun PhotoSection(form: GarmentFormState, onPick: () -> Unit, onRemove: () -> Unit) {
    val hasPhoto = form.photoId != null
    Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(width = 120.dp, height = 150.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WadrovaColors.Studio),
            contentAlignment = Alignment.Center,
        ) {
            val photoId = form.photoId
            if (photoId != null) {
                PhotoOrFallback(photoId, PhotoSize.DISPLAY, "Photo of this piece", Modifier.fillMaxSize()) {
                    Hint("Photo unavailable")
                }
            } else {
                Text(
                    if (form.importingPhoto) "Adding…" else "No photo",
                    style = MaterialTheme.typography.bodySmall,
                    color = WadrovaColors.Ink2,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                when {
                    form.importingPhoto -> "Adding photo…"
                    hasPhoto -> "Replace photo"
                    else -> "Add photo"
                },
                enabled = !form.saving && !form.importingPhoto,
                onClick = onPick,
            )
            if (hasPhoto) {
                ActionButton("Remove photo", enabled = !form.saving && !form.importingPhoto, onClick = onRemove)
            }
            Hint("Stays on this device and is left out of cloud backups.")
        }
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
    Eyebrow(text, Modifier.padding(top = 24.dp, bottom = 10.dp))
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
        swatch?.let {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(it)
                    .border(1.dp, WadrovaColors.Charcoal.copy(alpha = 0.25f), CircleShape),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) WadrovaColors.Warm else WadrovaColors.Charcoal,
        )
    }
}
