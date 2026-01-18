package com.flockyou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flockyou.data.BuiltInRuleCategory
import com.flockyou.data.CustomRule
import com.flockyou.data.RuleSettingsRepository
import com.flockyou.data.RuleType
import com.flockyou.data.model.DeviceType
import com.flockyou.data.model.DetectionPatterns
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RuleSettingsViewModel @Inject constructor(
    private val repository: RuleSettingsRepository
) : ViewModel() {
    
    val settings = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        RuleSettingsRepository.RuleSettings()
    )
    
    fun toggleCategory(category: BuiltInRuleCategory, enabled: Boolean) {
        viewModelScope.launch {
            repository.setCategoryEnabled(category, enabled)
        }
    }
    
    fun addCustomRule(rule: CustomRule) {
        viewModelScope.launch {
            repository.addCustomRule(rule)
        }
    }
    
    fun updateCustomRule(rule: CustomRule) {
        viewModelScope.launch {
            repository.updateCustomRule(rule)
        }
    }
    
    fun deleteCustomRule(ruleId: String) {
        viewModelScope.launch {
            repository.deleteCustomRule(ruleId)
        }
    }
    
    fun toggleCustomRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleCustomRule(ruleId, enabled)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSettingsScreen(
    viewModel: RuleSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomRule?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detection Rules") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedTab == 1) {
                        IconButton(onClick = { showAddRuleDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Rule")
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
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Built-in Rules") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Custom Rules")
                            if (settings.customRules.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Badge { Text("${settings.customRules.size}") }
                            }
                        }
                    }
                )
            }
            
            when (selectedTab) {
                0 -> BuiltInRulesTab(
                    settings = settings,
                    onToggleCategory = viewModel::toggleCategory
                )
                1 -> CustomRulesTab(
                    customRules = settings.customRules,
                    onToggleRule = viewModel::toggleCustomRule,
                    onEditRule = { editingRule = it },
                    onDeleteRule = viewModel::deleteCustomRule,
                    onAddRule = { showAddRuleDialog = true }
                )
            }
        }
    }
    
    // Add/Edit rule dialog
    if (showAddRuleDialog || editingRule != null) {
        AddEditRuleDialog(
            existingRule = editingRule,
            onDismiss = {
                showAddRuleDialog = false
                editingRule = null
            },
            onSave = { rule ->
                if (editingRule != null) {
                    viewModel.updateCustomRule(rule)
                } else {
                    viewModel.addCustomRule(rule)
                }
                showAddRuleDialog = false
                editingRule = null
            }
        )
    }
}

@Composable
private fun BuiltInRulesTab(
    settings: RuleSettingsRepository.RuleSettings,
    onToggleCategory: (BuiltInRuleCategory, Boolean) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Toggle entire categories of detection patterns. Disabled categories won't trigger alerts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        
        item {
            CategoryCard(
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
            CategoryCard(
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
            CategoryCard(
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
            CategoryCard(
                category = BuiltInRuleCategory.GENERIC_SURVEILLANCE,
                enabled = settings.genericSurveillanceEnabled,
                onToggle = { onToggleCategory(BuiltInRuleCategory.GENERIC_SURVEILLANCE, it) },
                patternCount = DetectionPatterns.ssidPatterns.count { 
                    it.deviceType == DeviceType.UNKNOWN_SURVEILLANCE 
                } + DetectionPatterns.macPrefixes.size,
                icon = Icons.Default.Visibility
            )
        }
        
        // Pattern breakdown
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "PATTERN COUNTS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PatternCountRow("WiFi SSID Patterns", DetectionPatterns.ssidPatterns.size)
                    PatternCountRow("BLE Name Patterns", DetectionPatterns.bleNamePatterns.size)
                    PatternCountRow("MAC Prefixes", DetectionPatterns.macPrefixes.size)
                    PatternCountRow("Raven Service UUIDs", DetectionPatterns.ravenServiceUuids.size)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    PatternCountRow(
                        "Total Patterns",
                        DetectionPatterns.ssidPatterns.size +
                            DetectionPatterns.bleNamePatterns.size +
                            DetectionPatterns.macPrefixes.size +
                            DetectionPatterns.ravenServiceUuids.size,
                        bold = true
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: BuiltInRuleCategory,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    patternCount: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector
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
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
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

@Composable
private fun PatternCountRow(label: String, count: Int, bold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CustomRulesTab(
    customRules: List<CustomRule>,
    onToggleRule: (String, Boolean) -> Unit,
    onEditRule: (CustomRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onAddRule: () -> Unit
) {
    if (customRules.isEmpty()) {
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
                    text = "No Custom Rules",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Add your own regex patterns to detect specific devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onAddRule) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Custom Rule")
                }
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(customRules, key = { it.id }) { rule ->
                CustomRuleCard(
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
private fun CustomRuleCard(
    rule: CustomRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = rule.type.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = rule.pattern,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }
            
            if (rule.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = rule.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Threat Score: ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${rule.threatScore}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            rule.threatScore >= 90 -> Color(0xFFD32F2F)
                            rule.threatScore >= 70 -> Color(0xFFF57C00)
                            rule.threatScore >= 50 -> Color(0xFFFBC02D)
                            else -> Color(0xFF388E3C)
                        }
                    )
                }
                
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditRuleDialog(
    existingRule: CustomRule?,
    onDismiss: () -> Unit,
    onSave: (CustomRule) -> Unit
) {
    var name by remember { mutableStateOf(existingRule?.name ?: "") }
    var pattern by remember { mutableStateOf(existingRule?.pattern ?: "") }
    var type by remember { mutableStateOf(existingRule?.type ?: RuleType.SSID_REGEX) }
    var threatScore by remember { mutableStateOf(existingRule?.threatScore?.toString() ?: "50") }
    var description by remember { mutableStateOf(existingRule?.description ?: "") }
    var patternError by remember { mutableStateOf<String?>(null) }
    
    // Validate regex pattern
    fun validatePattern(): Boolean {
        return try {
            if (type == RuleType.MAC_PREFIX) {
                // MAC prefix validation
                pattern.matches(Regex("^([0-9A-Fa-f]{2}:){1,2}[0-9A-Fa-f]{2}$"))
            } else {
                // Regex validation
                Regex(pattern)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingRule != null) "Edit Rule" else "Add Custom Rule") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Name") },
                    placeholder = { Text("e.g., My Local PD Cameras") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Type selector
                Text("Pattern Type", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RuleType.entries.forEach { ruleType ->
                        FilterChip(
                            selected = type == ruleType,
                            onClick = { type = ruleType },
                            label = { Text(ruleType.displayName) }
                        )
                    }
                }
                
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { 
                        pattern = it
                        patternError = null
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
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    placeholder = { Text("What does this detect?") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!validatePattern()) {
                        patternError = "Invalid pattern"
                        return@TextButton
                    }
                    
                    val score = threatScore.toIntOrNull()?.coerceIn(0, 100) ?: 50
                    
                    onSave(
                        CustomRule(
                            id = existingRule?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.ifBlank { "Custom Rule" },
                            pattern = pattern,
                            type = type,
                            threatScore = score,
                            description = description,
                            enabled = existingRule?.enabled ?: true,
                            createdAt = existingRule?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                },
                enabled = name.isNotBlank() && pattern.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
