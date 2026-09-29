package com.example.ttsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import java.util.Locale

class TtsService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isPlaying = false
    private var pendingText: String = ""
    private var pendingSpeechRate: Float = 1.0f
    private var pendingPitch: Float = 1.0f
    private var pendingVoice: Voice? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    // 回调接口，用于通知 UI 状态变化
    interface TtsCallback {
        fun onInitSuccess()
        fun onInitFailed()
        fun onPlayingStateChanged(isPlaying: Boolean)
        fun onVoicesAvailable(voices: List<Voice>)
    }

    var callback: TtsCallback? = null

    companion object {
        const val CHANNEL_ID = "tts_playback_channel"
        const val NOTIFICATION_ID = 1
        const val UTTERANCE_ID = "tts_utterance"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    // 播放完成后立即重新开始，实现无间隔循环
                    if (isPlaying && utteranceId == UTTERANCE_ID) {
                        speakInternal()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stopPlaying()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    // 用户主动停止时不触发循环
                }
            })

            // 设置默认语言为中文（如果可用），否则使用系统默认
            val chineseLocale = Locale.SIMPLIFIED_CHINESE
            if (tts?.isLanguageAvailable(chineseLocale) != TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = chineseLocale
            }

            callback?.onInitSuccess()
            callback?.onVoicesAvailable(tts?.voices?.toList() ?: emptyList())
        } else {
            isTtsReady = false
            callback?.onInitFailed()
        }
    }

    fun speak(text: String, speechRate: Float = 1.0f, pitch: Float = 1.0f, voice: Voice? = null) {
        pendingText = text
        pendingSpeechRate = speechRate
        pendingPitch = pitch
        pendingVoice = voice

        if (isTtsReady) {
            isPlaying = true
            startForeground(NOTIFICATION_ID, buildNotification())
            speakInternal()
            callback?.onPlayingStateChanged(true)
        }
    }

    private fun speakInternal() {
        if (!isTtsReady || pendingText.isBlank()) return

        tts?.apply {
            setSpeechRate(pendingSpeechRate)
            setPitch(pendingPitch)
            pendingVoice?.let { setVoice(it) }

            // 使用 QUEUE_FLUSH 确保每次从头开始播放
            speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    fun stopSpeaking() {
        stopPlaying()
    }

    private fun stopPlaying() {
        isPlaying = false
        tts?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        callback?.onPlayingStateChanged(false)
    }

    fun isPlaying(): Boolean = isPlaying

    fun getVoices(): List<Voice> {
        return if (isTtsReady) tts?.voices?.toList() ?: emptyList() else emptyList()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TTS 语音播放控制"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        super.onDestroy()
    }
}
