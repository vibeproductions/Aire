package com.aire.claude

import android.graphics.Bitmap
import android.util.Base64
import com.aire.domain.ExtractedFields
import com.aire.domain.MemoryRecord
import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream

/**
 * Suggested action for the app to perform.
 */
@Serializable
data class AssistantAction(
    val type: String, // e.g., "SAVE_MEMORY", "ADD_CALENDAR", "ADD_CONTACT"
    val label: String, // e.g., "Save to Memory"
    val data: Map<String, String> = emptyMap()
)

/**
 * Structured response from the Assistant.
 */
@Serializable
data class AssistantResponse(
    val explanation: String,
    val suggestedActions: List<AssistantAction> = emptyList(),
    val extractedFields: ExtractedFields? = null
)

/**
 * Core AI service that handles context-aware assistant interactions.
 */
class AssistantService(
    private val client: AnthropicClient,
    private val model: String
) {

    suspend fun interact(
        text: String,
        image: Bitmap?,
        context: String, // e.g. previous records or location
        history: List<MessageParam> = emptyList()
    ): AssistantResponse = withContext(Dispatchers.IO) {
        android.util.Log.d("AssistantService", "Starting interaction with model: $model")
        val params = buildParams(text, image, context, history)
        val response = client.messages().create(params)
        android.util.Log.d("AssistantService", "Received response from Claude")

        val rawText = buildString {
            response.content().forEach { block -> block.text().ifPresent { append(it.text()) } }
        }

        ClaudeJson.decodeFromString(
            AssistantResponse.serializer(),
            extractJsonObject(rawText)
        )
    }

    private fun buildParams(
        input: String,
        image: Bitmap?,
        context: String,
        history: List<MessageParam>
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(2048L)
            .system(SYSTEM_PROMPT.replace("{{CONTEXT}}", context))

        // Add history (to be implemented in ViewModel)
        history.forEach { builder.addMessage(it) }

        if (image != null) {
            val stream = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val blocks = mutableListOf<ContentBlockParam>()
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(input).build()))
            blocks.add(ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                    .source(
                        ImageBlockParam.Source.ofBase64(
                            Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .data(base64Image)
                                .build()
                        )
                    )
                    .build()
            ))
            builder.addUserMessageOfBlockParams(blocks)
        } else {
            builder.addUserMessage(input)
        }

        return builder.build()
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You are Aire, a context-aware AI Assistant for Android.
            
            Your goal is to explain inputs, answer questions, and suggest helpful actions.
            You have access to the user's "Memory Vault" (context provided below).
            
            Context from Memory Vault and Device:
            {{CONTEXT}}

            Respond with ONLY a JSON object with these fields:
              "explanation": A natural-language response to the user.
              "suggestedActions": A list of actions the user might want to take.
                Action types & data requirements:
                - "SAVE_MEMORY": Always use when info seems important. Needs "extractedFields".
                - "ADD_CALENDAR": Needs "title", "description", "location", "beginTime" (epoch millis), "endTime" (epoch millis).
                - "ADD_CONTACT": Needs "name", "phone", "email", "notes".
                - "MAPS_SEARCH": Needs "query" (e.g., "coffee shops near me").
                - "OPEN_WEB": Needs "url".
              "extractedFields": Structured data for memories (category, title, summary, etc.).

            Guidelines:
              - Be friendly and professional.
              - If a photo is provided, explain it first.
              - Use context to answer questions about the past.
              - Always suggest "SAVE_MEMORY" if the input seems important for later.
              - Suggest "ADD_CALENDAR" for events with dates.
              - Suggest "ADD_CONTACT" for business cards or people info.
              - IMPORTANT: You never have access to the user's detailed health records for synthesis; 
                if the user asks about health/medical data, explain that it is kept securely on-device only.
        """.trimIndent()
    }
}
