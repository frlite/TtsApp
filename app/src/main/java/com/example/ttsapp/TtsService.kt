package com.example.ttsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import com.example.ttsapp.data.AppDatabase
import com.example.ttsapp.data.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class TtsService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isPlaying = false
    private var pendingText: String = ""
    private var pendingSpeechRate: Float = 1.0f
    private var pendingPitch: Float = 1.0f
    private var pendingVoice: Voice? = null

    // Service 独立于 Activity 存活所需的标记
    private var isStartedAsForeground = false

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
        fun onSpeakFailed(reason: String)
    }

    var callback: TtsCallback? = null

    private var database: AppDatabase? = null

    // 与 Service 生命周期绑定，onDestroy 时统一取消
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_ID = "tts_playback_channel"
        const val NOTIFICATION_ID = 1
        const val UTTERANCE_ID = "tts_utterance"
        private const val ACTION_STOP = "com.example.ttsapp.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, TtsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        database = AppDatabase.getDatabase(this)
        tts = TextToSpeech(this, this)
    }

    /**
     * 保存播放历史。相同文本会覆盖旧记录并刷新时间戳，
     * 同时裁剪超出上限的旧记录，避免数据库无限增长。
     */
    fun saveHistory(text: String, speechRate: Float, pitch: Float) {
        val db = database ?: return
        serviceScope.launch {
            val dao = db.historyDao()
            dao.upsert(
                HistoryEntity(
                    text = text,
                    speechRate = speechRate,
                    pitch = pitch,
                    timestamp = System.currentTimeMillis()
                )
            )
            dao.trimToLimit()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏"停止"按钮：用户已在后台，需要自行收尾并结束服务
        if (intent?.action == ACTION_STOP) {
            stopPlaying()
            stopSelf()
            return START_NOT_STICKY
        }
        // 通过 startForegroundService 启动时，必须在 5 秒内调用 startForeground
        // 此处先以前台服务身份自保，实际播放由 UI 触发
        if (!isStartedAsForeground) {
            ensureForeground()
        }
        return START_NOT_STICKY
    }

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
        if (!isTtsReady) {
            callback?.onSpeakFailed("语音引擎尚未就绪")
            return
        }
        if (text.isBlank()) {
            callback?.onSpeakFailed("播放内容为空")
            return
        }

        pendingText = text
        pendingSpeechRate = speechRate
        pendingPitch = pitch
        pendingVoice = voice

        // 确保服务进入前台状态，使播放不依赖 Activity 生命周期
        ensureForeground()

        isPlaying = true
        val started = speakInternal()
        if (started) {
            callback?.onPlayingStateChanged(true)
        } else {
            isPlaying = false
            releaseForeground()
            callback?.onSpeakFailed("当前语音引擎无法播放该内容")
        }
    }

    /**
     * 实际发起播报。返回 false 表示引擎拒绝了本次请求。
     */
    private fun speakInternal(): Boolean {
        val engine = tts ?: return false
        if (!isTtsReady || pendingText.isBlank()) return false

        engine.setSpeechRate(pendingSpeechRate)
        engine.setPitch(pendingPitch)
        pendingVoice?.let { engine.setVoice(it) }

        // 使用 QUEUE_FLUSH 确保每次从头开始播放
        val result = engine.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        return result == TextToSpeech.SUCCESS
    }

    fun stopSpeaking() {
        stopPlaying()
    }

    private fun stopPlaying() {
        val wasPlaying = isPlaying
        isPlaying = false
        tts?.stop()
        releaseForeground()
        // 只有通过 startForegroundService 启动时才 stopSelf，
        // 纯 bind 场景下 stopSelf 不会销毁服务，保留绑定关系
        if (isStartedAsForeground) {
            stopSelf()
        }
        if (wasPlaying) {
            callback?.onPlayingStateChanged(false)
        }
    }

    fun isPlaying(): Boolean = isPlaying

    fun getVoices(): List<Voice> {
        return if (isTtsReady) tts?.voices?.toList() ?: emptyList() else emptyList()
    }

    /**
     * 进入前台服务状态。使用 startForeground 让服务在 Activity 销毁后仍可存活。
     */
    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isStartedAsForeground = true
    }

    /**
     * 退出前台状态但保留服务实例，等待下一次播放重新进入前台。
     */
    private fun releaseForeground() {
        if (!isStartedAsForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isStartedAsForeground = false
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
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // 循环播放时用户会退到后台，通知上必须能直接停止，
        // 否则只能回到应用内才能结束播放。
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_stop),
                stopIntent
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isPlaying = false
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        super.onDestroy()
    }
}
