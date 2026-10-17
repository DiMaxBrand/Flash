package com.example.flash.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings > Simulation: make this phone act as a sender or a receiver with generated photos, over a
 * fast or a slow (one second per file) imitated network. Shared by the Liquid and Expressive screens.
 */
@Composable
fun SimulationDialog(viewModel: WorkbenchViewModel, onDismiss: () -> Unit) {
    var role by rememberSaveable { mutableStateOf(SimRole.RECEIVER) }
    var speed by rememberSaveable { mutableStateOf(SimSpeed.SLOW) }
    var count by rememberSaveable { mutableStateOf(3) }
    var content by rememberSaveable { mutableStateOf(SimContent.MIXED) }
    var damage by rememberSaveable { mutableStateOf(SimDamage.NONE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Simulation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Acts like a real transfer with generated pictures. No network, nothing is saved to the gallery.",
                    style = MaterialTheme.typography.bodySmall
                )
                Choice("I am the", SimRole.entries, role, { role = it }) { if (it == SimRole.RECEIVER) "Receiver" else "Sender" }
                Choice("Network", SimSpeed.entries, speed, { speed = it }) { if (it == SimSpeed.FAST) "Fast" else "Slow (photo 1 s, video 6 s)" }
                Choice("Sending", SimContent.entries, content, { content = it }) {
                    when (it) {
                        SimContent.PHOTOS -> "Photos"
                        SimContent.MIXED -> "Mixed (photo, video, document)"
                        SimContent.VIDEOS -> "Videos"
                        SimContent.FILES -> "Documents"
                    }
                }
                Choice("Photos", listOf(1, 3, 5, 8, 15), count, { count = it }) { it.toString() }
                if (role == SimRole.RECEIVER) {
                    Choice("Photo 2 arrives", SimDamage.entries, damage, { damage = it }) {
                        when (it) {
                            SimDamage.NONE -> "intact"
                            SimDamage.RECOVERS -> "damaged, fixed by retry"
                            SimDamage.STAYS -> "damaged for good"
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.startSimulation(role, speed, count, if (role == SimRole.RECEIVER) damage else SimDamage.NONE, content)
                onDismiss()
            }) { Text("Start") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { viewModel.stopSimulation(); onDismiss() }) { Text("Stop") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun <T> Choice(title: String, items: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
        ) {
            items.forEach { item ->
                FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
            }
        }
    }
}
