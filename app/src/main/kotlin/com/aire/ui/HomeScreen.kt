package com.aire.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import com.aire.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aire.domain.MemoryRecord
import com.aire.data.HistoryRecordEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MemoryViewModel) {
    val ui by viewModel.uiState.collectAsState()
    val history by viewModel.history.collectAsState()
    val records by viewModel.records.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Determine Greeting based on time
    val greeting = remember {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when (hour) {
            in 0..11 -> "Good morning."
            in 12..16 -> "Good afternoon."
            else -> "Good evening."
        }
    }

    // State for the random font
    var randomFont by remember { mutableStateOf(FontFamily.Default) }

    // Refresh font every time the app is "opened" (brought to foreground)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                randomFont = listOf(
                    FontFamily.Serif,
                    FontFamily.SansSerif,
                    FontFamily.Monospace,
                    FontFamily.Cursive,
                    FontFamily.Default
                ).random()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    var inputText by remember { mutableStateOf("") }

    val recordAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.startListening(context)
    }

    Scaffold(
        topBar = {
            if (!ui.isPortalVisible) {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clip(CircleShape).clickable { viewModel.navigateTo(AppScreen.VOICE_MODE) }.padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_aire_logo),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("Aire", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable { viewModel.navigateTo(AppScreen.VAULT) }
                            ) {
                                Text(
                                    text = "${records.size} memories",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.navigateTo(AppScreen.SETTINGS) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!ui.isPortalVisible) {
                HistorySwipeBar(history.firstOrNull()) {
                    viewModel.navigateTo(AppScreen.HISTORY)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // --- Welcome Section ---
            Text(
                text = greeting,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = randomFont,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Update Chip
            ui.availableUpdate?.let { asset ->
                Spacer(Modifier.height(16.dp))
                SuggestionChip(
                    onClick = { viewModel.installUpdate() },
                    label = { Text("Update available: ${asset.name}") },
                    icon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp)) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            Spacer(Modifier.height(32.dp))

            // --- Centered "Ask Aire" Bar ---
            if (!ui.isPortalVisible) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask Aire anything...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    maxLines = 3,
                    trailingIcon = {
                        Row(modifier = Modifier.padding(end = 8.dp)) {
                            IconButton(onClick = {
                                recordAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                            }) {
                                Icon(Icons.Default.Mic, "Voice", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { viewModel.navigateTo(AppScreen.LENS) }) {
                                Icon(Icons.Default.CameraAlt, "Lens", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(
                                onClick = {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                },
                                enabled = inputText.isNotBlank()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray)
                            }
                        }
                    }
                )
            } else {
                Spacer(Modifier.height(64.dp))
            }
        }
    }
}

@Composable
fun HistorySwipeBar(latestHistory: HistoryRecordEntity?, onSwipeUp: () -> Unit) {
    var offsetY by remember { mutableStateOf(0f) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .height(80.dp)
            .offset(y = offsetY.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        offsetY = (offsetY + dragAmount).coerceAtMost(0f)
                        if (offsetY < -100f) {
                            onSwipeUp()
                            offsetY = 0f
                        }
                    },
                    onDragEnd = {
                        offsetY = 0f
                    }
                )
            },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = latestHistory?.title ?: "No recent history",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Swipe up for history",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
