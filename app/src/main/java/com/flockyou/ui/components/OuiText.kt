package com.flockyou.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.data.oui.OuiLookupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for OUI lookups that can be shared across composables.
 * Uses a cache to avoid repeated database lookups for the same MAC address.
 */
@HiltViewModel
class OuiLookupViewModel @Inject constructor(
    private val ouiLookupService: OuiLookupService
) : ViewModel() {

    // Cache of OUI prefix -> manufacturer name
    private val cache = mutableMapOf<String, String?>()

    // Currently loading OUIs
    private val loading = mutableSetOf<String>()

    // State flow for reactive updates
    private val _lookupResults = MutableStateFlow<Map<String, String?>>(emptyMap())
    val lookupResults: StateFlow<Map<String, String?>> = _lookupResults.asStateFlow()

    /**
     * Lookup manufacturer for a MAC address or OUI prefix.
     * Returns cached result if available, otherwise triggers async lookup.
     */
    fun lookupManufacturer(macOrOui: String): String? {
        val normalized = normalizeOui(macOrOui)

        // Return cached result if available
        if (cache.containsKey(normalized)) {
            return cache[normalized]
        }

        // Skip if already loading
        if (loading.contains(normalized)) {
            return null
        }

        // Start async lookup
        loading.add(normalized)
        viewModelScope.launch {
            val result = ouiLookupService.getManufacturer(macOrOui)
            cache[normalized] = result
            loading.remove(normalized)
            _lookupResults.value = cache.toMap()
        }

        return null
    }

    /**
     * Get cached manufacturer name, or null if not yet looked up.
     */
    fun getCachedManufacturer(macOrOui: String): String? {
        val normalized = normalizeOui(macOrOui)
        return cache[normalized]
    }

    /**
     * Normalize OUI to a consistent format for caching.
     */
    private fun normalizeOui(oui: String): String {
        return oui.uppercase()
            .replace("-", "")
            .replace(":", "")
            .replace(".", "")
            .take(6)
    }
}

/**
 * Composable that displays an OUI/MAC address with auto-resolved manufacturer name.
 *
 * @param macAddress The MAC address or OUI prefix to look up
 * @param modifier Modifier for the composable
 * @param style Text style to apply
 * @param color Text color
 * @param showMac Whether to show the MAC address along with the manufacturer
 * @param maxLines Maximum number of lines
 * @param overflow Text overflow behavior
 */
@Composable
fun OuiText(
    macAddress: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    showMac: Boolean = false,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    viewModel: OuiLookupViewModel = hiltViewModel()
) {
    // Trigger lookup and observe results
    val lookupResults by viewModel.lookupResults.collectAsState()

    // Trigger the lookup on composition
    LaunchedEffect(macAddress) {
        viewModel.lookupManufacturer(macAddress)
    }

    // Get the manufacturer name (from cache or freshly looked up)
    val manufacturer = viewModel.getCachedManufacturer(macAddress)

    val displayText = when {
        manufacturer != null && showMac -> "$manufacturer ($macAddress)"
        manufacturer != null -> manufacturer
        else -> macAddress
    }

    Text(
        text = displayText,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow
    )
}

/**
 * Composable that displays a manufacturer name resolved from OUI.
 * If lookup fails or is pending, shows the fallback text.
 *
 * @param macAddress The MAC address or OUI prefix to look up
 * @param fallback Fallback text if manufacturer is not found
 * @param modifier Modifier for the composable
 * @param style Text style to apply
 * @param color Text color
 * @param maxLines Maximum number of lines
 * @param overflow Text overflow behavior
 */
@Composable
fun ManufacturerText(
    macAddress: String?,
    fallback: String = "Unknown",
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    viewModel: OuiLookupViewModel = hiltViewModel()
) {
    if (macAddress == null) {
        Text(
            text = fallback,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    // Trigger lookup and observe results
    val lookupResults by viewModel.lookupResults.collectAsState()

    // Trigger the lookup on composition
    LaunchedEffect(macAddress) {
        viewModel.lookupManufacturer(macAddress)
    }

    // Get the manufacturer name (from cache or freshly looked up)
    val manufacturer = viewModel.getCachedManufacturer(macAddress)

    Text(
        text = manufacturer ?: fallback,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow
    )
}

/**
 * Composable that displays a MAC address with optional manufacturer name.
 * Shows MAC in monospace font and manufacturer name in regular style.
 *
 * @param macAddress The MAC address to display
 * @param modifier Modifier for the composable
 * @param macStyle Text style for MAC address
 * @param manufacturerStyle Text style for manufacturer name
 * @param color Text color
 * @param showManufacturer Whether to lookup and show manufacturer
 */
@Composable
fun MacAddressWithManufacturer(
    macAddress: String,
    modifier: Modifier = Modifier,
    macStyle: TextStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
    manufacturerStyle: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    manufacturerColor: Color = MaterialTheme.colorScheme.tertiary,
    showManufacturer: Boolean = true,
    viewModel: OuiLookupViewModel = hiltViewModel()
) {
    // Trigger lookup and observe results
    val lookupResults by viewModel.lookupResults.collectAsState()

    // Trigger the lookup on composition
    LaunchedEffect(macAddress) {
        if (showManufacturer) {
            viewModel.lookupManufacturer(macAddress)
        }
    }

    // Get the manufacturer name
    val manufacturer = if (showManufacturer) {
        viewModel.getCachedManufacturer(macAddress)
    } else {
        null
    }

    Column(modifier = modifier) {
        Text(
            text = macAddress,
            style = macStyle,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (manufacturer != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = manufacturer,
                style = manufacturerStyle,
                color = manufacturerColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
