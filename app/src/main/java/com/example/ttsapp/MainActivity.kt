package com.example.ttsapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ttsapp.adapter.HistoryAdapter
import com.example.ttsapp.data.AppDatabase
import com.example.ttsapp.data.HistoryEntity
import com.example.ttsapp.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), TtsService.TtsCallback {

    companion object {
        private const val STATUS_RESET_DELAY_MS = 2500L
    }

    private lateinit var binding: ActivityMainBinding
    private var ttsService: TtsService? = null
    private var isBound = false
    private var voices: List<Voice> = emptyList()
    private var selectedVoice: Voice? = null

    private lateinit var database: AppDatabase
    private lateinit var historyAdapter: HistoryAdapter

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsService.LocalBinder
            ttsService = binder.getService()
            ttsService?.callback = this@MainActivity
            isBound = true

            if (ttsService?.isPlaying() == true) {
                updatePlayState(true)
            }
            voices = ttsService?.getVoices() ?: emptyList()
            setupVoiceSpinner()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            isBound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        requestNotificationPermission()
        setupSliders()
        setupButtons()
        setupHistory()
        bindTtsService()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun bindTtsService() {
        val intent = Intent(this, TtsService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 播放前先把 Service 拉起到前台状态，使其不依赖本 Activity 的生命周期。
     * 旋转屏幕或从最近任务移除应用时，播放仍能继续。
     */
    private fun ensureServiceStarted() {
        runCatching { TtsService.start(this) }
    }

    private fun setupSliders() {
        binding.tvSpeedLabel.text = getString(R.string.label_speed, binding.sliderSpeed.value)
        binding.tvPitchLabel.text = getString(R.string.label_pitch, binding.sliderPitch.value)

        binding.sliderSpeed.addOnChangeListener { _, value, _ ->
            binding.tvSpeedLabel.text = getString(R.string.label_speed, value)
        }
        binding.sliderPitch.addOnChangeListener { _, value, _ ->
            binding.tvPitchLabel.text = getString(R.string.label_pitch, value)
        }
    }

    private fun setupVoiceSpinner() {
        val voiceNames = mutableListOf(getString(R.string.label_voice))
        voices.forEach { voice ->
            val locale = voice.locale
            val displayName = "${locale.displayName} (${voice.name})"
            voiceNames.add(displayName)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVoice.adapter = adapter

        // 重建 adapter 会把选中项重置为 0，这里恢复用户此前的选择
        val restoredPosition = voices.indexOfFirst { it.name == selectedVoice?.name }
        if (restoredPosition >= 0) {
            binding.spinnerVoice.setSelection(restoredPosition + 1, false)
        }

        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedVoice = if (position == 0) null else voices.getOrNull(position - 1)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedVoice = null
            }
        }
    }

    private fun setupHistory() {
        historyAdapter = HistoryAdapter(
            onItemClick = { item ->
                // 点击历史记录：只回填内容与参数，不重复写入历史（避免产生副本）
                binding.etTextInput.setText(item.text)
                binding.etTextInput.setSelection(item.text.length)
                binding.sliderSpeed.value = item.speechRate
                binding.sliderPitch.value = item.pitch
                replayText(item.text, item.speechRate, item.pitch)
            },
            onDeleteClick = { item ->
                lifecycleScope.launch {
                    database.historyDao().deleteById(item.id)
                }
            }
        )

        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }

        // 监听历史记录数据变化（仅加载最近 N 条）
        lifecycleScope.launch {
            database.historyDao().getRecentHistory().collectLatest { historyList ->
                historyAdapter.submitList(historyList)
                binding.tvHistoryEmpty.visibility = if (historyList.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        // 清空历史按钮
        binding.btnClearHistory.setOnClickListener {
            lifecycleScope.launch {
                database.historyDao().clearAll()
            }
        }
    }

    private fun setupButtons() {
        binding.btnPlayPause.setOnClickListener {
            val service = ttsService
            if (service == null) {
                showTransientStatus(getString(R.string.status_service_unavailable))
                return@setOnClickListener
            }
            if (service.isPlaying()) {
                service.stopSpeaking()
            } else {
                val text = binding.etTextInput.text.toString().trim()
                if (text.isEmpty()) {
                    binding.etTextInput.error = getString(R.string.hint_input_text)
                    return@setOnClickListener
                }
                playText(text, binding.sliderSpeed.value, binding.sliderPitch.value)
            }
        }
    }

    /** 播放用户新输入的内容，同时写入历史记录。 */
    private fun playText(text: String, speed: Float, pitch: Float) {
        val service = ttsService
        if (service == null) {
            showTransientStatus(getString(R.string.status_service_unavailable))
            return
        }
        // 先将 Service 拉起到前台，确保播放不随 Activity 销毁而中断
        ensureServiceStarted()
        service.saveHistory(text, speed, pitch)
        service.speak(text, speed, pitch, selectedVoice)
    }

    /** 重播已有历史记录，不重复写入历史。 */
    private fun replayText(text: String, speed: Float, pitch: Float) {
        val service = ttsService
        if (service == null) {
            showTransientStatus(getString(R.string.status_service_unavailable))
            return
        }
        ensureServiceStarted()
        service.speak(text, speed, pitch, selectedVoice)
    }

    private fun showTransientStatus(message: String) {
        binding.tvStatus.text = message
        binding.tvStatus.postDelayed({
            if (!ttsService?.isPlaying()!!) {
                binding.tvStatus.text = getString(R.string.status_idle)
            }
        }, STATUS_RESET_DELAY_MS)
    }

    private fun updatePlayState(isPlaying: Boolean) {
        if (isPlaying) {
            binding.btnPlayPause.text = getString(R.string.btn_stop)
            binding.tvStatus.text = getString(R.string.status_playing)
        } else {
            binding.btnPlayPause.text = getString(R.string.btn_play)
            binding.tvStatus.text = getString(R.string.status_idle)
        }
    }

    // TtsCallback 实现
    override fun onInitSuccess() {
        runOnUiThread {
            voices = ttsService?.getVoices() ?: emptyList()
            setupVoiceSpinner()
        }
    }

    override fun onInitFailed() {
        runOnUiThread {
            updatePlayState(false)
            showTransientStatus(getString(R.string.status_tts_init_failed))
        }
    }

    override fun onPlayingStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            updatePlayState(isPlaying)
        }
    }

    override fun onVoicesAvailable(voices: List<Voice>) {
        runOnUiThread {
            this.voices = voices
            setupVoiceSpinner()
        }
    }

    override fun onSpeakFailed(reason: String) {
        runOnUiThread {
            // 播放失败时回到空闲状态，避免按钮卡在"停止播放"
            updatePlayState(false)
            showTransientStatus(reason)
        }
    }

    override fun onDestroy() {
        if (isBound) {
            // 只解除绑定，不停止播放：Service 已通过 startForegroundService 独立存活
            ttsService?.callback = null
            runCatching { unbindService(serviceConnection) }
            isBound = false
        }
        super.onDestroy()
    }
}
