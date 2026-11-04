package com.example.wadrova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wadrova.data.PhotoSize
import com.example.wadrova.domain.GarmentImport
import com.example.wadrova.domain.ImportState

private const val UPLOAD_NOTE =
    "Your photo is uploaded to Wadrova to find the pieces in it. Nothing is added to your wardrobe until you review and save."

/** The two ways to add a piece. Extracting opens the photo picker; the pick itself starts the import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPieceSheet(onExtract: () -> Unit, onManual: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WadrovaColors.Ivory,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            EditorialTitle("Add a ", "piece", WadrovaColors.Cobalt)
            AddMethod(
                "Extract from photo",
                "Choose a photo of a piece, a flat lay or an outfit. Wadrova finds the pieces for you to review.",
                Modifier.padding(top = 16.dp),
                onExtract,
            )
            AddMethod("Add manually", "Enter the details yourself, with an optional photo.", Modifier.padding(top = 10.dp), onManual)
            Small(UPLOAD_NOTE, Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun AddMethod(title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WadrovaColors.Warm)
            .border(1.dp, WadrovaColors.Line, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = WadrovaColors.Charcoal)
        Body(detail, Modifier.padding(top = 4.dp))
    }
}

/** Pending imports at the top of Your Items, until each is reviewed or removed. */
@Composable
fun PendingImports(imports: List<GarmentImport>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Imports")
        imports.forEach { item ->
            val status = item.status()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (item.state == ImportState.READY) WadrovaColors.CobaltSoft else WadrovaColors.Warm)
                    .border(1.dp, WadrovaColors.Line, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { onOpen(item.id) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(WadrovaColors.Studio), contentAlignment = Alignment.Center) {
                    PhotoOrFallback(item.photoId, PhotoSize.THUMBNAIL, "Imported photo", Modifier.fillMaxSize()) {}
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(status.title, style = MaterialTheme.typography.titleSmall, color = WadrovaColors.Charcoal)
                    status.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = WadrovaColors.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (status.working) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = WadrovaColors.Cobalt, strokeWidth = 2.dp)
                } else {
                    Text("›", style = MaterialTheme.typography.titleLarge, color = WadrovaColors.Ink2)
                }
            }
        }
    }
}

/** Preparing, uploading, processing and every outcome of one import. Leaving never stops the import. */
@Composable
fun ImportProgressScreen(state: ImportUiState, viewModel: GarmentImportViewModel, onChooseAnother: () -> Unit) {
    val route = state.route
    val item = (route as? ImportRoute.Progress)?.let { state.import(it.importId) }
    MirrorPage("Add a piece", "Extract from ", "photo", viewModel::close) {
        if (item == null) {
            // Preparing, or the picked photo couldn't be used. Without an error the import is about to appear.
            PhotoFrame { if (state.prepareError == null) Working() }
            val error = state.prepareError
            StatusText(
                if (error == null) ImportStatus("Preparing photo", "Saving a private copy on this device.", true) else ImportStatus("Couldn't use this photo", error, false),
            )
            if (error != null) {
                ActionButton("Choose another photo", Modifier.padding(top = 20.dp), dark = true, onClick = onChooseAnother)
                ActionButton("Back to wardrobe", Modifier.padding(top = 8.dp), onClick = viewModel::close)
            }
            return@MirrorPage
        }
        val status = item.status()
        PhotoFrame { PhotoOrFallback(item.photoId, PhotoSize.DISPLAY, "Your photo", Modifier.fillMaxSize()) { Small("Photo unavailable") } }
        StatusText(status)
        ImportActions(item, viewModel, onChooseAnother)
        Small(UPLOAD_NOTE, Modifier.padding(top = 20.dp))
    }
}

@Composable
private fun ImportActions(item: GarmentImport, viewModel: GarmentImportViewModel, onChooseAnother: () -> Unit) {
    val top = Modifier.padding(top = 20.dp)
    val next = Modifier.padding(top = 8.dp)
    when {
        item.needsConnection -> {
            ActionButton("Connect to Wadrova", top, dark = true, onClick = viewModel::openConnection)
            RemoveLink(item, viewModel)
        }
        item.canRetry -> {
            ActionButton("Try again", top, dark = true) { viewModel.retry(item.id) }
            RemoveLink(item, viewModel)
        }
        item.state == ImportState.READY && (item.candidateCount ?: 0) > 0 -> {
            ActionButton("Review pieces", top, dark = true) { viewModel.openReview(item.id) }
            RemoveLink(item, viewModel)
        }
        item.state == ImportState.READY -> {
            ActionButton("Choose another photo", top, dark = true, onClick = onChooseAnother)
            RemoveLink(item, viewModel)
        }
        item.state in setOf(ImportState.FAILED, ImportState.REJECTED, ImportState.CLOSED) -> {
            ActionButton("Remove", top, dark = true) { viewModel.requestRemoval(item.id) }
            ActionButton("Choose another photo", next, onClick = onChooseAnother)
        }
        item.state == ImportState.CANCEL_PENDING -> ActionButton("Back to wardrobe", top, onClick = viewModel::close)
        else -> {
            // Uploading or processing: there's nothing to wait for here.
            ActionButton("Back to wardrobe", top, onClick = viewModel::close)
            if (item.state == ImportState.PROCESSING) Small("You can leave this page. Wadrova keeps working on your photo.", Modifier.padding(top = 8.dp))
            RemoveLink(item, viewModel)
        }
    }
}

