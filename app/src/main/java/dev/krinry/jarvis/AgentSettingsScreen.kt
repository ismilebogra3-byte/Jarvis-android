package dev.krinry.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.ai.ModelInfo
import dev.krinry.jarvis.security.SecureKeyStore
import dev.krinry.jarvis.service.AutoAgentService
import dev.krinry.jarvis.service.FloatingBubbleService
import dev.krinry.jarvis.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }

    val isAccessibilityEnabled = remember(refreshKey) { checkAccessibilityEnabled(context) }
    val hasOverlayPermission = remember(refreshKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    }
    var hasAudioPermission by remember(refreshKey) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasAudioPermission = isGranted; if (isGranted) refreshKey++ }

    var agentEnabled by remember { mutableStateOf(SecureKeyStore.isAgentEnabled(context)) }
    val allReady = isAccessibilityEnabled && hasOverlayPermission && hasAudioPermission

    // Provider state
    val providers = remember { GroqApiClient.getProviders() }
    var selectedProviderId by remember { mutableStateOf(SecureKeyStore.getApiProvider(context)) }
    val selectedProvider = remember(selectedProviderId) { GroqApiClient.getProvider(selectedProviderId) ?: providers.first() }
    var apiKey by remember(selectedProviderId) {
        mutableStateOf(selectedProvider.getApiKey(context) ?: "")
    }
    var primaryModel by remember { mutableStateOf(SecureKeyStore.getPrimaryModel(context)) }
    var fallbackModel by remember { mutableStateOf(SecureKeyStore.getFallbackModel(context)) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelPickerTarget by remember { mutableStateOf("primary") }
    var providerExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
    kotlinx.coroutines.delay(500)
    refreshKey++

    // Voice-only background mode:
    // Bubble UI stays OFF, but Hello Nura service starts automatically
    // whenever the Jarvis Agent is enabled.
    if (SecureKeyStore.isAgentEnabled(context)) {
        startBubbleService(context)
    }
}

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = {}, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ===== HERO =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(JarvisPrimary, Color(0xFF8E7CF3), JarvisSecondary.copy(alpha = 0.6f))))
                    .padding(24.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Text("J", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("JARVIS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 3.sp)
                            Text("AI Voice Agent", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, letterSpacing = 1.sp)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (agentEnabled) Icons.Default.PowerSettingsNew else Icons.Default.PowerOff, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (agentEnabled) "Agent Active" else "Agent Offline", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(if (agentEnabled) "Hello Nura voice standby active" else "Turn on to start voice assistant", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                        }
                        Switch(
                            checked = agentEnabled,
                            onCheckedChange = { enabled ->
                                agentEnabled = enabled
                                SecureKeyStore.setAgentEnabled(context, enabled)
                                if (enabled) startBubbleService(context) else stopBubbleService(context)
                                refreshKey++
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Color.White.copy(alpha = 0.35f), checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.1f), uncheckedThumbColor = Color.White.copy(alpha = 0.5f))
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== PERMISSIONS =====
            SectionHeader("Setup", Icons.Default.Shield)
            PermissionRow(Icons.Default.Accessibility, "Accessibility Service", "Read & interact with apps", isAccessibilityEnabled) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
            PermissionRow(Icons.Default.Layers, "Display Over Apps", "Floating AI bubble", hasOverlayPermission) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            }
            PermissionRow(Icons.Default.Mic, "Microphone", "Voice commands", hasAudioPermission) { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }

            if (agentEnabled) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (allReady) JarvisSuccess.copy(alpha = 0.1f) else JarvisError.copy(alpha = 0.1f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (allReady) "✅ All set! Say Hello Nura to start." else "⚠️ Grant all permissions above.",
                        fontSize = 13.sp, color = if (allReady) JarvisSuccess else JarvisError, textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ===== AI PROVIDER (DROPDOWN FORM) =====
            SectionHeader("AI Configuration", Icons.Default.Hub)

            // Provider dropdown
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp), color = DarkCard
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Provider", fontSize = 12.sp, color = DarkOnSurfaceVariant, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
                        OutlinedTextField(
                            value = selectedProvider.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisPrimary,
                                unfocusedBorderColor = DarkSurfaceVariant
                            )
                        )
                        ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.displayName) },
                                    onClick = {
                                        selectedProviderId = provider.id
                                        SecureKeyStore.setApiProvider(context, provider.id)
                                        apiKey = provider.getApiKey(context) ?: ""
                                        // Reset models when provider changes
                                        primaryModel = ""
                                        fallbackModel = ""
                                        SecureKeyStore.setPrimaryModel(context, "")
                                        SecureKeyStore.setFallbackModel(context, "")
                                        providerExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // API Key field (single, for selected provider)
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp), color = DarkCard
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("${selectedProvider.displayName} API Key", fontSize = 12.sp, color = DarkOnSurfaceVariant, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { newKey ->
                            apiKey = newKey
                            selectedProvider.saveApiKey(context, newKey)
                        },
                        placeholder = { Text("Enter API key...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisPrimary,
                            unfocusedBorderColor = DarkSurfaceVariant
                        )
                    )
                    // Groq STT note
                    if (selectedProviderId != "groq") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "💡 Voice uses Groq Whisper. Say Hello Nura to start a command.",
                            fontSize = 11.sp, color = JarvisWarning.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ===== MODEL SELECTION =====
            SectionHeader("Models", Icons.Default.Psychology)

            SettingsRow(Icons.Default.AutoAwesome, "Primary Model",
                primaryModel.ifEmpty { selectedProvider.defaultModel }, JarvisSecondary
            ) { modelPickerTarget = "primary"; showModelPicker = true }

            SettingsRow(Icons.Default.SwapHoriz, "Fallback Model",
                fallbackModel.ifEmpty { selectedProvider.defaultFallbackModel }, JarvisAccent
            ) { modelPickerTarget = "fallback"; showModelPicker = true }

            Spacer(Modifier.height(40.dp))

            Text("Jarvis v1.0 • by Krinry", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 12.sp, color = DarkOnSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(24.dp))
        }
    }

    // ===== MODEL PICKER DIALOG =====
    if (showModelPicker) {
        ModelPickerDialog(
            context = context, target = modelPickerTarget,
            onSelect = { modelId ->
                if (modelPickerTarget == "primary") {
                    primaryModel = modelId; SecureKeyStore.setPrimaryModel(context, modelId)
                } else {
                    fallbackModel = modelId; SecureKeyStore.setFallbackModel(context, modelId)
                }
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}

// =============================================================================
// === Reusable Components ===
// =============================================================================

@Composable
private fun SectionHeader(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp, top = 4.dp)) {
        Icon(icon, null, tint = JarvisAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = JarvisAccent)
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, iconColor: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = DarkCard) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = DarkOnSurface)
                Text(subtitle, fontSize = 12.sp, color = DarkOnSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null, tint = DarkOnSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, subtitle: String, isGranted: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = DarkCard) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (isGranted) JarvisSuccess.copy(alpha = 0.15f) else JarvisPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = if (isGranted) JarvisSuccess else JarvisPrimary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = DarkOnSurface)
                Text(subtitle, fontSize = 12.sp, color = DarkOnSurfaceVariant)
            }
            if (isGranted) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(JarvisSuccess.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = JarvisSuccess, modifier = Modifier.size(16.dp))
                }
            } else {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(JarvisPrimary).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("Grant", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerDialog(context: Context, target: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showFreeOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch { isLoading = true; models = GroqApiClient.fetchAvailableModels(context); isLoading = false }
    }

    val filteredModels = models.filter { model ->
        (searchQuery.isEmpty() || model.id.contains(searchQuery, ignoreCase = true) || model.name.contains(searchQuery, ignoreCase = true)) &&
        (!showFreeOnly || model.isFree)
    }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (target == "primary") "Primary Model" else "Fallback Model") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Search models...") },
                    singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showFreeOnly, onCheckedChange = { showFreeOnly = it })
                    Text("Free only", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${filteredModels.size} models", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                if (isLoading) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (filteredModels.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("No models found\nCheck API key", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(filteredModels) { model ->
                            Surface(onClick = { onSelect(model.id) }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(model.name.take(40), fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                                        Text(model.id, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    if (model.isFree) {
                                        Surface(shape = RoundedCornerShape(6.dp), color = JarvisSuccess.copy(alpha = 0.15f)) {
                                            Text("FREE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = JarvisSuccess, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = {}
    )
}

// =============================================================================
// === Helpers ===
// =============================================================================

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

private fun startBubbleService(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
        Toast.makeText(context, "Grant overlay permission first", Toast.LENGTH_LONG).show()
        return
    }
    try {
        val intent = Intent(context, FloatingBubbleService::class.java).apply { action = FloatingBubbleService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not start Jarvis: ${e.message?.take(50)}", Toast.LENGTH_LONG).show()
    }
}

private fun stopBubbleService(context: Context) {
    try {
        context.startService(Intent(context, FloatingBubbleService::class.java).apply { action = FloatingBubbleService.ACTION_STOP })
    } catch (_: Exception) {}
}
