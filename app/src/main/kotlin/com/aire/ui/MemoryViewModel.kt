package com.aire.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aire.claude.*
import com.aire.data.*
import com.aire.domain.MemoryRecord
import com.aire.domain.SourceType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppScreen { HOME, CHAT, LENS, SETTINGS, VAULT, VOICE_MODE, HISTORY }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val image: Bitmap? = null,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val response: AssistantResponse? = null,
)

/** Transient UI state for Assistant interactions. */
data class MemoryUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val isThinking: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialTranscription: String = "",
    val isAiAvailable: Boolean = true,
    val capturedImage: Bitmap? = null,
    val chatHistory: List<ChatMessage> = emptyList(),
    val currentLocation: DeviceLocation? = null,
    val availableUpdate: GitHubAsset? = null,
    val error: String? = null,
    val aiModel: String = "claude-haiku-4-5",
    val appearance: String = "System",
    val locationFeaturesEnabled: Boolean = false,
    val storeLocationWithMemories: Boolean = false,
    val shareLocationWithAi: Boolean = false,
    val portalExpansion: Float = 0f,
    val isPortalVisible: Boolean = false,
)

/**
 * Unified ViewModel for the AI Assistant. Handles chat history, multimodal input,
 * and context-aware interactions via [AssistantService].
 */
