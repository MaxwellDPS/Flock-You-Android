@file:OptIn(ExperimentalMaterial3Api::class)

package com.flockyou.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flockyou.data.*
import com.flockyou.data.model.DetectionPatterns
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.PatternType
import com.flockyou.ui.theme.*

/**
 * All Detections Screen - Shows all detection patterns (built-in + custom + heuristic)
 * in a unified, browsable view with the ability to manage custom rules
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllDetectionsScreen(
    viewModel: RuleSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showAddRuleSheet by remember { mutableStateOf(false) }
    var showAddHeuristicSheet by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomRule?>(null) }
    var editingHeuristicRule by remember { mutableStateOf<HeuristicRule?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedScannerType by remember { mutableStateOf<ScannerType?>(null) }

    val tabs = listOf("All Patterns", "Custom Rules", "Heuristic Rules", "Categories")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("All Detections")
                        Text(
                            text = "Pattern matching and behavioral rules",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Show appropriate add button based on tab
                    when (selectedTab) {
                        1 -> IconButton(onClick = { showAddRuleSheet = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Custom Rule")
                        }
                        2 -> IconButton(onClick = { showAddHeuristicSheet = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Heuristic Rule")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Summary stats card
            DetectionSummaryCard(
                builtInCount = DetectionPatterns.ssidPatterns.size +
                    DetectionPatterns.bleNamePatterns.size +
                    DetectionPatterns.macPrefixes.size +
                    DetectionPatterns.ravenServiceUuids.size,
                customCount = settings.customRules.size,
                heuristicCount = settings.heuristicRules.size,
                enabledCustomCount = settings.customRules.count { it.enabled },
                enabledHeuristicCount = settings.heuristicRules.count { it.enabled }
            )

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search patterns and rules...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Scanner Type Filter (horizontal scroll)
            ScannerTypeFilterRow(
                selectedType = selectedScannerType,
                onTypeSelected = { selectedScannerType = if (selectedScannerType == it) null else it }
            )

            // Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title)
                                when (index) {
                                    1 -> if (settings.customRules.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge { Text("${settings.customRules.size}") }
                                    }
                                    2 -> if (settings.heuristicRules.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Badge { Text("${settings.heuristicRules.size}") }
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Content
            when (selectedTab) {
                0 -> AllPatternsTab(
                    searchQuery = searchQuery,
                    customRules = settings.customRules,
                    selectedCategory = selectedCategory,
                    selectedScannerType = selectedScannerType,
                    onCategorySelected = { selectedCategory = if (selectedCategory == it) null else it }
                )
                1 -> CustomRulesTabEnhanced(
                    customRules = settings.customRules,
                    searchQuery = searchQuery,
                    selectedScannerType = selectedScannerType,
                    onToggleRule = viewModel::toggleCustomRule,
                    onEditRule = { editingRule = it },
                    onDeleteRule = viewModel::deleteCustomRule,
                    onAddRule = { showAddRuleSheet = true }
                )
                2 -> HeuristicRulesTab(
                    heuristicRules = settings.heuristicRules,
                    searchQuery = searchQuery,
                    selectedScannerType = selectedScannerType,
                    onToggleRule = viewModel::toggleHeuristicRule,
                    onEditRule = { editingHeuristicRule = it },
                    onDeleteRule = viewModel::deleteHeuristicRule,
                    onAddRule = { showAddHeuristicSheet = true }
                )
                3 -> CategoriesTab(
                    settings = settings,
                    onToggleCategory = viewModel::toggleCategory
                )
            }
        }
    }

    // Add/Edit custom rule bottom sheet
    if (showAddRuleSheet || editingRule != null) {
        AddEditRuleBottomSheet(
            existingRule = editingRule,
            onDismiss = {
                showAddRuleSheet = false
                editingRule = null
            },
            onSave = { rule ->
                if (editingRule != null) {
                    viewModel.updateCustomRule(rule)
                } else {
                    viewModel.addCustomRule(rule)
                }
                showAddRuleSheet = false
                editingRule = null
            }
        )
    }

    // Add/Edit heuristic rule bottom sheet
    if (showAddHeuristicSheet || editingHeuristicRule != null) {
        AddEditHeuristicRuleBottomSheet(
            existingRule = editingHeuristicRule,
            onDismiss = {
                showAddHeuristicSheet = false
                editingHeuristicRule = null
            },
            onSave = { rule ->
                if (editingHeuristicRule != null) {
                    viewModel.updateHeuristicRule(rule)
                } else {
                    viewModel.addHeuristicRule(rule)
                }
                showAddHeuristicSheet = false
                editingHeuristicRule = null
            }
        )
    }
}

@Composable
private fun ScannerTypeFilterRow(
    selectedType: ScannerType?,
    onTypeSelected: (ScannerType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ScannerType.entries.forEach { scannerType ->
            FilterChip(
                selected = selectedType == scannerType,
                onClick = { onTypeSelected(scannerType) },
                label = { Text(scannerType.displayName, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (selectedType == scannerType) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

@Composable
private fun DetectionSummaryCard(
    builtInCount: Int,
    customCount: Int,
    heuristicCount: Int,
    enabledCustomCount: Int,
    enabledHeuristicCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryStatBox(
                count = builtInCount,
                label = "Built-in",
                icon = Icons.Default.Security
            )
            SummaryStatBox(
                count = customCount,
                label = "Pattern",
                icon = Icons.Default.Edit,
                subtitle = if (customCount > 0) "$enabledCustomCount enabled" else null
            )
            SummaryStatBox(
                count = heuristicCount,
                label = "Heuristic",
                icon = Icons.Default.Psychology,
                subtitle = if (heuristicCount > 0) "$enabledHeuristicCount enabled" else null
            )
            SummaryStatBox(
                count = builtInCount + customCount + heuristicCount,
                label = "Total",
                icon = Icons.Default.Visibility,
                isPrimary = true
            )
        }
    }
}

@Composable
private fun SummaryStatBox(
    count: Int,
    label: String,
    icon: ImageVector,
    subtitle: String? = null,
    isPrimary: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllPatternsTab(
    searchQuery: String,
    customRules: List<CustomRule>,
    selectedCategory: String?,
    selectedScannerType: ScannerType?,
    onCategorySelected: (String) -> Unit
) {
    // Combine all patterns into unified list
    data class UnifiedPattern(
        val id: String,
        val name: String,
        val pattern: String,
        val type: String,
        val category: String,
        val scannerType: ScannerType,
        val deviceType: DeviceType,
        val threatScore: Int,
        val description: String,
        val manufacturer: String?,
        val isCustom: Boolean,
        val isEnabled: Boolean = true
    )

    val allPatterns = remember(customRules, searchQuery, selectedCategory, selectedScannerType) {
        val patterns = mutableListOf<UnifiedPattern>()

        // Add SSID patterns
        DetectionPatterns.ssidPatterns.forEach { p ->
            patterns.add(UnifiedPattern(
                id = "ssid_${p.pattern}",
                name = p.pattern.removePrefix("(?i)^").removeSuffix(".*").removeSuffix("[_-]?"),
                pattern = p.pattern,
                type = "SSID",
                category = when (p.deviceType) {
                    DeviceType.FLOCK_SAFETY_CAMERA -> "Flock Safety"
                    DeviceType.RAVEN_GUNSHOT_DETECTOR -> "Acoustic Sensors"
                    DeviceType.MOTOROLA_POLICE_TECH, DeviceType.AXON_POLICE_TECH,
                    DeviceType.L3HARRIS_SURVEILLANCE, DeviceType.CELLEBRITE_FORENSICS,
                    DeviceType.BODY_CAMERA, DeviceType.POLICE_RADIO, DeviceType.STINGRAY_IMSI -> "Police Tech"
                    else -> "Generic"
                },
                scannerType = ScannerType.WIFI,
                deviceType = p.deviceType,
                threatScore = p.threatScore,
                description = p.description,
                manufacturer = p.manufacturer,
                isCustom = false
            ))
        }

        // Add BLE patterns
        DetectionPatterns.bleNamePatterns.forEach { p ->
            patterns.add(UnifiedPattern(
                id = "ble_${p.pattern}",
                name = p.pattern.removePrefix("(?i)^").removeSuffix(".*").removeSuffix("[_-]?"),
                pattern = p.pattern,
                type = "BLE",
                category = if (p.deviceType == DeviceType.RAVEN_GUNSHOT_DETECTOR) "Acoustic Sensors"
                    else if (p.deviceType == DeviceType.FLOCK_SAFETY_CAMERA) "Flock Safety"
                    else "Generic",
                scannerType = ScannerType.BLUETOOTH,
                deviceType = p.deviceType,
                threatScore = p.threatScore,
                description = p.description,
                manufacturer = p.manufacturer,
                isCustom = false
            ))
        }

        // Add MAC prefixes
        DetectionPatterns.macPrefixes.forEach { p ->
            patterns.add(UnifiedPattern(
                id = "mac_${p.prefix}",
                name = p.prefix,
                pattern = p.prefix,
                type = "MAC",
                category = "MAC Prefixes",
                scannerType = ScannerType.WIFI,
                deviceType = p.deviceType,
                threatScore = p.threatScore,
                description = p.description,
                manufacturer = p.manufacturer,
                isCustom = false
            ))
        }

        // Add custom rules
        customRules.forEach { rule ->
            patterns.add(UnifiedPattern(
                id = "custom_${rule.id}",
                name = rule.name,
                pattern = rule.pattern,
                type = rule.type.displayName,
                category = "Custom",
                scannerType = rule.scannerType,
                deviceType = rule.deviceType,
                threatScore = rule.threatScore,
                description = rule.description,
                manufacturer = null,
                isCustom = true,
                isEnabled = rule.enabled
            ))
        }

        // Filter by search query, category, and scanner type
        patterns.filter { p ->
            val matchesSearch = searchQuery.isEmpty() ||
                p.name.contains(searchQuery, ignoreCase = true) ||
                p.pattern.contains(searchQuery, ignoreCase = true) ||
                p.description.contains(searchQuery, ignoreCase = true) ||
                p.deviceType.name.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == null || p.category == selectedCategory
            val matchesScannerType = selectedScannerType == null || p.scannerType == selectedScannerType
            matchesSearch && matchesCategory && matchesScannerType
        }.sortedWith(compareBy({ !it.isCustom }, { it.category }, { it.threatScore * -1 }))
    }

    // Category filter chips
    val categories = listOf("Flock Safety", "Police Tech", "Acoustic Sensors", "MAC Prefixes", "Generic", "Custom")

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Category filter row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.filter { cat ->
                    allPatterns.any { it.category == cat } || cat == selectedCategory
                }.forEach { category ->
                    val count = allPatterns.count { it.category == category }
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { onCategorySelected(category) },
                        label = {
                            Text("$category ($count)", style = MaterialTheme.typography.labelSmall)
                        }
                    )
                }
            }
        }

        if (allPatterns.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No patterns found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(allPatterns, key = { it.id }) { pattern ->
                UnifiedPatternCard(
                    pattern = pattern.name,
                    fullPattern = pattern.pattern,
                    type = pattern.type,
                    category = pattern.category,
                    scannerType = pattern.scannerType,
                    deviceType = pattern.deviceType,
                    threatScore = pattern.threatScore,
                    description = pattern.description,
                    manufacturer = pattern.manufacturer,
                    isCustom = pattern.isCustom,
                    isEnabled = pattern.isEnabled
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnifiedPatternCard(
    pattern: String,
    fullPattern: String,
    type: String,
    category: String,
    scannerType: ScannerType,
    deviceType: DeviceType,
    threatScore: Int,
    description: String,
    manufacturer: String?,
    isCustom: Boolean,
    isEnabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    val threatColor = when {
        threatScore >= 90 -> ThreatCritical
        threatScore >= 75 -> ThreatHigh
        threatScore >= 50 -> ThreatMedium
        threatScore >= 25 -> ThreatLow
        else -> ThreatInfo
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (isCustom && !isEnabled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scanner type icon
                    Icon(
                        imageVector = when (scannerType) {
                            ScannerType.WIFI -> Icons.Default.Wifi
                            ScannerType.BLUETOOTH -> Icons.Default.Bluetooth
                            ScannerType.CELLULAR -> Icons.Default.CellTower
                            ScannerType.SATELLITE -> Icons.Default.SatelliteAlt
                            ScannerType.GNSS -> Icons.Default.GpsFixed
                            ScannerType.RF -> Icons.Default.Radio
                            ScannerType.ULTRASONIC -> Icons.Default.GraphicEq
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    // Custom badge
                    if (isCustom) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "CUSTOM",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pattern,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        manufacturer?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Threat score badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = threatColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "$threatScore%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = threatColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Type badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = type,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detects: ${deviceType.name.replace("_", " ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded content
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Full Pattern:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = fullPattern,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomRulesTabEnhanced(
    customRules: List<CustomRule>,
    searchQuery: String,
    selectedScannerType: ScannerType?,
    onToggleRule: (String, Boolean) -> Unit,
    onEditRule: (CustomRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onAddRule: () -> Unit
) {
    val filteredRules = remember(customRules, searchQuery, selectedScannerType) {
        customRules.filter {
            val matchesSearch = searchQuery.isEmpty() ||
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.pattern.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
            val matchesScannerType = selectedScannerType == null || it.scannerType == selectedScannerType
            matchesSearch && matchesScannerType
        }
    }

    if (filteredRules.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (searchQuery.isEmpty() && selectedScannerType == null) "No Custom Rules" else "No Matching Rules",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (searchQuery.isEmpty() && selectedScannerType == null)
                        "Add pattern-based rules to detect specific devices"
                    else
                        "Try a different search or filter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (searchQuery.isEmpty() && selectedScannerType == null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onAddRule) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Custom Rule")
                    }
                }
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredRules, key = { it.id }) { rule ->
                EnhancedCustomRuleCard(
                    rule = rule,
                    onToggle = { onToggleRule(rule.id, it) },
                    onEdit = { onEditRule(rule) },
                    onDelete = { onDeleteRule(rule.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnhancedCustomRuleCard(
    rule: CustomRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val threatColor = when {
        rule.threatScore >= 90 -> ThreatCritical
        rule.threatScore >= 75 -> ThreatHigh
        rule.threatScore >= 50 -> ThreatMedium
        rule.threatScore >= 25 -> ThreatLow
        else -> ThreatInfo
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Scanner type icon
                        Icon(
                            imageVector = when (rule.scannerType) {
                                ScannerType.WIFI -> Icons.Default.Wifi
                                ScannerType.BLUETOOTH -> Icons.Default.Bluetooth
                                ScannerType.CELLULAR -> Icons.Default.CellTower
                                ScannerType.SATELLITE -> Icons.Default.SatelliteAlt
                                ScannerType.GNSS -> Icons.Default.GpsFixed
                                ScannerType.RF -> Icons.Default.Radio
                                ScannerType.ULTRASONIC -> Icons.Default.GraphicEq
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = rule.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (!rule.enabled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = "DISABLED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = rule.type.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = threatColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Score: ${rule.threatScore}",
                                style = MaterialTheme.typography.labelSmall,
                                color = threatColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pattern display
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = rule.pattern,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (rule.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = rule.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Expanded content with actions
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Detects: ${rule.deviceType.name.replace("_", " ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row {
                            TextButton(onClick = onEdit) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit")
                            }
                            TextButton(
                                onClick = { showDeleteDialog = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete Rule?") },
            text = { Text("Delete \"${rule.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HeuristicRulesTab(
    heuristicRules: List<HeuristicRule>,
    searchQuery: String,
    selectedScannerType: ScannerType?,
    onToggleRule: (String, Boolean) -> Unit,
    onEditRule: (HeuristicRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onAddRule: () -> Unit
) {
    val filteredRules = remember(heuristicRules, searchQuery, selectedScannerType) {
        heuristicRules.filter {
            val matchesSearch = searchQuery.isEmpty() ||
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
            val matchesScannerType = selectedScannerType == null || it.scannerType == selectedScannerType
            matchesSearch && matchesScannerType
        }
    }

    if (filteredRules.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (searchQuery.isEmpty() && selectedScannerType == null) "No Heuristic Rules" else "No Matching Rules",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (searchQuery.isEmpty() && selectedScannerType == null)
                        "Add behavioral rules to detect anomalies"
                    else
                        "Try a different search or filter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (searchQuery.isEmpty() && selectedScannerType == null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onAddRule) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Heuristic Rule")
                    }
                }
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredRules, key = { it.id }) { rule ->
                HeuristicRuleCard(
                    rule = rule,
                    onToggle = { onToggleRule(rule.id, it) },
                    onEdit = { onEditRule(rule) },
                    onDelete = { onDeleteRule(rule.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeuristicRuleCard(
    rule: HeuristicRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val threatColor = when {
        rule.threatScore >= 90 -> ThreatCritical
        rule.threatScore >= 75 -> ThreatHigh
        rule.threatScore >= 50 -> ThreatMedium
        rule.threatScore >= 25 -> ThreatLow
        else -> ThreatInfo
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Scanner type icon
                        Icon(
                            imageVector = when (rule.scannerType) {
                                ScannerType.WIFI -> Icons.Default.Wifi
                                ScannerType.BLUETOOTH -> Icons.Default.Bluetooth
                                ScannerType.CELLULAR -> Icons.Default.CellTower
                                ScannerType.SATELLITE -> Icons.Default.SatelliteAlt
                                ScannerType.GNSS -> Icons.Default.GpsFixed
                                ScannerType.RF -> Icons.Default.Radio
                                ScannerType.ULTRASONIC -> Icons.Default.GraphicEq
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "Heuristic",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = rule.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (!rule.enabled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = "DISABLED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "${rule.conditions.size} condition${if (rule.conditions.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = rule.conditionLogic.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = threatColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "Score: ${rule.threatScore}",
                                style = MaterialTheme.typography.labelSmall,
                                color = threatColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle
                )
            }

            if (rule.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = rule.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Expanded content showing conditions
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Conditions:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    rule.conditions.forEachIndexed { index, condition ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = condition.field.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = condition.operator.symbol,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (condition.operator == HeuristicOperator.BETWEEN)
                                        "${condition.value} - ${condition.secondValue}"
                                    else condition.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (condition.field.unit.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = condition.field.unit,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (index < rule.conditions.size - 1) {
                            Text(
                                text = if (rule.conditionLogic == ConditionLogic.AND) "AND" else "OR",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cooldown: ${rule.cooldownMs / 1000}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(onClick = onEdit) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit")
                            }
                            TextButton(
                                onClick = { showDeleteDialog = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete Heuristic Rule?") },
            text = { Text("Delete \"${rule.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CategoriesTab(
    settings: RuleSettingsRepository.RuleSettings,
    onToggleCategory: (BuiltInRuleCategory, Boolean) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Enable or disable entire categories of detection patterns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            CategoryToggleCard(
                category = BuiltInRuleCategory.FLOCK_SAFETY,
                enabled = settings.flockSafetyEnabled,
                onToggle = { onToggleCategory(BuiltInRuleCategory.FLOCK_SAFETY, it) },
                patternCount = DetectionPatterns.ssidPatterns.count {
                    it.deviceType == DeviceType.FLOCK_SAFETY_CAMERA
                } + DetectionPatterns.bleNamePatterns.count {
                    it.deviceType == DeviceType.FLOCK_SAFETY_CAMERA
                },
                icon = Icons.Default.CameraAlt
            )
        }

        item {
            CategoryToggleCard(
                category = BuiltInRuleCategory.POLICE_TECH,
                enabled = settings.policeTechEnabled,
                onToggle = { onToggleCategory(BuiltInRuleCategory.POLICE_TECH, it) },
                patternCount = DetectionPatterns.ssidPatterns.count {
                    it.deviceType in listOf(
                        DeviceType.MOTOROLA_POLICE_TECH,
                        DeviceType.AXON_POLICE_TECH,
                        DeviceType.L3HARRIS_SURVEILLANCE,
                        DeviceType.CELLEBRITE_FORENSICS,
                        DeviceType.BODY_CAMERA,
                        DeviceType.POLICE_RADIO,
                        DeviceType.STINGRAY_IMSI
                    )
                },
                icon = Icons.Default.LocalPolice
            )
        }

        item {
            CategoryToggleCard(
                category = BuiltInRuleCategory.ACOUSTIC_SENSORS,
                enabled = settings.acousticSensorsEnabled,
                onToggle = { onToggleCategory(BuiltInRuleCategory.ACOUSTIC_SENSORS, it) },
                patternCount = DetectionPatterns.bleNamePatterns.count {
                    it.deviceType == DeviceType.RAVEN_GUNSHOT_DETECTOR
                } + DetectionPatterns.ravenServiceUuids.size,
                icon = Icons.Default.Mic
            )
        }

        item {
            CategoryToggleCard(
                category = BuiltInRuleCategory.GENERIC_SURVEILLANCE,
                enabled = settings.genericSurveillanceEnabled,
                onToggle = { onToggleCategory(BuiltInRuleCategory.GENERIC_SURVEILLANCE, it) },
                patternCount = DetectionPatterns.ssidPatterns.count {
                    it.deviceType == DeviceType.UNKNOWN_SURVEILLANCE
                } + DetectionPatterns.macPrefixes.size,
                icon = Icons.Default.Visibility
            )
        }
    }
}

@Composable
private fun CategoryToggleCard(
    category: BuiltInRuleCategory,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    patternCount: Int,
    icon: ImageVector
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = category.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$patternCount patterns",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditRuleBottomSheet(
    existingRule: CustomRule?,
    onDismiss: () -> Unit,
    onSave: (CustomRule) -> Unit
) {
    var name by remember { mutableStateOf(existingRule?.name ?: "") }
    var pattern by remember { mutableStateOf(existingRule?.pattern ?: "") }
    var selectedScannerType by remember { mutableStateOf(existingRule?.scannerType ?: ScannerType.WIFI) }
    var type by remember { mutableStateOf(existingRule?.type ?: RuleType.SSID_REGEX) }
    var deviceType by remember { mutableStateOf(existingRule?.deviceType ?: DeviceType.UNKNOWN_SURVEILLANCE) }
    var threatScore by remember { mutableStateOf(existingRule?.threatScore?.toString() ?: "50") }
    var description by remember { mutableStateOf(existingRule?.description ?: "") }
    var patternError by remember { mutableStateOf<String?>(null) }
    var showDeviceTypePicker by remember { mutableStateOf(false) }
    var testInput by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    // Get rule types for selected scanner type
    val availableRuleTypes = remember(selectedScannerType) {
        RuleType.entries.filter { it.scannerType == selectedScannerType }
    }

    // Update type when scanner type changes
    LaunchedEffect(selectedScannerType) {
        if (type.scannerType != selectedScannerType) {
            type = availableRuleTypes.firstOrNull() ?: RuleType.SSID_REGEX
        }
    }

    // Test the pattern
    fun testPattern(): Boolean {
        return try {
            when (type) {
                RuleType.MAC_PREFIX -> testInput.uppercase().startsWith(pattern.uppercase())
                RuleType.CELLULAR_MCC_MNC -> testInput.matches(Regex(pattern.replace("*", ".*")))
                RuleType.CELLULAR_LAC_RANGE -> {
                    val parts = pattern.split("-")
                    if (parts.size == 2) {
                        val num = testInput.toIntOrNull() ?: return false
                        num in parts[0].toInt()..parts[1].toInt()
                    } else false
                }
                RuleType.RF_FREQUENCY_RANGE, RuleType.ULTRASONIC_FREQUENCY -> {
                    val parts = pattern.split("-")
                    if (parts.size == 2) {
                        val num = testInput.toIntOrNull() ?: return false
                        num in parts[0].toInt()..parts[1].toInt()
                    } else false
                }
                else -> Regex(pattern).containsMatchIn(testInput)
            }
        } catch (e: Exception) {
            false
        }
    }

    // Validate pattern
    fun validatePattern(): Boolean {
        return try {
            when (type) {
                RuleType.MAC_PREFIX -> pattern.matches(Regex("^([0-9A-Fa-f]{2}:){0,2}[0-9A-Fa-f]{2}$"))
                RuleType.CELLULAR_LAC_RANGE, RuleType.RF_FREQUENCY_RANGE, RuleType.ULTRASONIC_FREQUENCY -> {
                    pattern.matches(Regex("^\\d+-\\d+$"))
                }
                RuleType.BLE_SERVICE_UUID -> pattern.matches(Regex("^[0-9a-fA-F-]+$"))
                else -> { Regex(pattern); true }
            }
        } catch (e: Exception) {
            false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = if (existingRule != null) "Edit Custom Rule" else "Add Custom Rule",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Rule name
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Name") },
                    placeholder = { Text("e.g., My Local PD Cameras") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Scanner type selector
            item {
                Text("Scanner Type", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScannerType.entries.forEach { scannerType ->
                        FilterChip(
                            selected = selectedScannerType == scannerType,
                            onClick = { selectedScannerType = scannerType },
                            label = { Text(scannerType.displayName) },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (scannerType) {
                                        ScannerType.WIFI -> Icons.Default.Wifi
                                        ScannerType.BLUETOOTH -> Icons.Default.Bluetooth
                                        ScannerType.CELLULAR -> Icons.Default.CellTower
                                        ScannerType.SATELLITE -> Icons.Default.SatelliteAlt
                                        ScannerType.GNSS -> Icons.Default.GpsFixed
                                        ScannerType.RF -> Icons.Default.Radio
                                        ScannerType.ULTRASONIC -> Icons.Default.GraphicEq
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Pattern type (filtered by scanner type)
            item {
                Text("Pattern Type", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableRuleTypes.forEach { ruleType ->
                        FilterChip(
                            selected = type == ruleType,
                            onClick = {
                                type = ruleType
                                patternError = null
                            },
                            label = { Text(ruleType.displayName) }
                        )
                    }
                }
            }

            // Pattern input
            item {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        patternError = null
                        testResult = null
                    },
                    label = { Text("Pattern") },
                    placeholder = { Text(type.patternHint) },
                    isError = patternError != null,
                    supportingText = {
                        if (patternError != null) {
                            Text(patternError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Pattern hint: ${type.patternHint}")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Live pattern tester
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Test Your Pattern",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = testInput,
                                onValueChange = {
                                    testInput = it
                                    testResult = null
                                },
                                placeholder = { Text("Test input...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { testResult = testPattern() },
                                enabled = pattern.isNotEmpty() && testInput.isNotEmpty()
                            ) {
                                Text("Test")
                            }
                        }

                        testResult?.let { matches ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (matches) StatusActive.copy(alpha = 0.2f)
                                    else StatusError.copy(alpha = 0.2f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (matches) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        contentDescription = null,
                                        tint = if (matches) StatusActive else StatusError,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (matches) "Pattern matches!" else "No match",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (matches) StatusActive else StatusError
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Device type selector
            item {
                Text("Device Type", style = MaterialTheme.typography.labelMedium)
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDeviceTypePicker = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = deviceType.name.replace("_", " "),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = showDeviceTypePicker,
                    onDismissRequest = { showDeviceTypePicker = false }
                ) {
                    DeviceType.entries.forEach { dt ->
                        DropdownMenuItem(
                            text = { Text(dt.name.replace("_", " ")) },
                            onClick = {
                                deviceType = dt
                                showDeviceTypePicker = false
                            }
                        )
                    }
                }
            }

            // Threat score
            item {
                OutlinedTextField(
                    value = threatScore,
                    onValueChange = {
                        if (it.isEmpty() || it.toIntOrNull() != null) {
                            threatScore = it
                        }
                    },
                    label = { Text("Threat Score (0-100)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        val score = threatScore.toIntOrNull() ?: 50
                        Text(
                            text = when {
                                score >= 90 -> "CRITICAL - Immediate threat"
                                score >= 75 -> "HIGH - Significant concern"
                                score >= 50 -> "MEDIUM - Notable"
                                score >= 25 -> "LOW - Minor concern"
                                else -> "INFO - Informational only"
                            }
                        )
                    }
                )
            }

            // Description
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    placeholder = { Text("What does this detect?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            // Action buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (!validatePattern()) {
                                patternError = "Invalid pattern"
                                return@Button
                            }

                            val score = threatScore.toIntOrNull()?.coerceIn(0, 100) ?: 50

                            onSave(
                                CustomRule(
                                    id = existingRule?.id ?: java.util.UUID.randomUUID().toString(),
                                    name = name.ifBlank { "Custom Rule" },
                                    pattern = pattern,
                                    type = type,
                                    deviceType = deviceType,
                                    threatScore = score,
                                    description = description,
                                    enabled = existingRule?.enabled ?: true,
                                    createdAt = existingRule?.createdAt ?: System.currentTimeMillis()
                                )
                            )
                        },
                        enabled = name.isNotBlank() && pattern.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save")
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditHeuristicRuleBottomSheet(
    existingRule: HeuristicRule?,
    onDismiss: () -> Unit,
    onSave: (HeuristicRule) -> Unit
) {
    var name by remember { mutableStateOf(existingRule?.name ?: "") }
    var description by remember { mutableStateOf(existingRule?.description ?: "") }
    var selectedScannerType by remember { mutableStateOf(existingRule?.scannerType ?: ScannerType.CELLULAR) }
    var conditions by remember { mutableStateOf(existingRule?.conditions ?: emptyList()) }
    var conditionLogic by remember { mutableStateOf(existingRule?.conditionLogic ?: ConditionLogic.AND) }
    var deviceType by remember { mutableStateOf(existingRule?.deviceType ?: DeviceType.UNKNOWN_SURVEILLANCE) }
    var threatScore by remember { mutableStateOf(existingRule?.threatScore?.toString() ?: "50") }
    var cooldownSeconds by remember { mutableStateOf((existingRule?.cooldownMs ?: 60000L) / 1000) }
    var showDeviceTypePicker by remember { mutableStateOf(false) }
    var showAddConditionDialog by remember { mutableStateOf(false) }

    // Get available fields for selected scanner type
    val availableFields = remember(selectedScannerType) {
        HeuristicField.entries.filter { it.scannerType == selectedScannerType }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = if (existingRule != null) "Edit Heuristic Rule" else "Add Heuristic Rule",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Define behavioral conditions to detect anomalies",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Rule name
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Name") },
                    placeholder = { Text("e.g., IMSI Catcher Detection") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Scanner type selector
            item {
                Text("Scanner Type", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScannerType.entries.forEach { scannerType ->
                        FilterChip(
                            selected = selectedScannerType == scannerType,
                            onClick = {
                                selectedScannerType = scannerType
                                // Clear conditions when scanner type changes
                                conditions = emptyList()
                            },
                            label = { Text(scannerType.displayName) },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (scannerType) {
                                        ScannerType.WIFI -> Icons.Default.Wifi
                                        ScannerType.BLUETOOTH -> Icons.Default.Bluetooth
                                        ScannerType.CELLULAR -> Icons.Default.CellTower
                                        ScannerType.SATELLITE -> Icons.Default.SatelliteAlt
                                        ScannerType.GNSS -> Icons.Default.GpsFixed
                                        ScannerType.RF -> Icons.Default.Radio
                                        ScannerType.ULTRASONIC -> Icons.Default.GraphicEq
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Conditions section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Conditions", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showAddConditionDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Condition")
                    }
                }

                if (conditions.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "No conditions added",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Add at least one condition",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        conditions.forEachIndexed { index, condition ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = condition.field.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${condition.operator.symbol} ${condition.value}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            if (condition.secondValue != null) {
                                                Text(
                                                    text = " - ${condition.secondValue}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                            if (condition.field.unit.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = condition.field.unit,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            conditions = conditions.toMutableList().also { it.removeAt(index) }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            if (index < conditions.size - 1) {
                                Text(
                                    text = conditionLogic.displayName.substringBefore(" "),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Condition logic
            if (conditions.size > 1) {
                item {
                    Text("Condition Logic", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConditionLogic.entries.forEach { logic ->
                            FilterChip(
                                selected = conditionLogic == logic,
                                onClick = { conditionLogic = logic },
                                label = { Text(logic.displayName) }
                            )
                        }
                    }
                }
            }

            // Device type selector
            item {
                Text("Device Type", style = MaterialTheme.typography.labelMedium)
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDeviceTypePicker = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = deviceType.name.replace("_", " "),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = showDeviceTypePicker,
                    onDismissRequest = { showDeviceTypePicker = false }
                ) {
                    DeviceType.entries.forEach { dt ->
                        DropdownMenuItem(
                            text = { Text(dt.name.replace("_", " ")) },
                            onClick = {
                                deviceType = dt
                                showDeviceTypePicker = false
                            }
                        )
                    }
                }
            }

            // Threat score
            item {
                OutlinedTextField(
                    value = threatScore,
                    onValueChange = {
                        if (it.isEmpty() || it.toIntOrNull() != null) {
                            threatScore = it
                        }
                    },
                    label = { Text("Threat Score (0-100)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Cooldown
            item {
                OutlinedTextField(
                    value = cooldownSeconds.toString(),
                    onValueChange = {
                        cooldownSeconds = it.toLongOrNull() ?: 60L
                    },
                    label = { Text("Cooldown (seconds)") },
                    supportingText = { Text("Minimum time between alerts from this rule") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Description
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    placeholder = { Text("What does this rule detect?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            // Action buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val score = threatScore.toIntOrNull()?.coerceIn(0, 100) ?: 50

                            onSave(
                                HeuristicRule(
                                    id = existingRule?.id ?: java.util.UUID.randomUUID().toString(),
                                    name = name.ifBlank { "Heuristic Rule" },
                                    description = description,
                                    scannerType = selectedScannerType,
                                    conditions = conditions,
                                    conditionLogic = conditionLogic,
                                    deviceType = deviceType,
                                    threatScore = score,
                                    cooldownMs = cooldownSeconds * 1000L,
                                    enabled = existingRule?.enabled ?: true,
                                    createdAt = existingRule?.createdAt ?: System.currentTimeMillis()
                                )
                            )
                        },
                        enabled = name.isNotBlank() && conditions.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save")
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Add condition dialog
    if (showAddConditionDialog) {
        AddConditionDialog(
            availableFields = availableFields,
            onDismiss = { showAddConditionDialog = false },
            onAdd = { condition ->
                conditions = conditions + condition
                showAddConditionDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddConditionDialog(
    availableFields: List<HeuristicField>,
    onDismiss: () -> Unit,
    onAdd: (HeuristicCondition) -> Unit
) {
    var selectedField by remember { mutableStateOf(availableFields.firstOrNull()) }
    var selectedOperator by remember { mutableStateOf(HeuristicOperator.GREATER_THAN) }
    var value by remember { mutableStateOf("") }
    var secondValue by remember { mutableStateOf("") }
    var showFieldPicker by remember { mutableStateOf(false) }

    // Update default value when field changes
    LaunchedEffect(selectedField) {
        selectedField?.let {
            value = it.defaultThreshold
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Condition") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Field selector
                Text("Field", style = MaterialTheme.typography.labelMedium)
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFieldPicker = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = selectedField?.displayName ?: "Select field",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            selectedField?.let {
                                Text(
                                    text = it.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = showFieldPicker,
                    onDismissRequest = { showFieldPicker = false }
                ) {
                    availableFields.forEach { field ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(field.displayName)
                                    Text(
                                        field.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedField = field
                                showFieldPicker = false
                            }
                        )
                    }
                }

                // Operator selector
                Text("Operator", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HeuristicOperator.entries.forEach { op ->
                        FilterChip(
                            selected = selectedOperator == op,
                            onClick = { selectedOperator = op },
                            label = { Text(op.symbol) }
                        )
                    }
                }

                // Value input
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (selectedOperator == HeuristicOperator.BETWEEN) "Min Value" else "Value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        selectedField?.unit?.takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )

                // Second value for BETWEEN operator
                if (selectedOperator == HeuristicOperator.BETWEEN) {
                    OutlinedTextField(
                        value = secondValue,
                        onValueChange = { secondValue = it },
                        label = { Text("Max Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            selectedField?.unit?.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedField?.let { field ->
                        onAdd(
                            HeuristicCondition(
                                field = field,
                                operator = selectedOperator,
                                value = value,
                                secondValue = if (selectedOperator == HeuristicOperator.BETWEEN) secondValue else null
                            )
                        )
                    }
                },
                enabled = selectedField != null && value.isNotBlank() &&
                    (selectedOperator != HeuristicOperator.BETWEEN || secondValue.isNotBlank())
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
