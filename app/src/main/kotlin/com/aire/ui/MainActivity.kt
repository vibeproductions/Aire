package com.aire.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aire.data.IntegrationManager
import com.aire.data.LocationProvider
import com.aire.data.MemoryDatabase
import com.aire.data.SettingsRepository
import com.aire.data.UpdateManager
import com.aire.ui.theme.AireTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = MemoryDatabase.get(applicationContext).dao()
        val settings = SettingsRepository(applicationContext)
        val locationProvider = LocationProvider(applicationContext)
        val integrationManager = IntegrationManager(applicationContext)
        val updateManager = UpdateManager(applicationContext)
        
        setContent {
            val vm: MemoryViewModel = viewModel(factory = MemoryViewModel.Factory(dao, settings, locationProvider, integrationManager, updateManager))
            val uiState by vm.uiState.collectAsState()

            AireTheme(appearance = uiState.appearance) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Base Layer: Home or other non-chat screens
                    Crossfade(targetState = uiState.currentScreen, label = "ScreenTransition") { screen ->
                        when (screen) {
                            AppScreen.HOME -> HomeScreen(vm)
                            AppScreen.CHAT -> ChatScreen(vm)
                            AppScreen.LENS -> LensScreen(
                                onCaptured = { vm.onImageCaptured(it) },
                                onClose = { vm.navigateTo(AppScreen.HOME) }
                            )
                            AppScreen.SETTINGS -> SettingsScreen(vm)
                            AppScreen.VAULT -> VaultScreen(vm)
                            AppScreen.VOICE_MODE -> VoiceModeScreen(vm)
                            AppScreen.HISTORY -> HistoryScreen(vm)
                        }
                    }

                    // Overlay Layer: Interactive Portal
                    if (uiState.isPortalVisible) {
                        ChatPortal(vm) {
                            ChatScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
