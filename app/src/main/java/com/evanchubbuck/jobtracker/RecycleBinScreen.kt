package com.evanchubbuck.jobtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RecycleBinScreen(entries: List<RecycledJob>, modifier: Modifier,
    onRestore: (String) -> Unit, onDeleteForever: (Set<String>) -> Unit) {
    var deletingId by remember { mutableStateOf<String?>(null) }
    var emptying by remember { mutableStateOf(false) }
    LazyColumn(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Recycle bin", color = UiInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Deleted jobs and drafts stay here until you restore them or delete them forever. Active jobs return as planned.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) Text("The Recycle bin is empty.", color = UiInk)
            else TextButton(onClick = { emptying = true }) { Text("Empty bin", color = MaterialTheme.colorScheme.error) }
        }
        items(entries.sortedByDescending { it.deletedAt }, key = { it.id }) { entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(entry.job.label, color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${if (entry.draft) "Draft" else "Job"} · Deleted ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.deletedAt))}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (entry.job.address.isNotBlank()) Text(entry.job.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onRestore(entry.id) }) { Text("Restore") }
                        TextButton(onClick = { deletingId = entry.id }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    entries.firstOrNull { it.id == deletingId }?.let { entry ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Delete ${entry.job.label} forever?") },
            text = { Text("This permanently removes it, including its costs and photos that aren't used elsewhere. You can't undo this.") },
            confirmButton = { TextButton(onClick = { onDeleteForever(setOf(entry.id)); deletingId = null }) {
                Text("Delete forever", color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } })
    }
    if (emptying) AlertDialog(onDismissRequest = { emptying = false }, title = { Text("Empty the Recycle bin?") },
        text = { Text("This permanently deletes everything in the bin. You can't undo this.") },
        confirmButton = { TextButton(onClick = { onDeleteForever(entries.map { it.id }.toSet()); emptying = false }) {
            Text("Empty bin", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { emptying = false }) { Text("Cancel") } })
}
