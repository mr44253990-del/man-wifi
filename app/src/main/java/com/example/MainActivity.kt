package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.BorderStroke
import com.example.data.database.AppDatabase
import com.example.data.database.RadarRepository
import com.example.data.database.RadarEvent
import com.example.ui.RadarViewModel
import com.example.ui.RadarViewModelFactory
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) { // Forcing dark mode for radar feel
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepDarkBlue
                ) {
                    RadarAppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarAppScreen() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context.applicationContext) }
    val repository = remember { RadarRepository(database.radarEventDao()) }
    val viewModel: RadarViewModel = viewModel(
        factory = RadarViewModelFactory(repository, context.applicationContext)
    )

    // Collecting States
    val isMonitoring by viewModel.isMonitoring.collectAsState()
    val isSimulationMode by viewModel.isSimulationMode.collectAsState()
    val currentRssi by viewModel.currentRssi.collectAsState()
    val baseRssi by viewModel.baseRssi.collectAsState()
    val dropMagnitude by viewModel.dropMagnitude.collectAsState()
    val signalHistory by viewModel.signalHistory.collectAsState()
    val trackedSsid by viewModel.trackedSsid.collectAsState()
    val trackedBssid by viewModel.trackedBssid.collectAsState()
    val status by viewModel.status.collectAsState()
    val statusType by viewModel.statusType.collectAsState()
    val inference by viewModel.inference.collectAsState()
    val scannedNetworks by viewModel.scannedNetworks.collectAsState()
    val loggedEvents by viewModel.loggedEvents.collectAsState()
    val isAnalyzingWithAi by viewModel.isAnalyzingWithAi.collectAsState()
    val aiAnalysisResult by viewModel.aiAnalysisResult.collectAsState()

    var showApSelectorDialog by remember { mutableStateOf(false) }

    // Dynamic state theme color
    val stateThemeColor by animateColorAsState(
        targetValue = when (statusType) {
            RadarViewModel.StatusType.CALM -> RadarNeonGreen
            RadarViewModel.StatusType.MOTION -> RadarYellow
            RadarViewModel.StatusType.ALERT -> RadarRed
            RadarViewModel.StatusType.WARNING -> RadarCyan
        },
        animationSpec = tween(500),
        label = "StateThemeColor"
    )

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            viewModel.setSimulationMode(false)
            Toast.makeText(context, "বাস্তব সেন্সর অ্যাক্টিভেট করা হয়েছে", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "অনুমতি না থাকায় সিমুলেশন মোড ব্যবহার করা হচ্ছে", Toast.LENGTH_LONG).show()
            viewModel.setSimulationMode(true)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "Wifi Radar",
                            tint = stateThemeColor,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "ওয়াই-ফাই রাডার (Sensing)",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DeepDarkBlue
                ),
                actions = {
                    IconButton(
                        onClick = {
                            if (isSimulationMode) {
                                // Request permissions to activate real mode
                                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (hasFine) {
                                    viewModel.setSimulationMode(false)
                                    Toast.makeText(context, "বাস্তব সেন্সর চালু করা হয়েছে", Toast.LENGTH_SHORT).show()
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            } else {
                                viewModel.setSimulationMode(true)
                                Toast.makeText(context, "সিমুলেশন মোড সক্রিয় করা হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("mode_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isSimulationMode) Icons.Default.DeveloperMode else Icons.Default.CompassCalibration,
                            contentDescription = "Toggle Real/Simulation",
                            tint = if (isSimulationMode) RadarYellow else RadarNeonGreen
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = DeepDarkBlue
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // 1. Radar Circular Sweeper Section
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f),
                    colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        RadarSweepView(
                            modifier = Modifier
                                .fillMaxSize(0.85f)
                                .align(Alignment.Center),
                            statusType = statusType,
                            currentRssi = currentRssi
                        )

                        // Mode Indicator Banner
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSimulationMode) RadarYellow.copy(alpha = 0.15f)
                                    else RadarNeonGreen.copy(alpha = 0.15f)
                                )
                                .border(
                                    1.dp,
                                    if (isSimulationMode) RadarYellow.copy(alpha = 0.4f)
                                    else RadarNeonGreen.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isSimulationMode) RadarYellow else RadarNeonGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isSimulationMode) "সিমুলেশন মোড (Demo)" else "বাস্তব সেন্সর মোড",
                                    color = if (isSimulationMode) RadarYellow else RadarNeonGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Core RSSI HUD Text
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                        ) {
                            Text(
                                text = "$currentRssi dBm",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "বেসলাইন: $baseRssi dBm | ক্ষতি: $dropMagnitude dB",
                                color = RadarTextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // 2. Monitoring Control Bar (Start/Stop)
            item {
                Button(
                    onClick = { viewModel.toggleMonitoring() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("start_stop_monitoring_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMonitoring) RadarRed else RadarNeonGreen
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isMonitoring) "Stop" else "Start",
                            tint = if (isMonitoring) Color.White else DeepDarkBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isMonitoring) "মনিটরিং বন্ধ করুন (Stop)" else "মনিটর শুরু করুন (Start Tracking)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMonitoring) Color.White else DeepDarkBlue
                        )
                    }
                }
            }

            // 3. Status Display Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, stateThemeColor.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "বর্তমান স্ট্যাটাস (Inference)",
                                color = RadarTextMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = stateThemeColor.copy(alpha = 0.15f)),
                                border = BorderStroke(1.dp, stateThemeColor.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = status,
                                    color = stateThemeColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            text = inference,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "লাইভ সিগন্যাল ওয়েভ (Last 30s)",
                            color = RadarTextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Smooth line chart canvas
                        LiveWaveChart(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.2f)),
                            history = signalHistory,
                            statusType = statusType
                        )
                    }
                }
            }

            // 4. Target Network / Scanned AP Info
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "সংযুক্ত ওয়াই-ফাই সোর্স (Source AP)",
                                    color = RadarTextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = trackedSsid,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "BSSID: $trackedBssid",
                                    color = RadarTextMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            if (!isSimulationMode) {
                                Button(
                                    onClick = { showApSelectorDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Scan AP", modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("স্ক্যান AP", color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }

                        if (isSimulationMode) {
                            Text(
                                text = "💡 টিপস: আপনি বাস্তব সেন্সর অ্যাক্টিভেট করতে উপরের ডানদিকের বাটনটি চাপুন। বাস্তব ডিভাইস সিগন্যাল ওঠানামা পর্যবেক্ষণ করতে পারবেন!",
                                color = RadarCyan,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // 5. Active Simulator Scenario Controls (When in Simulation Mode)
            if (isSimulationMode) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, RadarYellow.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SettingsInputAntenna, contentDescription = null, tint = RadarYellow, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "সিমুলেশন ট্রিগার (Simulation Telemetry)",
                                    color = RadarYellow,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Text(
                                text = "ঘরের ভেতরের বিভিন্ন নড়াচড়া ও মানুষের অনুপ্রবেশ অনুকরণ করতে নিচের যেকোনো বাটনে চাপুন:",
                                color = RadarTextMuted,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )

                            // Simulator Buttons Grid
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.triggerSimulationPreset(RadarViewModel.SimulationPreset.CALM) },
                                        modifier = Modifier.weight(1f).testTag("sim_preset_calm"),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("স্বাভাবিক (Quiet)", color = Color.White, fontSize = 11.sp, maxLines = 1)
                                    }
                                    Button(
                                        onClick = { viewModel.triggerSimulationPreset(RadarViewModel.SimulationPreset.SLOW_WALK) },
                                        modifier = Modifier.weight(1f).testTag("sim_preset_slow"),
                                        colors = ButtonDefaults.buttonColors(containerColor = RadarYellow.copy(alpha = 0.15f)),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, RadarYellow.copy(alpha = 0.3f))
                                    ) {
                                        Text("হেঁটে যাওয়া (Walk)", color = RadarYellow, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.triggerSimulationPreset(RadarViewModel.SimulationPreset.FAST_INTRUDER) },
                                        modifier = Modifier.weight(1f).testTag("sim_preset_fast"),
                                        colors = ButtonDefaults.buttonColors(containerColor = RadarRed.copy(alpha = 0.15f)),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, RadarRed.copy(alpha = 0.3f))
                                    ) {
                                        Text("অনুপ্রবেশ (Intruder)", color = RadarRed, fontSize = 11.sp, maxLines = 1)
                                    }
                                    Button(
                                        onClick = { viewModel.triggerSimulationPreset(RadarViewModel.SimulationPreset.CHAOTIC) },
                                        modifier = Modifier.weight(1f).testTag("sim_preset_chaos"),
                                        colors = ButtonDefaults.buttonColors(containerColor = RadarCyan.copy(alpha = 0.15f)),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.3f))
                                    ) {
                                        Text("একাধিক মানুষ (Chaos)", color = RadarCyan, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Gemini AI Analysis section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = RadarCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Gemini AI সিগন্যাল বিশ্লেষণ",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = { viewModel.analyzeLogsWithGemini() },
                                colors = ButtonDefaults.buttonColors(containerColor = RadarCyan.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isAnalyzingWithAi,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("ai_analyze_button")
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isAnalyzingWithAi) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            color = RadarCyan,
                                            strokeWidth = 1.5.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("বিশ্লেষণ হচ্ছে...", color = RadarCyan, fontSize = 11.sp)
                                    } else {
                                        Icon(Icons.Default.Analytics, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("রিপোর্ট তৈরি", color = RadarCyan, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        Divider(color = Color.White.copy(alpha = 0.05f))

                        if (isAnalyzingWithAi) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = RadarCyan)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Gemini AI আপনার ঘরের সিগন্যাল লগ বিশ্লেষণ করছে...",
                                    color = RadarTextMuted,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            if (aiAnalysisResult != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(alpha = 0.25f))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = aiAnalysisResult!!,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Info, contentDescription = null, tint = RadarTextMuted, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "মনিটরিংয়ের পর 'রিপোর্ট তৈরি' চাপুন। Gemini AI আপনার ঘরের সিগন্যাল ওঠানামা বিশ্লেষণ করে রিপোর্ট প্রদান করবে।",
                                        color = RadarTextMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. Recorded History Feed Title & Action
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.History, contentDescription = null, tint = RadarTextMuted, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ডিটেকশন টাইমলাইন (${loggedEvents.size})",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (loggedEvents.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.clearHistory() },
                            modifier = Modifier.testTag("clear_history_button")
                        ) {
                            Text("লগ মুছুন", color = RadarRed, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 8. Event Feed Timeline List
            if (loggedEvents.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = "No scan logs",
                            tint = RadarTextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "কোনো ডিটেকশন ইভেন্ট রেকর্ড নেই",
                            color = RadarTextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "মনিটর চালু করুন এবং সিগন্যাল কমালে ইভেন্ট রেকর্ড হবে",
                            color = RadarTextMuted.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                }
            } else {
                items(
                    items = loggedEvents.take(40), // Show last 40 entries
                    key = { it.id }
                ) { event ->
                    RadarEventLogItem(event = event)
                }
            }
        }
    }

    // Wi-Fi Access Point Selection Dialog (Real Mode)
    if (showApSelectorDialog) {
        AlertDialog(
            onDismissRequest = { showApSelectorDialog = false },
            title = {
                Text("Lock onto Transmitter AP", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                if (scannedNetworks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = RadarNeonGreen)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Nearby Wi-Fi networks scanning...", color = RadarTextMuted, fontSize = 13.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(scannedNetworks) { network ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .clickable {
                                        viewModel.selectNetwork(network.ssid, network.bssid)
                                        showApSelectorDialog = false
                                        Toast.makeText(context, "${network.ssid} locked as active signal", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = network.ssid, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(text = network.bssid, color = RadarTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            network.rssi >= -55 -> RadarNeonGreen.copy(alpha = 0.15f)
                                            network.rssi >= -75 -> RadarYellow.copy(alpha = 0.15f)
                                            else -> RadarRed.copy(alpha = 0.15f)
                                        }
                                    ),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "${network.rssi} dBm",
                                        color = when {
                                            network.rssi >= -55 -> RadarNeonGreen
                                            network.rssi >= -75 -> RadarYellow
                                            else -> RadarRed
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showApSelectorDialog = false }) {
                    Text("Close", color = RadarNeonGreen)
                }
            },
            containerColor = RadarCardBg,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun RadarSweepView(
    modifier: Modifier = Modifier,
    statusType: RadarViewModel.StatusType,
    currentRssi: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SweepAngle"
    )

    val color = when (statusType) {
        RadarViewModel.StatusType.CALM -> RadarNeonGreen
        RadarViewModel.StatusType.MOTION -> RadarYellow
        RadarViewModel.StatusType.ALERT -> RadarRed
        RadarViewModel.StatusType.WARNING -> RadarCyan
    }

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseScale"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.9f

        // Draw grids
        drawCircle(
            color = color.copy(alpha = 0.15f),
            radius = radius,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = 0.1f),
            radius = radius * 0.66f,
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = 0.08f),
            radius = radius * 0.33f,
            style = Stroke(width = 1.dp.toPx())
        )

        // Draw cross lines
        drawLine(
            color = color.copy(alpha = 0.12f),
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = color.copy(alpha = 0.12f),
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 1.dp.toPx()
        )

        // Draw animated pulse
        drawCircle(
            color = color.copy(alpha = 0.05f * (1.0f - pulseScale)),
            radius = radius * pulseScale,
            style = Stroke(width = 6.dp.toPx())
        )

        // Draw rotating sweep sector
        val sweepAngleRad = Math.toRadians(angle.toDouble())
        val endX = center.x + radius * Math.cos(sweepAngleRad).toFloat()
        val endY = center.y + radius * Math.sin(sweepAngleRad).toFloat()

        drawLine(
            color = color.copy(alpha = 0.7f),
            start = center,
            end = Offset(endX, endY),
            strokeWidth = 2.5.dp.toPx()
        )

        drawArc(
            color = color.copy(alpha = 0.08f),
            startAngle = angle - 45f,
            sweepAngle = 45f,
            useCenter = true,
            size = Size(radius * 2, radius * 2),
            topLeft = Offset(center.x - radius, center.y - radius)
        )

        // Draw central blinking node representing target lock
        val intensity = ((currentRssi + 127f) / (127f - 30f)).coerceIn(0f, 1f)
        val targetRadius = 7.dp.toPx() + (intensity * 4.dp.toPx())
        drawCircle(
            color = color,
            radius = targetRadius,
            center = center
        )
        drawCircle(
            color = Color.White,
            radius = 2.5.dp.toPx(),
            center = center
        )
    }
}

