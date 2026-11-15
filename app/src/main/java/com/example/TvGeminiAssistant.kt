package com.example

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class TvGeminiAssistant(
  private val scope: CoroutineScope,
  private val onStateJson: (String) -> Unit
) {
  companion object {
    private const val TAG = "TvGeminiAssistant"
  }

  private val model by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      systemInstruction = content {
        text(
          "You are a concise, helpful smart TV assistant inside TV Home Launcher for Android TV. " +
            "Give clear, short TV-friendly answers (under 140 words unless asked for more). " +
            "Help with movie/YouTube video ideas, TV apps, games, inputs, and ADB CleanYourTV tips."
        )
      }
    )
  }

  private var activeJob: Job? = null

  fun ask(prompt: String, installedSummary: String) {
    val clean = prompt.trim()
    if (clean.isEmpty()) return

    activeJob?.cancel()
    emit("loading", "Thinking with Gemini...", clean)

    val fullPrompt = if (installedSummary.isNotBlank()) {
      "Installed TV apps summary: $installedSummary\n\nUser request: $clean"
    } else {
      clean
    }

    activeJob = scope.launch {
      val acc = StringBuilder()
      try {
        withTimeout(60_000L) {
          model.generateContentStream(fullPrompt).collect { chunk ->
            val piece = chunk.text.orEmpty()
            if (piece.isNotEmpty()) {
              acc.append(piece)
              emit("streaming", acc.toString(), clean)
            }
          }
        }
        val final = acc.toString().trim()
        if (final.isNotEmpty()) {
          emit("success", final, clean)
        } else {
          emit("error", "Gemini returned an empty response.", clean)
        }
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Gemini request timed out", e)
        emit("error", "Gemini request timed out after 60 seconds. Please try again.", clean)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Firebase AI Logic generation failed", e)
        emit("error", e.localizedMessage ?: "Gemini request failed.", clean)
      }
    }
  }

  fun cancel() {
    activeJob?.cancel()
    activeJob = null
  }

  private fun emit(status: String, text: String, prompt: String) {
    val json = JSONObject().apply {
      put("status", status)
      put("text", text)
      put("prompt", prompt)
    }.toString()
    onStateJson(json)
  }
}
