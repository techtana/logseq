package com.logseq.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Continuous dictation for voice notes. Android's recognizer ends after each
 * utterance, so while a session is active it is started again after every
 * result or silence timeout. The screen stays on during a session; once
 * Logseq goes to the background the recognizer loses the microphone and the
 * session ends.
 *
 * Events: `state` {listening}, `partial` {text}, `result` {text},
 * `level` {level: 0..1}, `error` {code}. Codes: permission, network,
 * language, unavailable, other.
 */
@CapacitorPlugin(
  name = "VoiceNote",
  permissions = [
    Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = VoiceNotePlugin.MICROPHONE)
  ]
)
class VoiceNotePlugin : Plugin() {
  companion object {
    const val MICROPHONE = "microphone"
    private const val LEVEL_INTERVAL_MS = 80L
    private const val RESTART_DELAY_MS = 250L
    private const val STOP_TIMEOUT_MS = 3000L
    private const val MAX_RESTART_FAILURES = 3
  }

  // Everything below is touched on the main thread only.
  private val main = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var language = ""
  /** The user wants to keep dictating; cleared by stop, cancel and errors. */
  private var active = false
  /** Between startListening and its final onResults/onError. */
  private var utteranceOpen = false
  private var restartFailures = 0
  private var stopCall: PluginCall? = null
  private var lastLevelAt = 0L

  @PluginMethod
  fun start(call: PluginCall) {
    if (call.getString("language") == null) {
      call.reject("language is required")
      return
    }
    if (getPermissionState(MICROPHONE) == PermissionState.GRANTED) {
      main.post { startSession(call) }
    } else {
      requestPermissionForAlias(MICROPHONE, call, "microphonePermissionCallback")
    }
  }

  @PermissionCallback
  private fun microphonePermissionCallback(call: PluginCall) {
    if (getPermissionState(MICROPHONE) == PermissionState.GRANTED) {
      main.post { startSession(call) }
    } else {
      call.reject("Microphone permission denied", "permission")
    }
  }

  /** Ends the session after the current utterance's final result is sent. */
  @PluginMethod
  fun stop(call: PluginCall) {
    main.post {
      if (recognizer == null) {
        call.resolve()
        return@post
      }
      active = false
      stopCall = call
      if (utteranceOpen) {
        recognizer?.stopListening()
        main.postDelayed({ if (stopCall === call) endSession() }, STOP_TIMEOUT_MS)
      } else {
        endSession()
      }
    }
  }

  /** Ends the session right away, dropping the current utterance. */
  @PluginMethod
  fun cancel(call: PluginCall) {
    main.post {
      recognizer?.cancel()
      endSession()
      call.resolve()
    }
  }

  override fun handleOnStop() {
    super.handleOnStop()
    // The recognizer loses the microphone once Logseq is in the background.
    main.post {
      if (active && utteranceOpen) {
        active = false
        recognizer?.stopListening()
      } else if (recognizer != null && stopCall == null) {
        // Between utterances; a pending stop finishes on its own.
        endSession()
      }
    }
  }

  override fun handleOnDestroy() {
    main.post {
      recognizer?.cancel()
      endSession()
    }
    super.handleOnDestroy()
  }

  private fun startSession(call: PluginCall) {
    if (recognizer != null) {
      call.reject("A voice note is already recording", "busy")
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      call.reject("Speech recognition isn't available", "unavailable")
      return
    }
    language = call.getString("language")!!
    recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
      setRecognitionListener(listener)
    }
    active = true
    restartFailures = 0
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    listen()
    notifyListeners("state", JSObject().put("listening", true))
    call.resolve()
  }

  private fun listen() {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
      if (language.isNotEmpty()) {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
      }
    }
    utteranceOpen = true
    recognizer!!.startListening(intent)
  }

  private fun listenAgain(delayMs: Long) {
    main.postDelayed({
      if (active && recognizer != null && !utteranceOpen) listen()
    }, delayMs)
  }

  private fun endSession() {
    val wasRunning = recognizer != null
    active = false
    utteranceOpen = false
    recognizer?.destroy()
    recognizer = null
    if (wasRunning) {
      activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    stopCall?.resolve()
    stopCall = null
    if (wasRunning) {
      notifyListeners("state", JSObject().put("listening", false))
    }
  }

  private fun firstResult(bundle: Bundle?): String? =
    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      ?.firstOrNull()
      ?.takeIf { it.isNotBlank() }

  private fun errorCode(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "network"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language"
    else -> "other"
  }

  private val listener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onRmsChanged(rmsdB: Float) {
      val now = System.currentTimeMillis()
      if (now - lastLevelAt < LEVEL_INTERVAL_MS) return
      lastLevelAt = now
      // Typical speech is roughly -2..10 dB.
      val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
      notifyListeners("level", JSObject().put("level", level.toDouble()))
    }

    override fun onPartialResults(partialResults: Bundle?) {
      firstResult(partialResults)?.let {
        restartFailures = 0
        notifyListeners("partial", JSObject().put("text", it))
      }
    }

    override fun onResults(results: Bundle?) {
      utteranceOpen = false
      firstResult(results)?.let {
        restartFailures = 0
        notifyListeners("result", JSObject().put("text", it))
      }
      if (active) listenAgain(0) else endSession()
    }

    override fun onError(error: Int) {
      utteranceOpen = false
      if (!active) {
        endSession()
        return
      }
      when (error) {
        // Silence: keep listening.
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenAgain(0)
        // The recognizer wasn't ready for the restart yet.
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT ->
          if (++restartFailures <= MAX_RESTART_FAILURES) {
            recognizer?.cancel()
            listenAgain(RESTART_DELAY_MS)
          } else {
            notifyListeners("error", JSObject().put("code", "other"))
            endSession()
          }
        else -> {
          notifyListeners("error", JSObject().put("code", errorCode(error)))
          endSession()
        }
      }
    }
  }
}