class MemoryViewModel(
    private val dao: MemoryDao,
    private val settings: SettingsRepository,
    private val locationProvider: LocationProvider,
    private val integrationManager: IntegrationManager,
    private val updateManager: UpdateManager
) : ViewModel() {

    private var voiceSynthesizer: VoiceSynthesizer? = null

    private val _records = dao.observeAll()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val records: StateFlow<List<MemoryRecord>> = _records

    private val _history = dao.observeHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    val history: StateFlow<List<HistoryRecordEntity>> = _history

    private val _searchResults = MutableStateFlow<List<MemoryRecord>>(emptyList())
    val searchResults: StateFlow<List<MemoryRecord>> = _searchResults.asStateFlow()

    private var currentSearchJob: kotlinx.coroutines.Job? = null

    fun searchMemories(query: String) {
        currentSearchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        
        currentSearchJob = viewModelScope.launch {
            // Sanitize query for FTS4: wrap in quotes and escape internal quotes
            val sanitized = query.replace("\"", "\"\"")
            val ftsQuery = "\"$sanitized*\""
            
            try {
                val results = dao.search(ftsQuery).map { it.toDomain() }
                _searchResults.value = results
            } catch (e: Exception) {
                // Handle cases where FTS syntax might still be invalid
                _searchResults.value = emptyList()
            }
        }
    }

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settings.anthropicApiKey,
                settings.aiModel,
                settings.appearance,
                settings.locationFeaturesEnabled,
                settings.storeLocationWithMemories,
                settings.shareLocationWithAi
            ) { args: Array<*> ->
                val key = args[0] as? String
                val model = args[1] as String
                val appearance = args[2] as String
                val locEnabled = args[3] as Boolean
                val locStore = args[4] as Boolean
                val locAi = args[5] as Boolean
                
                _uiState.update { it.copy(
                    isAiAvailable = !key.isNullOrBlank(),
                    aiModel = model,
                    appearance = appearance,
                    locationFeaturesEnabled = locEnabled,
                    storeLocationWithMemories = locStore,
                    shareLocationWithAi = locAi
                ) }
            }.collect()
        }
        checkForUpdates()
    }

    private fun checkForUpdates() {
        viewModelScope.launch {
            val asset = updateManager.checkForUpdate()
            _uiState.update { it.copy(availableUpdate = asset) }
        }
    }

    fun installUpdate() {
        val asset = uiState.value.availableUpdate ?: return
        updateManager.downloadAndInstall(asset)
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun setPortalExpansion(progress: Float) {
        _uiState.update { it.copy(portalExpansion = progress) }
        if (progress >= 1f) {
            viewModelScope.launch {
                // Smooth transition: give the animation a moment to finish before switching screens
                kotlinx.coroutines.delay(100)
                _uiState.update { it.copy(currentScreen = AppScreen.CHAT, isPortalVisible = false, portalExpansion = 0f) }
            }
        }
    }

    fun closePortal() {
        _uiState.update { it.copy(isPortalVisible = false, portalExpansion = 0f, currentScreen = AppScreen.HOME) }
    }

    /** Called when the camera shutter is pressed. */
    fun onImageCaptured(bitmap: Bitmap) {
        _uiState.update { it.copy(capturedImage = bitmap, currentScreen = AppScreen.HOME) }
    }

    fun clearCapturedImage() {
        _uiState.update { it.copy(capturedImage = null) }
    }

    /** Refresh current location context. Call this when starting a conversation or capture. */
    fun refreshLocation() {
        if (!uiState.value.locationFeaturesEnabled) return
        viewModelScope.launch {
            val location = locationProvider.getCurrentLocation()
            _uiState.update { it.copy(currentLocation = location) }
        }
    }

    fun onActionClicked(action: AssistantAction, response: AssistantResponse) {
        when (action.type) {
            "SAVE_MEMORY" -> {
                saveMemoryFromResponse(response)
            }
            else -> {
                // Delegate to integration manager for system-level actions (MAPS_SEARCH, etc.)
                integrationManager.execute(action)
            }
        }
    }

    private fun saveMemoryFromResponse(response: AssistantResponse) {
        val fields = response.extractedFields ?: return
        val loc = if (uiState.value.storeLocationWithMemories) uiState.value.currentLocation else null
        val record = MemoryRecord(
            id = UUID.randomUUID().toString(),
            category = fields.category,
            title = fields.title,
            summary = fields.summary,
            occurredOn = fields.occurredOn,
            attributes = fields.attributes,
            tags = fields.tags,
            capturedAt = System.currentTimeMillis(),
            sourceText = response.explanation,
            sourceType = SourceType.TEXT,
            locationName = loc?.name,
            latitude = loc?.latitude,
            longitude = loc?.longitude
        )
        viewModelScope.launch {
            dao.insert(MemoryRecordEntity.fromDomain(record))
        }
    }

    fun sendMessage(text: String) {
        android.util.Log.d("MemoryViewModel", "sendMessage: $text")
        if (text.isBlank() && (uiState.value.capturedImage == null)) return
        
        val isFromHome = uiState.value.currentScreen == AppScreen.HOME
        val userImage = uiState.value.capturedImage
        val userMessage = ChatMessage(text = text, image = userImage, isUser = true)
        
        // Reset chat history and trigger portal if sending from Home
        if (isFromHome) {
            android.util.Log.d("MemoryViewModel", "New chat initiated from Home. Switching to portal.")
            
            // Save to persistent History
            viewModelScope.launch {
                dao.insertHistory(HistoryRecordEntity(
                    title = text.take(30) + if (text.length > 30) "..." else "",
                    summary = "Conversational interaction",
                    timestamp = System.currentTimeMillis()
                ))
            }

            _uiState.update { it.copy(
                isPortalVisible = true, 
                portalExpansion = 0f,
                chatHistory = listOf(userMessage),
                isThinking = true,
                error = null,
                capturedImage = null
            ) }
        } else {
            _uiState.update { it.copy(
                chatHistory = it.chatHistory + userMessage,
                isThinking = true,
                error = null,
                capturedImage = null
            ) }
        }

        viewModelScope.launch {
            try {
                // Ensure we get the latest key
                val apiKey = settings.anthropicApiKey.filterNotNull().first()
                if (!apiKey.isBlank()) {
                    val config = ClaudeConfig(
                        useProxy = false,
                        proxyBaseUrl = "",
                        directApiKey = apiKey,
                        proxyAuthToken = "",
                        model = uiState.value.aiModel
                    )
                    val client = AnthropicClientProvider.get(config)
                    val assistant = AssistantService(client, config.model)
                    
                    val context = buildAssistantContext()
                    val response = assistant.interact(text, userImage, context)
                    
                    val assistantMessage = ChatMessage(text = response.explanation, isUser = false, response = response)
                    _uiState.update { it.copy(
                        chatHistory = it.chatHistory + assistantMessage,
                        isThinking = false
                    ) }

                    if (uiState.value.currentScreen == AppScreen.VOICE_MODE) {
                        _uiState.update { it.copy(isSpeaking = true) }
                        voiceSynthesizer?.speak(response.explanation)
                    }
                } else {
                    android.util.Log.w("MemoryViewModel", "No API key found.")
                    val assistantMessage = ChatMessage(text = "Please add your Anthropic API key in Settings.", isUser = false)
                    _uiState.update { it.copy(chatHistory = it.chatHistory + assistantMessage, isThinking = false) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("MemoryViewModel", "AI Error", t)
                val errorMessage = ChatMessage(text = "Error: ${t.message}", isUser = false)
                _uiState.update { it.copy(chatHistory = it.chatHistory + errorMessage, isThinking = false, error = t.friendlyMessage()) }
            }
        }
    }

    private fun buildAssistantContext(): String {
        val memories = records.value.asSequence().take(10).joinToString("\n") { 
            it.toRecallSummary(shareLocation = uiState.value.shareLocationWithAi) 
        }
        val locationText = if (uiState.value.shareLocationWithAi) {
            uiState.value.currentLocation?.let { 
                "User's Current Location: ${it.name ?: "Unknown area"} (${it.latitude}, ${it.longitude})" 
            } ?: "User's Location: Unknown (GPS unavailable)"
        } else {
            "User's Location: Not provided (Privacy mode)"
        }

        return """
            Recent Memories:
            $memories
            
            $locationText
            Current Time: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}
        """.trimIndent()
    }

    private var voiceRecognizer: VoiceRecognizer? = null

    fun startListening(context: android.content.Context) {
        // Initialize TTS if needed for Voice Mode
        if (voiceSynthesizer == null) {
            voiceSynthesizer = VoiceSynthesizer(context) {
                // When Aire finishes speaking, optionally restart listening in Voice Mode
                // Ensure UI state update happens on the main thread
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    _uiState.update { it.copy(isSpeaking = false) }
                    if (uiState.value.currentScreen == AppScreen.VOICE_MODE) {
                        voiceRecognizer?.stop() // Ensure clean state before restart
                        voiceRecognizer?.start()
                        _uiState.update { it.copy(isListening = true) }
                    }
                }
            }
        }

        _uiState.update { it.copy(isListening = true, partialTranscription = "", error = null) }
        voiceRecognizer = VoiceRecognizer(
            context = context,
            onPartialResults = { partial ->
                _uiState.update { it.copy(partialTranscription = partial) }
            },
            onFinalResults = { final ->
                _uiState.update { it.copy(isListening = false, partialTranscription = "") }
                sendMessage(final)
            },
            onError = { err ->
                _uiState.update { it.copy(isListening = false, error = err) }
            }
        ) {
            _uiState.update { it.copy(isListening = false) }
        }
        voiceRecognizer?.start()
    }

    fun stopListening() {
        voiceRecognizer?.stop()
        voiceRecognizer = null
        voiceSynthesizer?.stop()
        _uiState.update { it.copy(isListening = false, isSpeaking = false) }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecognizer?.stop()
        voiceSynthesizer?.shutdown()
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
    
    fun clearChat() {
        _uiState.update { it.copy(chatHistory = emptyList()) }
    }
    
    // Settings Actions
    fun updateApiKey(key: String) = viewModelScope.launch { settings.setApiKey(key) }
    fun updateGoogleApiKey(key: String) = viewModelScope.launch { settings.setGoogleApiKey(key) }
    fun updateModel(model: String) = viewModelScope.launch { settings.setModel(model) }
    fun updateAppearance(appearance: String) = viewModelScope.launch { settings.setAppearance(appearance) }
    fun updateLocationEnabled(enabled: Boolean) = viewModelScope.launch { settings.setLocationEnabled(enabled) }
    fun updateStoreLocation(enabled: Boolean) = viewModelScope.launch { settings.setStoreLocation(enabled) }
    fun updateShareLocationAi(enabled: Boolean) = viewModelScope.launch { settings.setShareLocationAi(enabled) }

    private fun Throwable.friendlyMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Something went wrong (${this::class.simpleName})."

    class Factory(
        private val dao: MemoryDao,
        private val settings: SettingsRepository,
        private val locationProvider: LocationProvider,
        private val integrationManager: IntegrationManager,
        private val updateManager: UpdateManager
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return MemoryViewModel(
                dao = dao,
                settings = settings,
                locationProvider = locationProvider,
                integrationManager = integrationManager,
                updateManager = updateManager
            ) as T
        }
    }
}