@Composable
private fun RemoveLink(item: GarmentImport, viewModel: GarmentImportViewModel) {
    TextButton(onClick = { viewModel.requestRemoval(item.id) }, Modifier.padding(top = 4.dp)) {
        Text(if (item.state == ImportState.READY) "Discard this photo" else "Cancel import", color = WadrovaColors.Ink2)
    }
}

/**
 * The entry point M4.5 replaces with the real review. It shows only what the server reported and says plainly
 * that nothing has been saved, so it can't be mistaken for added pieces.
 */
@Composable
fun ImportReviewPlaceholder(state: ImportUiState, viewModel: GarmentImportViewModel) {
    val item = (state.route as? ImportRoute.Review)?.let { state.import(it.importId) }
    MirrorPage("Review pieces", "Your ", "pieces", viewModel::close) {
        if (item != null) {
            PhotoFrame { PhotoOrFallback(item.photoId, PhotoSize.DISPLAY, "Your photo", Modifier.fillMaxSize()) { Small("Photo unavailable") } }
            item.status().detail?.let { Text(it, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, color = WadrovaColors.Charcoal) }
        }
        Body(
            "Reviewing and saving pieces comes in the next update. Nothing from this photo has been added to your wardrobe, and it stays here to review then.",
            Modifier.padding(top = 8.dp),
        )
        ActionButton("Back", Modifier.padding(top = 20.dp), onClick = viewModel::close)
    }
}

/** Development only, until sign-in exists. The token goes to the Keychain or Keystore and is never shown again. */
@Composable
fun ImportConnectionScreen(state: ImportUiState, viewModel: GarmentImportViewModel) {
    val form = state.connection
    MirrorPage("Development", "Wadrova ", "connection", viewModel::close) {
        Body("Extract from photo uses the Wadrova server. Until sign-in is available, enter its address and a development access token.")
        OutlinedTextField(
            value = form.url,
            onValueChange = { url -> viewModel.updateConnection(form.copy(url = url)) },
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            label = { Text("Server address (https://…)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
        )
        OutlinedTextField(
            value = form.token,
            onValueChange = { token -> viewModel.updateConnection(form.copy(token = token)) },
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            label = { Text(if (form.hasSavedToken) "Access token (saved; leave blank to keep)" else "Access token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(14.dp),
        )
        ActionButton(
            if (form.checking) "Checking…" else "Save and check connection",
            Modifier.padding(top = 16.dp),
            dark = true,
            enabled = !form.checking,
            onClick = viewModel::saveConnection,
        )
        if (form.hasSavedToken) {
            TextButton(onClick = viewModel::forgetToken, Modifier.padding(top = 4.dp), enabled = !form.checking) {
                Text("Remove saved token", color = WadrovaColors.Ink2)
            }
        }
        form.message?.let { Body(it, Modifier.padding(top = 12.dp)) }
        Small("The token is kept in this device's secure storage, never in the app database or in backups.", Modifier.padding(top = 12.dp))
    }
}

@Composable
fun ImportDialogs(state: ImportUiState, viewModel: GarmentImportViewModel) {
    val id = state.pendingRemoval ?: return
    val item = state.import(id) ?: return
    val (title, text, confirm) = when (item.state) {
        ImportState.READY -> Triple("Discard this photo?", "Its pieces won't be added to your wardrobe.", "Discard")
        ImportState.FAILED, ImportState.REJECTED, ImportState.CLOSED -> Triple("Remove this import?", "The photo's private copy on this device is deleted too.", "Remove")
        else -> Triple("Cancel this import?", "Wadrova stops looking for pieces in this photo. Nothing has been added to your wardrobe.", "Cancel import")
    }
    AlertDialog(
        onDismissRequest = viewModel::keepImport,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = viewModel::confirmRemoval) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = viewModel::keepImport) { Text("Keep") } },
    )
}

@Composable
private fun PhotoFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(WadrovaColors.Studio),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun Working() = CircularProgressIndicator(Modifier.size(28.dp), color = WadrovaColors.Cobalt, strokeWidth = 2.dp)

@Composable
private fun StatusText(status: ImportStatus) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(status.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = WadrovaColors.Charcoal)
        if (status.working) CircularProgressIndicator(Modifier.size(20.dp), color = WadrovaColors.Cobalt, strokeWidth = 2.dp)
    }
    status.detail?.let { Body(it, Modifier.padding(top = 6.dp)) }
}
