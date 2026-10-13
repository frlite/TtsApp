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
                // 点击历史记录，填充到输入框并播放
                binding.etTextInput.setText(item.text)
                binding.etTextInput.setSelection(item.text.length)
                playText(item.text, item.speechRate, item.pitch)
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

        // 监听历史记录数据变化
        lifecycleScope.launch {
            database.historyDao().getAllHistory().collectLatest { historyList ->
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
            if (ttsService?.isPlaying() == true) {
                ttsService?.stopSpeaking()
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

    private fun playText(text: String, speed: Float, pitch: Float) {
        // 保存到历史记录
        ttsService?.saveHistory(text, speed, pitch)
        // 播放
        ttsService?.speak(text, speed, pitch, selectedVoice)
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
            binding.tvStatus.text = "TTS 引擎初始化失败"
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

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            ttsService?.callback = null
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
