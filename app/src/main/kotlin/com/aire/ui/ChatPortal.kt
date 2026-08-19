package com.aire.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@Composable
fun ChatPortal(
    viewModel: MemoryViewModel,
    content: @Composable () -> Unit,
) {
    val ui by viewModel.uiState.collectAsState()
    val currentPortalExpansion by rememberUpdatedState(ui.portalExpansion)
    
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    
    // 1. Initial Morph State: Bar -> Circle
    var isMorphed by remember { mutableStateOf(value = false) }
    LaunchedEffect(Unit) {
        isMorphed = true
    }

    // Portal base dimensions (matching the chat bar)
    val barWidth = screenWidth - 48.dp
    val barHeight = 64.dp
    val barCornerRadius = 32.dp

    // Animate dimensions from Bar to Circle
    val currentBaseHeight by animateDpAsState(
        targetValue = if (isMorphed) barWidth else barHeight,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.7f),
        label = "morphHeight"
    )
    val currentCornerRadius by animateDpAsState(
        targetValue = if (isMorphed) barWidth / 2 else barCornerRadius,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.7f),
        label = "morphCorners"
    )

    // 2. Expansion Animation: Circle -> Full Screen
    val expansion by animateFloatAsState(
        targetValue = ui.portalExpansion,
        animationSpec = spring(stiffness = 600f, dampingRatio = 0.8f),
        label = "expansion"
    )

    val baseSizePx = with(density) { barWidth.toPx() }
    val screenDiagonal = sqrt((screenWidth.value * screenWidth.value) + (screenHeight.value * screenHeight.value))
    val targetScale = (screenDiagonal / barWidth.value) * 1.5f
    val currentScale = 1f + (expansion * (targetScale - 1f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f * (1f - expansion))),
        contentAlignment = Alignment.Center
    ) {
        // --- The Portal Bubble (Morphing & Expanding) ---
        Surface(
            modifier = Modifier
                .width(barWidth)
                .height(currentBaseHeight)
                .graphicsLayer {
                    scaleX = currentScale
                    scaleY = currentScale
                    alpha = 1f
                }
                .clip(RoundedCornerShape(currentCornerRadius))
                .clickable { viewModel.setPortalExpansion(1f) },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Fixed-size content container (The real Chat Screen)
                Box(modifier = Modifier
                    .requiredSize(screenWidth, screenHeight)
                    .graphicsLayer {
                        scaleX = 1f / currentScale
                        scaleY = 1f / currentScale
                        // Alpha-blend content in as we expand
                        alpha = (expansion * 1.5f).coerceIn(0f, 1f)
                    }
                ) {
                    content()
                }
                
                // --- Preview / Guidance Overlay ---
                val lastAssistantMessage = ui.chatHistory.lastOrNull { !it.isUser }
                // Overlay visibility: Visible during morph, fades during expansion
                val overlayAlpha = (1f - (expansion * 2.5f)).coerceIn(0f, 1f)
                
                if (overlayAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(overlayAlpha)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            if (ui.isThinking && (lastAssistantMessage == null)) {
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(16.dp))
                                Text("Aire is thinking...", style = MaterialTheme.typography.bodyMedium)
                            } else if (lastAssistantMessage != null) {
                                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = lastAssistantMessage.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 6
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "PULL TO OPEN",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(16.dp))
                                Text("Pull outward to open", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        // --- GESTURE LAYER ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var cumulativeZoom = 1f
                    var gestureMaxExpansion = 0f
                    
                    detectTransformGestures { centroid, _, zoom, _ ->
                        // 1. PINCH TO EXIT
                        if (zoom != 1f) {
                            cumulativeZoom *= zoom
                            if (cumulativeZoom < 0.7f) {
                                viewModel.closePortal()
                                cumulativeZoom = 1f
                            }
                        }

                        // 2. RADIAL PULL TO EXPAND
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val dx = centroid.x - centerX
                        val dy = centroid.y - centerY
                        val dist = sqrt(dx * dx + dy * dy)
                        
                        val hitRadius = baseSizePx * 0.7f
                        if (dist >= hitRadius || currentPortalExpansion > 0f) {
                            val startRange = baseSizePx / 2f
                            val endRange = size.width * 0.45f
                            val newExpansion = ((dist - startRange) / (endRange - startRange)).coerceIn(0f, 1f)
                            
                            if (newExpansion > gestureMaxExpansion) {
                                gestureMaxExpansion = newExpansion
                                viewModel.setPortalExpansion(newExpansion)
                            }
                        }
                    }
                }
        )
    }
}
