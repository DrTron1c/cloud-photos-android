package io.github.drtron1c.cloudphotos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext

/** Drop photos or videos from another app (split screen, floating window) to add them to iCloud. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.dropToUpload(vm: MainViewModel): Modifier {
    val activity = LocalContext.current.findActivity()
    val target = remember(vm) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { vm.dropHover = true }
            override fun onExited(event: DragAndDropEvent) { vm.dropHover = false }
            override fun onEnded(event: DragAndDropEvent) { vm.dropHover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                vm.dropHover = false
                val e = event.toAndroidDragEvent()
                activity?.requestDragAndDropPermissions(e)
                val clip = e.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                vm.onDrop(uris)
                return uris.isNotEmpty()
            }
        }
    }
    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { ev ->
            val e = ev.toAndroidDragEvent()
            e.localState !== DRAG_FROM_HERE && vm.phase == Phase.Ready && vm.canUpload &&
                ev.mimeTypes().any { it.startsWith("image/") || it.startsWith("video/") }
        },
        target = target,
    )
}

/** Export: pick the format, then a folder (Files, USB drive, SD card, cloud drive…). */
@Composable
fun ExportFlow(vm: MainViewModel) {
    var pending by remember { mutableStateOf<ExportRequest?>(null) }
    var compatible by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val r = pending
        pending = null
        if (uri != null && r != null) vm.runExport(r, uri, compatible)
    }
    val request = vm.exportRequest ?: return
    val n = request.size
    AlertDialog(
        onDismissRequest = { vm.exportRequest = null },
        title = { Text(if (n == 1) "Export 1 item" else "Export %,d items".format(n)) },
        text = {
            Text(
                "Copies go to a folder you choose. “Unmodified Originals” keeps HEIC photos as they are; " +
                    "“Most Compatible” turns them into JPEG for any computer or app. Edited photos export with their edits.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                compatible = true; pending = request; vm.exportRequest = null
                vm.expectingExternal = true
                picker.launch(null)
            }) { Text("Most Compatible") }
        },
        dismissButton = {
            TextButton(onClick = {
                compatible = false; pending = request; vm.exportRequest = null
                vm.expectingExternal = true
                picker.launch(null)
            }) { Text("Unmodified Originals") }
        },
    )
}
