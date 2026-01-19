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
import com.flockyou.data.BuiltInRuleCategory
import com.flockyou.data.CustomRule
import com.flockyou.data.RuleSettingsRepository
import com.flockyou.data.RuleType
import com.flockyou.data.model.DetectionPatterns
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.PatternType
import com.flockyou.ui.theme.*

/**
 * All Detections Screen - Shows all detection patterns (built-in + custom)
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
    var editingRule by remember { mutableStateOf<CustomRule?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val tabs = listOf("All Patterns", "Custom Rules", "Categories")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("All Detections")
                        Text(
                            text = "Built-in and custom patterns",
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
                    IconButton(onClick = { showAddRuleSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Custom Rule")
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
                enabledCustomCount = settings.customRules.count { it.enabled }
            )

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search patterns...") },
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

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title)
                                if (index == 1 && settings.customRules.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Badge { Text("${settings.customRules.size}") }
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
                    onCategorySelected = { selectedCategory = if (selectedCategory == it) null else it }
                )
                1 -> CustomRulesTabEnhanced(
                    customRules = settings.customRules,
                    searchQuery = searchQuery,
                    onToggleRule = viewModel::toggleCustomRule,
                    onEditRule = { editingRule = it },
                    onDeleteRule = viewModel::deleteCustomRule,
                    onAddRule = { showAddRuleSheet = true }
                )
                2 -> CategoriesTab(
                    settings = settings,
                    onToggleCategory = viewModel::toggleCategory
                )
            }
        }
    }

    // Add/Edit rule bottom sheet
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
}

@Composable
private fun DetectionSummaryCard(
    builtInCount: Int,
    customCount: Int,
    enabledCustomCount: Int
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
                label = "Custom",
                icon = Icons.Default.Edit,
                subtitle = if (customCount > 0) "$enabledCustomCount enabled" else null
            )
            SummaryStatBox(
                count = builtInCount + customCount,
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
    onCategorySelected: (String) -> Unit
) {
    // Combine all patterns into unified list
    data class UnifiedPattern(
        val id: String,
        val name: String,
        val pattern: String,
        val type: String,
        val category: String,
        val deviceType: DeviceType,
        val threatScore: Int,
        val description: String,
        val manufacturer: String?,
        val isCustom: Boolean,
        val isEnabled: Boolean = true
    )

    val allPatterns = remember(customRules, searchQuery, selectedCategory) {
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
                type = when (rule.type) {
                    RuleType.SSID_REGEX -> "SSID"
                    RuleType.BLE_NAME_REGEX -> "BLE"
                    RuleType.MAC_PREFIX -> "MAC"
                },
                category = "Custom",
                deviceType = rule.deviceType,
                threatScore = rule.threatScore,
                description = rule.description,
                manufacturer = null,
                isCustom = true,
                isEnabled = rule.enabled
            ))
        }

        // Filter by search query
        patterns.filter { p ->
            val matchesSearch = searchQuery.isEmpty() ||
                p.name.contains(searchQuery, ignoreCase = true) ||
                p.pattern.contains(searchQuery, ignoreCase = true) ||
                p.description.contains(searchQuery, ignoreCase = true) ||
                p.deviceType.name.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == null || p.category == selectedCategory
            matchesSearch && matchesCategory
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
    onToggleRule: (String, Boolean) -> Unit,
    onEditRule: (CustomRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onAddRule: () -> Unit
) {
    val filteredRules = remember(customRules, searchQuery) {
        if (searchQuery.isEmpty()) customRules
        else customRules.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.pattern.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true)
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
                    text = if (searchQuery.isEmpty()) "No Custom Rules" else "No Matching Rules",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (searchQuery.isEmpty())
                        "Add your own regex patterns to detect specific devices"
                    else
                        "Try a different search term",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (searchQuery.isEmpty()) {
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
    var type by remember { mutableStateOf(existingRule?.type ?: RuleType.SSID_REGEX) }
    var deviceType by remember { mutableStateOf(existingRule?.deviceType ?: DeviceType.UNKNOWN_SURVEILLANCE) }
    var threatScore by remember { mutableStateOf(existingRule?.threatScore?.toString() ?: "50") }
    var description by remember { mutableStateOf(existingRule?.description ?: "") }
    var patternError by remember { mutableStateOf<String?>(null) }
    var showDeviceTypePicker by remember { mutableStateOf(false) }
    var testInput by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    // Test the pattern
    fun testPattern(): Boolean {
        return try {
            if (type == RuleType.MAC_PREFIX) {
                testInput.uppercase().startsWith(pattern.uppercase())
            } else {
                Regex(pattern).containsMatchIn(testInput)
            }
        } catch (e: Exception) {
            false
        }
    }

    // Validate regex pattern
    fun validatePattern(): Boolean {
        return try {
            if (type == RuleType.MAC_PREFIX) {
                pattern.matches(Regex("^([0-9A-Fa-f]{2}:){0,2}[0-9A-Fa-f]{2}$"))
            } else {
                Regex(pattern)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
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

            // Pattern type
            item {
                Text("Pattern Type", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RuleType.entries.forEach { ruleType ->
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
                    placeholder = {
                        Text(
                            when (type) {
                                RuleType.SSID_REGEX -> "(?i)^mycity[_-]?.*"
                                RuleType.BLE_NAME_REGEX -> "(?i)^patrol[_-]?.*"
                                RuleType.MAC_PREFIX -> "AA:BB:CC"
                            }
                        )
                    },
                    isError = patternError != null,
                    supportingText = {
                        if (patternError != null) {
                            Text(patternError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(
                                when (type) {
                                    RuleType.SSID_REGEX, RuleType.BLE_NAME_REGEX -> "Java regex pattern. (?i) = case insensitive"
                                    RuleType.MAC_PREFIX -> "MAC address prefix (e.g., AA:BB:CC)"
                                }
                            )
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
                                placeholder = {
                                    Text(
                                        when (type) {
                                            RuleType.SSID_REGEX -> "Test SSID name"
                                            RuleType.BLE_NAME_REGEX -> "Test BLE name"
                                            RuleType.MAC_PREFIX -> "Test MAC address"
                                        }
                                    )
                                },
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
