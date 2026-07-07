package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.api.Content
import com.example.data.api.GenerateContentRequest
import com.example.data.api.Part
import com.example.data.api.RetrofitClient
import com.example.data.database.RadarEvent
import com.example.data.database.RadarRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class RadarViewModel(
    private val repository: RadarRepository,
    private val context: Context
) : ViewModel() {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // Target Wi-Fi network to track (defaults to current connected AP or simulated AP)
    private val _trackedSsid = MutableStateFlow("Room_Sensing_AP")
    val trackedSsid: StateFlow<String> = _trackedSsid.asStateFlow()

    private val _trackedBssid = MutableStateFlow("00:11:22:33:44:55")
    val trackedBssid: StateFlow<String> = _trackedBssid.asStateFlow()

    // Signal States
    private val _currentRssi = MutableStateFlow(-45)
    val currentRssi: StateFlow<Int> = _currentRssi.asStateFlow()

    private val _baseRssi = MutableStateFlow(-45)
    val baseRssi: StateFlow<Int> = _baseRssi.asStateFlow()

    private val _dropMagnitude = MutableStateFlow(0)
    val dropMagnitude: StateFlow<Int> = _dropMagnitude.asStateFlow()

    private val _signalHistory = MutableStateFlow<List<Int>>(List(30) { -45 })
    val signalHistory: StateFlow<List<Int>> = _signalHistory.asStateFlow()

    // Control States
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _isSimulationMode = MutableStateFlow(true) // Enabled by default to ensure emulator compatibility
    val isSimulationMode: StateFlow<Boolean> = _isSimulationMode.asStateFlow()

    private val _status = MutableStateFlow(" নিষ্ক্রিয় (Inactive)")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _statusType = MutableStateFlow(StatusType.CALM)
    val statusType: StateFlow<StatusType> = _statusType.asStateFlow()

    private val _inference = MutableStateFlow("শুরু করতে 'মনিটর শুরু করুন' টিপুন")
    val inference: StateFlow<String> = _inference.asStateFlow()

    // AI States
    private val _isAnalyzingWithAi = MutableStateFlow(false)
    val isAnalyzingWithAi: StateFlow<Boolean> = _isAnalyzingWithAi.asStateFlow()

    private val _aiAnalysisResult = MutableStateFlow<String?>(null)
    val aiAnalysisResult: StateFlow<String?> = _aiAnalysisResult.asStateFlow()

    // Nearby Wi-Fi Hotspots found during scans
    private val _scannedNetworks = MutableStateFlow<List<WifiApInfo>>(emptyList())
    val scannedNetworks: StateFlow<List<WifiApInfo>> = _scannedNetworks.asStateFlow()

    // Flow of recorded database events
    val loggedEvents: StateFlow<List<RadarEvent>> = repository.allEvents
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var monitoringJob: Job? = null
    private var simulationStep = 0
    private var currentSimPreset: SimulationPreset = SimulationPreset.CALM
    private var lastRecordedStatus: String = "NO_MOTION"
    private var lastStatusTransitionTime: Long = 0

    enum class StatusType {
        CALM, MOTION, ALERT, WARNING
    }

    enum class SimulationPreset {
        CALM, SLOW_WALK, FAST_INTRUDER, CHAOTIC
    }

    data class WifiApInfo(
        val ssid: String,
        val bssid: String,
        val rssi: Int
    )

    fun toggleMonitoring() {
        if (_isMonitoring.value) {
            stopMonitoring()
        } else {
            startMonitoring()
        }
    }

    fun setSimulationMode(enabled: Boolean) {
        _isSimulationMode.value = enabled
        if (_isMonitoring.value) {
            // Restart monitoring with the new mode
            stopMonitoring()
            startMonitoring()
        }
    }

    fun selectNetwork(ssid: String, bssid: String) {
        _trackedSsid.value = if (ssid.isEmpty()) "Unknown_AP" else ssid
        _trackedBssid.value = bssid
        // Reset base RSSI for the new network
        _baseRssi.value = -50
    }

    private fun startMonitoring() {
        _isMonitoring.value = true
        _status.value = "চলমান (Active)"
        monitoringJob = viewModelScope.launch {
            if (!_isSimulationMode.value) {
                // Initialize real baseline RSSI
                val initialRssi = getRealConnectedRssi()
                if (initialRssi != -127) {
                    _baseRssi.value = initialRssi
                    _currentRssi.value = initialRssi
                }
            } else {
                _baseRssi.value = -45
                _currentRssi.value = -45
            }

            while (_isMonitoring.value) {
                if (_isSimulationMode.value) {
                    runSimulationStep()
                } else {
                    runRealScanStep()
                }
                delay(1000)
            }
        }
    }

    private fun stopMonitoring() {
        _isMonitoring.value = false
        monitoringJob?.cancel()
        monitoringJob = null
        _status.value = "নিষ্ক্রিয় (Inactive)"
        _statusType.value = StatusType.CALM
        _inference.value = "মনিটরিং বন্ধ করা হয়েছে।"
    }

    // Trigger specific simulation events from UI
    fun triggerSimulationPreset(preset: SimulationPreset) {
        currentSimPreset = preset
        simulationStep = 0
        val eventName = when (preset) {
            SimulationPreset.CALM -> "স্বাভাবিক মোড চালু (Calm)"
            SimulationPreset.SLOW_WALK -> "ধীর নড়াচড়া শুরু (Slow Walk)"
            SimulationPreset.FAST_INTRUDER -> "হঠাৎ অনুপ্রবেশ শুরু (Fast Intruder)"
            SimulationPreset.CHAOTIC -> "একাধিক মানুষের নড়াচড়া (Chaos)"
        }
        _inference.value = "সিমুলেশন: $eventName ট্রিগার করা হয়েছে।"
    }

    private fun runSimulationStep() {
        val base = _baseRssi.value
        var nextRssi = base

        when (currentSimPreset) {
            SimulationPreset.CALM -> {
                // Calm state: subtle noise around baseline (-43 to -47)
                nextRssi = base + Random.nextInt(-2, 3)
            }
            SimulationPreset.SLOW_WALK -> {
                // Simulates a human walking slowly. 12 steps waveform
                val offsets = listOf(0, -1, -3, -8, -15, -24, -26, -20, -12, -5, -2, 0)
                val offset = if (simulationStep < offsets.size) offsets[simulationStep] else 0
                nextRssi = base + offset
                simulationStep++
                if (simulationStep >= offsets.size) {
                    currentSimPreset = SimulationPreset.CALM // return to calm
                }
            }
            SimulationPreset.FAST_INTRUDER -> {
                // Simulates a quick runner passing through. 6 steps waveform
                val offsets = listOf(-1, -6, -28, -25, -5, 0)
                val offset = if (simulationStep < offsets.size) offsets[simulationStep] else 0
                nextRssi = base + offset
                simulationStep++
                if (simulationStep >= offsets.size) {
                    currentSimPreset = SimulationPreset.CALM
                }
            }
            SimulationPreset.CHAOTIC -> {
                // Continuous high oscillation
                nextRssi = base + Random.nextInt(-22, 5)
                simulationStep++
                if (simulationStep > 15) {
                    currentSimPreset = SimulationPreset.CALM
                }
            }
        }

        updateRssiState(nextRssi)
    }

    @SuppressLint("MissingPermission")
    private fun runRealScanStep() {
        // Read connected network RSSI
        val currentConnectedRssi = getRealConnectedRssi()
        if (currentConnectedRssi != -127) {
            updateRssiState(currentConnectedRssi)
        } else {
            // Not connected. Try scanning and updating networks list
            _inference.value = "কোনো ওয়াই-ফাই কানেকশন নেই! অনুগ্রহ করে কানেক্ট করুন অথবা সিমুলেশন মোড ব্যবহার করুন।"
            _statusType.value = StatusType.WARNING
            _status.value = "সংযোগহীন (No Connection)"
        }

        // Periodically perform scan for list of nearby networks
        try {
            wifiManager?.let { wm ->
                if (wm.isWifiEnabled) {
                    val scanResults = wm.scanResults
                    val mappedList = scanResults.map { result ->
                        val ssidName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            result.wifiSsid?.toString() ?: result.SSID
                        } else {
                            result.SSID
                        }
                        WifiApInfo(
                            ssid = ssidName ?: "Hidden AP",
                            bssid = result.BSSID ?: "00:00:00:00:00:00",
                            rssi = result.level
                        )
                    }.sortedByDescending { it.rssi }
                    _scannedNetworks.value = mappedList
                }
            }
        } catch (e: Exception) {
            Log.e("RadarViewModel", "Scan error: ${e.message}")
        }
    }

    private fun getRealConnectedRssi(): Int {
        return try {
            wifiManager?.let { wm ->
                val info = wm.connectionInfo
                if (info != null && info.networkId != -1) {
                    val ssidName = info.ssid?.replace("\"", "") ?: "Connected AP"
                    _trackedSsid.value = ssidName
                    _trackedBssid.value = info.bssid ?: "00:11:22:33:44:55"
                    info.rssi
                } else {
                    -127
                }
            } ?: -127
        } catch (e: Exception) {
            -127
        }
    }

    private fun updateRssiState(nextRssi: Int) {
        _currentRssi.value = nextRssi

        // Manage baseline adaptively if stable. If change is small, move baseline slowly towards current
        val currentBase = _baseRssi.value
        val diff = nextRssi - currentBase

        if (abs(diff) < 4) {
            // Adaptive slow drift to adjust for environmental noise
            _baseRssi.value = (currentBase * 0.95 + nextRssi * 0.05).toInt()
        }

        val drop = _baseRssi.value - nextRssi
        _dropMagnitude.value = if (drop < 0) 0 else drop

        // Update historical signal list
        val currentHistory = _signalHistory.value.toMutableList()
        currentHistory.removeAt(0)
        currentHistory.add(nextRssi)
        _signalHistory.value = currentHistory

        evaluatePresenceAndLog(drop, nextRssi)
    }

    private fun evaluatePresenceAndLog(drop: Int, currentRssi: Int) {
        val now = System.currentTimeMillis()
        var currentStatus = "NO_MOTION"
        var banglaStatus = "স্বাভাবিক (Quiet)"
        var inferenceText = "সিগন্যাল স্বাভাবিক আছে। ঘরে কোনো বড় নড়াচড়া শনাক্ত হয়নি।"
        var statusTypeVal = StatusType.CALM

        if (drop >= 22) {
            currentStatus = "HIGH_INTRUSION"
            banglaStatus = "তীব্র বাধা (Intrusion Detected!)"
            inferenceText = "বিপজ্জনক সিগন্যাল পতন! ঘরে মানুষের দ্রুত চলাচল বা দরজার তীব্র বাধা শনাক্ত হয়েছে।"
            statusTypeVal = StatusType.ALERT
        } else if (drop >= 12) {
            currentStatus = "MOTION_DETECTED"
            banglaStatus = "নড়াচড়া শনাক্ত (Motion Detected)"
            inferenceText = "সিগন্যালে মাঝারি ধরনের বাধা। কেউ ঘরের মধ্য দিয়ে বা রাউটারের সামনে দিয়ে হেঁটে গেছে।"
            statusTypeVal = StatusType.MOTION
        } else if (drop < -15) {
            // Signal spikes abnormally (e.g. device moved or reflection)
            currentStatus = "RF_ANOMALY"
            banglaStatus = "সিগন্যাল অসঙ্গতি (Anomaly)"
            inferenceText = "হঠাৎ সিগন্যাল তীব্র বৃদ্ধি পেয়েছে। ডিভাইসটি রাউটারের কাছাকাছি আনা হয়ে থাকতে পারে।"
            statusTypeVal = StatusType.WARNING
        }

        _status.value = banglaStatus
        _statusType.value = statusTypeVal
        _inference.value = inferenceText

        // Log to database on status transitions to prevent flooding, or every 8 seconds if status is active
        val stateChanged = currentStatus != lastRecordedStatus
        val secondsSinceLastRecord = (now - lastStatusTransitionTime) / 1000

        if (stateChanged || (currentStatus != "NO_MOTION" && secondsSinceLastRecord >= 8)) {
            lastRecordedStatus = currentStatus
            lastStatusTransitionTime = now

            // Do not log "NO_MOTION" initially, log only when we actually detect something or transition back
            viewModelScope.launch {
                val dbStatusText = when (currentStatus) {
                    "HIGH_INTRUSION" -> "তীব্র অনুপ্রবেশ শনাক্ত (High Alert)"
                    "MOTION_DETECTED" -> "নড়াচড়া শনাক্ত (Motion)"
                    "RF_ANOMALY" -> "সিগন্যাল অসঙ্গতি (Anomaly)"
                    else -> "সিগন্যাল স্বাভাবিক হয়েছে (Stabilized)"
                }

                repository.insert(
                    RadarEvent(
                        ssid = _trackedSsid.value,
                        bssid = _trackedBssid.value,
                        rssi = currentRssi,
                        baseRssi = _baseRssi.value,
                        dropMagnitude = drop,
                        status = dbStatusText,
                        inference = inferenceText
                    )
                )
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAll()
            _aiAnalysisResult.value = null
        }
    }

    fun analyzeLogsWithGemini() {
        val currentLogs = loggedEvents.value
        if (currentLogs.isEmpty()) {
            _aiAnalysisResult.value = "বিশ্লেষণ করার মতো কোনো লগ রেকর্ড এখনও তৈরি হয়নি। প্রথমে কিছুক্ষণ মনিটর করুন।"
            return
        }

        _isAnalyzingWithAi.value = true
        _aiAnalysisResult.value = null

        viewModelScope.launch {
            val logsSummary = currentLogs.take(15).joinToString("\n") { log ->
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                "সময়: $timeStr, নেটওয়ার্ক: ${log.ssid}, সিগন্যাল: ${log.rssi} dBm, ড্রপ: ${log.dropMagnitude} dB, স্ট্যাটাস: ${log.status}"
            }

            val prompt = """
                You are an expert wireless sensing, Wi-Fi radar, and security analyst.
                The user has an Android app that uses Wi-Fi RSSI (Signal Strength) drops to detect human movement and presence.
                When a human walks in the line-of-sight between the phone and the Wi-Fi Access Point, RSSI drops significantly (by 12 to 30 dBm).
                
                Below are the recent 15 Wi-Fi signal drop events logged on the user's phone:
                $logsSummary
                
                Please provide an intelligent, professional, and reassuring diagnostic report in Bengali.
                The report must cover:
                1. A summary of what occurred based on these logs (e.g., how many intrusions or movements were detected).
                2. An analysis of whether the movement suggests a slow human walk, a fast runner, or general interference.
                3. Proactive tips in Bengali on how the user can place their Wi-Fi router and phone for maximum human detection accuracy (e.g., putting them at waist height, avoiding metallic obstacles, choosing the 2.4GHz band vs 5GHz band).
                
                Keep the response structured, clear, and elegant with bullet points. Speak in a helpful AI Assistant tone. Keep it under 250 words.
            """.trimIndent()

            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt))))
            )

            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    _aiAnalysisResult.value = "ত্রুটি: Gemini API Key সেট করা নেই। অনুগ্রহ করে AI Studio-এর Secrets প্যানেলে 'GEMINI_API_KEY' যোগ করুন।"
                } else {
                    val response = RetrofitClient.service.generateContent(apiKey, request)
                    val reply = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    _aiAnalysisResult.value = reply ?: "Gemini থেকে কোনো প্রতিক্রিয়া পাওয়া যায়নি।"
                }
            } catch (e: Exception) {
                _aiAnalysisResult.value = "বিশ্লেষণে ত্রুটি ঘটেছে: ${e.localizedMessage ?: e.message}"
            } finally {
                _isAnalyzingWithAi.value = false
            }
        }
    }
}

class RadarViewModelFactory(
    private val repository: RadarRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RadarViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RadarViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
