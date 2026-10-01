package com.evanchubbuck.jobtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

internal val JobPdfOptionsSaver = Saver<JobPdfOptions, String>(
    save = { options ->
        JSONObject().put("clients", options.clients).put("site", options.site).put("schedule", options.schedule)
            .put("work", options.work).put("inventory", options.inventory).put("workers", options.workers)
            .put("prices", options.prices).put("phones", options.phoneNumbers).put("photos", JSONArray().apply {
                options.photos.forEach { put(JSONObject().put("path", it.path).put("caption", it.caption).put("included", it.included)) }
            }).toString()
    },
    restore = { saved ->
        val json = JSONObject(saved)
        val photos = json.getJSONArray("photos")
        JobPdfOptions(json.getBoolean("clients"), json.getBoolean("site"), json.getBoolean("schedule"),
            json.getBoolean("work"), json.getBoolean("inventory"), json.getBoolean("workers"), json.getBoolean("phones"),
            List(photos.length()) { index -> photos.getJSONObject(index).let {
                JobPdfPhoto(it.getString("path"), it.getString("caption"), it.getBoolean("included"))
            } }, prices = json.optBoolean("prices", false))
    }
)

@Composable
internal fun JobPdfOptionsScreen(job: Job, options: JobPdfOptions, modifier: Modifier,
                                 creating: Boolean, onChange: (JobPdfOptions) -> Unit, onPreview: () -> Unit) {
    val invalidPrices = options.inventory && options.prices && job.inventory.any { !validMaterialPrice(it.price) }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("PDF", color = UiInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(job.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Choose what to include in the PDF.", color = UiInk)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        PdfChoice("Client names", options.clients, { onChange(options.copy(clients = it)) })
                        PdfChoice("Job site", options.site, { onChange(options.copy(site = it)) })
                        PdfChoice("Schedule", options.schedule, { onChange(options.copy(schedule = it)) })
                        PdfChoice("Work details", options.work, { onChange(options.copy(work = it)) })
                    }
                }
            }
            item {
                Text("Additional details", color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        PdfChoice("Materials", options.inventory, { onChange(options.copy(inventory = it, prices = if (it) options.prices else false)) })
                        if (options.inventory) PdfChoice("Material prices", options.prices, { onChange(options.copy(prices = it)) },
                            subtitle = "Optional; off by default")
                        PdfChoice("Outside workers", options.workers, { onChange(options.copy(workers = it)) })
                        PdfChoice("Phone numbers", options.phoneNumbers, { onChange(options.copy(phoneNumbers = it)) },
                            subtitle = "For the people you include")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Prices are only included when you select Material prices.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (options.photos.isNotEmpty()) {
                item {
                    Text("Photos", color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Select photos and add captions for this PDF.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(options.photos, key = { index, photo -> "${photo.path}-$index" }) { index, photo ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            PdfChoice("Photo ${index + 1}", photo.included, { included ->
                                onChange(options.copy(photos = options.photos.mapIndexed { i, p -> if (i == index) p.copy(included = included) else p }))
                            })
                            PhotoImage(photo.path, Modifier.fillMaxWidth().height(144.dp))
                            if (photo.included) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(photo.caption, { caption ->
                                    onChange(options.copy(photos = options.photos.mapIndexed { i, p -> if (i == index) p.copy(caption = caption) else p }))
                                }, label = { Text("Caption for photo ${index + 1}") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
        if (invalidPrices) Text("Check material prices in the job before including them.",
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
        Surface(shadowElevation = 4.dp) {
            Button(onClick = onPreview, enabled = !creating && !invalidPrices, modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (creating) "Preparing PDF…" else "Preview PDF")
            }
        }
    }
}

@Composable
private fun PdfChoice(label: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = UiInk)
            subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