@Composable
fun LiveWaveChart(
    modifier: Modifier = Modifier,
    history: List<Int>,
    statusType: RadarViewModel.StatusType
) {
    val color = when (statusType) {
        RadarViewModel.StatusType.CALM -> RadarNeonGreen
        RadarViewModel.StatusType.MOTION -> RadarYellow
        RadarViewModel.StatusType.ALERT -> RadarRed
        RadarViewModel.StatusType.WARNING -> RadarCyan
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (history.size < 2) return@Canvas

        val maxVal = -30f
        val minVal = -95f
        val range = maxVal - minVal

        val points = history.mapIndexed { index, rssi ->
            val x = (index.toFloat() / (history.size - 1)) * width
            val boundedRssi = rssi.toFloat().coerceIn(minVal, maxVal)
            val y = height - ((boundedRssi - minVal) / range) * height
            Offset(x, y)
        }

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val curr = points[i]
                val cp1X = prev.x + (curr.x - prev.x) / 2
                val cp1Y = prev.y
                val cp2X = prev.x + (curr.x - prev.x) / 2
                val cp2Y = curr.y
                cubicTo(cp1X, cp1Y, cp2X, cp2Y, curr.x, curr.y)
            }
        }

        val fillPath = Path().apply {
            addPath(path)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.2f), color.copy(alpha = 0.0f)),
                startY = 0f,
                endY = height
            )
        )

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round)
        )

        val gridLines = 3
        for (i in 0..gridLines) {
            val y = (height / gridLines) * i
            drawLine(
                color = Color.White.copy(alpha = 0.05f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@Composable
fun RadarEventLogItem(event: RadarEvent) {
    val timeStr = remember(event.timestamp) {
        SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(event.timestamp))
    }

    val isAlert = event.status.contains("তীব্র") || event.status.contains("Alert")
    val isAnomaly = event.status.contains("অসঙ্গতি") || event.status.contains("Anomaly")

    val itemColor = when {
        isAlert -> RadarRed
        isAnomaly -> RadarCyan
        event.status.contains("নড়াচড়া") || event.status.contains("Motion") -> RadarYellow
        else -> RadarNeonGreen
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circle representing severity
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(itemColor)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = event.status,
                        color = itemColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = timeStr,
                        color = RadarTextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = event.inference,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ড্রপ: ${event.dropMagnitude} dB | সিগন্যাল: ${event.rssi} dBm",
                        color = RadarTextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = event.ssid,
                        color = RadarTextMuted.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
