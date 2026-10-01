package com.evanchubbuck.jobtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun JobSiteEditor(
    address: String,
    onAddressChange: (String) -> Unit,
    onNavigate: () -> Unit,
    lookup: AddressLookup = run {
        val context = LocalContext.current
        remember(context) { JobAddressLookup(DeviceAddressLookup(context), PhotonAddressLookup(),
            currentAddressCountry(context), CensusAddressLookup()) }
    },
    locationProvider: AddressLocationProvider? = run {
        val context = LocalContext.current
        remember(context) { DeviceAddressLocationProvider(context) }
    },
    showNavigate: Boolean = true,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val latestChange by rememberUpdatedState(onAddressChange)
    var focused by remember { mutableStateOf(false) }
    var searchRequested by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var searchMessage by remember { mutableStateOf("") }
    var selectionMessage by remember { mutableStateOf("") }
    var nearby by remember { mutableStateOf<AddressPoint?>(null) }
    var locationAllowed by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    ) }
    var locationRequested by remember { mutableStateOf(false) }
    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationAllowed = granted
    }

    LaunchedEffect(focused, locationAllowed, locationProvider) {
        if (focused && !locationAllowed && !locationRequested && locationProvider != null) {
            locationRequested = true
            requestLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    LaunchedEffect(locationAllowed, locationProvider) {
        if (locationAllowed && locationProvider != null) nearby = locationProvider.current()
    }

    fun applyAddress(value: String) {
        searchRequested = false
        suggestions = emptyList()
        searchMessage = ""
        latestChange(value)
        focus.clearFocus()
    }

    LaunchedEffect(address, focused, searchRequested, nearby) {
        suggestions = emptyList()
        searchMessage = ""
        searching = false
        if (!focused || !searchRequested || address.trim().length < 3) return@LaunchedEffect
        delay(300)
        searching = true
        try {
            suggestions = lookup.search(address.trim().take(200), nearby)
            if (suggestions.isEmpty()) searchMessage =
                "No suggestions found. You can use the address you typed, or add a city and try again."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            searchMessage = (error as? AddressLookupException)?.explanation
                ?: "Address search is unavailable. You can still type the address."
        } finally { searching = false }
    }

    Column {
        OutlinedTextField(address, { value ->
            selectionMessage = ""
            suggestions = emptyList()
            searchRequested = true
            latestChange(value)
        }, label = { Text("Street address") }, minLines = 2,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        Spacer(Modifier.height(8.dp))
        if (searching) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Finding addresses…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (suggestions.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    suggestions.forEachIndexed { index, suggestion ->
                        ListItem(headlineContent = { Text(suggestion.street) },
                            supportingContent = {
                                Column {
                                    if (suggestion.area.isNotBlank()) Text(suggestion.area)
                                    if (suggestion.rangeMatch)
                                        Text("Possible address. Check the street and city before selecting.")
                                    else if (suggestion.numberFromEntry)
                                        Text("We found the street, but not this specific house number. The number you typed is kept.")
                                    else if (suggestion.houseNumber.isBlank()) Text("Street only")
                                }
                            },
                            modifier = Modifier.clickable(role = Role.Button) {
                                applyAddress(suggestion.address)
                                selectionMessage = when {
                                    suggestion.rangeMatch -> "Possible address selected. Check it before saving."
                                    suggestion.numberFromEntry -> "Your house number is kept. Check the address before saving."
                                    else -> ""
                                }
                            })
                        if (index < suggestions.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        if (searchMessage.isNotBlank()) Text(searchMessage, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (selectionMessage.isNotBlank()) Text(selectionMessage, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showNavigate && address.isNotBlank()) OutlinedButton(onClick = onNavigate) { Text("Navigate") }
        Spacer(Modifier.height(12.dp))
        Text("Suggestions stay in your country. Add a city for more precise results; nearby matches are favored when your phone can find your location.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("© OpenStreetMap contributors · Photon", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")))
            }.padding(vertical = 8.dp))
    }
}
