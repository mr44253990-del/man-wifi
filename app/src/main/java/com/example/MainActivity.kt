package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
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
import com.example.data.database.AppDatabase
import com.example.data.database.RadarEvent
import com.example.data.database.RadarRepository
import com.example.ui.RadarViewModel
import com.example.ui.RadarViewModelFactory
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepDarkBlue
                ) {
                    RadarMainScreen()
                }
            }
        }
    }
}

// ---------------------------------------------------------
// Synthesizer Audio Utility for Cyberpunk Sonar Sound
// ---------------------------------------------------------
fun playSonarBeep(frequency: Double = 880.0, durationMs: Int = 120) {
    val sampleRate = 44100
    val numSamples = (durationMs / 1000.0 * sampleRate).toInt()
    val sample = DoubleArray(numSamples)
    val generatedSnd = ByteArray(2 * numSamples)

    for (i in 0 until numSamples) {
        sample[i] = sin(2 * Math.PI * i / (sampleRate / frequency))
    }

    var idx = 0
    for (dVal in sample) {
        val valShort = (dVal * 32767).toInt().toShort()
        generatedSnd[idx++] = (valShort.toInt() and 0x00ff).toByte()
        generatedSnd[idx++] = ((valShort.toInt() and 0xff00) ushr 8).toByte()
    }

    try {
        val audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            generatedSnd.size,
            AudioTrack.MODE_STATIC
        )
        audioTrack.write(generatedSnd, 0, generatedSnd.size)
        audioTrack.play()
        Thread {
            try {
                Thread.sleep(durationMs.toLong() + 50)
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                // Ignore
            }
        }.start()
    } catch (e: Exception) {
        Log.e("Audio", "Sonar sound failed: ${e.message}")
    }
}

@Composable
fun RadarMainScreen() {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context.applicationContext) }
    val repository = remember { RadarRepository(database.radarEventDao()) }
    val viewModel: RadarViewModel = viewModel(
        factory = RadarViewModelFactory(repository, context.applicationContext)
    )

    // Collect variables
    val currentTab by viewModel.currentTab.collectAsState()
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

    // Panel states
    val distanceMeters by viewModel.distanceMeters.collectAsState()
    val errorMargin by viewModel.errorMargin.collectAsState()
    val compassHeading by viewModel.compassHeading.collectAsState()
    val directionName by viewModel.directionName.collectAsState()
    val humanProbability by viewModel.humanProbability.collectAsState()
    val humanDetectionStatus by viewModel.humanDetectionStatus.collectAsState()
    val obstacleProbability by viewModel.obstacleProbability.collectAsState()
    val obstacleDistance by viewModel.obstacleDistance.collectAsState()
    val noiseLevel by viewModel.noiseLevel.collectAsState()
    val connectionQuality by viewModel.connectionQuality.collectAsState()
    val userPathX by viewModel.userPathX.collectAsState()
    val userPathY by viewModel.userPathY.collectAsState()

    // State Color mapping
    val stateThemeColor by animateColorAsState(
        targetValue = when (statusType) {
            RadarViewModel.StatusType.CALM -> RadarNeonGreen
            RadarViewModel.StatusType.MOTION -> RadarYellow
            RadarViewModel.StatusType.ALERT -> RadarRed
            RadarViewModel.StatusType.WARNING -> RadarCyan
        },
        animationSpec = tween(600),
        label = "StateColor"
    )

    val isBluetoothScanning by viewModel.isBluetoothScanning.collectAsState()
    val bluetoothStatus by viewModel.bluetoothStatus.collectAsState()
    val scannedBluetoothDevices by viewModel.scannedBluetoothDevices.collectAsState()

    // Permission handle
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val btScanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false
        } else {
            true
        }
        
        if (fineGranted || coarseGranted) {
            Toast.makeText(context, "বাস্তব ওয়াই-ফাই ও লোকেশন সেন্সর সক্রিয় করা হয়েছে", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "অনুমতি না থাকায় কিছু ফিচার সীমাবদ্ধ থাকতে পারে। অনুগ্রহ করে পারমিশন দিন।", Toast.LENGTH_LONG).show()
        }
        
        if (!btScanGranted) {
            Toast.makeText(context, "ব্লুটুথ স্ক্যান পারমিশন না দিলে আশেপাশের ডিভাইস শনাক্ত করা যাবে না।", Toast.LENGTH_LONG).show()
        }
    }

    val permissionsToRequest = remember {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        list.toTypedArray()
    }

    LaunchedEffect(Unit) {
        // Automatically request permissions on entering the application
        permissionLauncher.launch(permissionsToRequest)
    }

    Scaffold(
        bottomBar = {
            RadarBottomNav(
                currentTab = currentTab,
                onTabSelected = { viewModel.setTab(it) }
            )
        },
        containerColor = DeepDarkBlue
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                "home" -> {
                    HomeDashboardScreen(
                        viewModel = viewModel,
                        isMonitoring = isMonitoring,
                        isSimulationMode = isSimulationMode,
                        currentRssi = currentRssi,
                        baseRssi = baseRssi,
                        dropMagnitude = dropMagnitude,
                        signalHistory = signalHistory,
                        trackedSsid = trackedSsid,
                        trackedBssid = trackedBssid,
                        status = status,
                        statusType = statusType,
                        inference = inference,
                        distanceMeters = distanceMeters,
                        errorMargin = errorMargin,
                        compassHeading = compassHeading,
                        directionName = directionName,
                        humanProbability = humanProbability,
                        humanDetectionStatus = humanDetectionStatus,
                        obstacleProbability = obstacleProbability,
                        obstacleDistance = obstacleDistance,
                        noiseLevel = noiseLevel,
                        connectionQuality = connectionQuality,
                        userPathX = userPathX,
                        userPathY = userPathY,
                        stateThemeColor = stateThemeColor,
                        permissionLauncher = permissionLauncher
                    )
                }
                "map" -> {
                    InteractiveMapScreen(
                        viewModel = viewModel,
                        userPathX = userPathX,
                        userPathY = userPathY,
                        distanceMeters = distanceMeters,
                        statusType = statusType,
                        stateThemeColor = stateThemeColor
                    )
                }
                "radar" -> {
                    ImmersiveRadarScreen(
                        viewModel = viewModel,
                        statusType = statusType,
                        currentRssi = currentRssi,
                        distanceMeters = distanceMeters,
                        stateThemeColor = stateThemeColor
                    )
                }
                "sonar" -> {
                    val sonarActive by viewModel.sonarActive.collectAsState()
                    val sonarStatus by viewModel.sonarStatus.collectAsState()
                    val sonarDetectedDistance by viewModel.sonarDetectedDistance.collectAsState()
                    val sonarEchoIntensity by viewModel.sonarEchoIntensity.collectAsState()
                    val sonarTargetAngle by viewModel.sonarTargetAngle.collectAsState()
                    val compassHeading by viewModel.compassHeading.collectAsState()

                    ActiveSonarScreen(
                        viewModel = viewModel,
                        isActive = sonarActive,
                        status = sonarStatus,
                        detectedDistance = sonarDetectedDistance,
                        echoIntensity = sonarEchoIntensity,
                        targetAngle = sonarTargetAngle,
                        compassHeading = compassHeading
                    )
                }
                "bluetooth" -> {
                    BluetoothDeviceRadarScreen(
                        viewModel = viewModel,
                        isScanning = isBluetoothScanning,
                        scanStatus = bluetoothStatus,
                        devices = scannedBluetoothDevices
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------
// Navigation Component
// ---------------------------------------------------------
@Composable
fun RadarBottomNav(
    currentTab: String,
    onTabSelected: (String) -> Unit
) {
    NavigationBar(
        containerColor = RadarCardBg,
        tonalElevation = 8.dp,
        modifier = Modifier
            .border(
                BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            )
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        val navItems = listOf(
            Triple("home", "হোম", Icons.Outlined.Dashboard),
            Triple("map", "ম্যাপ", Icons.Outlined.Map),
            Triple("radar", "রাডার", Icons.Outlined.Radar),
            Triple("bluetooth", "ব্লুটুথ", Icons.Outlined.Bluetooth),
            Triple("sonar", "সোনার", Icons.Outlined.GraphicEq)
        )

        navItems.forEach { (route, label, icon) ->
            val isSelected = currentTab == route
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(route) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) RadarNeonGreen else RadarTextMuted
                    )
                },
                label = {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else RadarTextMuted,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = RadarNeonGreen.copy(alpha = 0.12f)
                ),
                modifier = Modifier.testTag("nav_tab_$route")
            )
        }
    }
}

// ---------------------------------------------------------
// Dashboard View (HOME)
// ---------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(
    viewModel: RadarViewModel,
    isMonitoring: Boolean,
    isSimulationMode: Boolean,
    currentRssi: Int,
    baseRssi: Int,
    dropMagnitude: Int,
    signalHistory: List<Int>,
    trackedSsid: String,
    trackedBssid: String,
    status: String,
    statusType: RadarViewModel.StatusType,
    inference: String,
    distanceMeters: Double,
    errorMargin: Double,
    compassHeading: Float,
    directionName: String,
    humanProbability: Float,
    humanDetectionStatus: String,
    obstacleProbability: Float,
    obstacleDistance: Double,
    noiseLevel: Int,
    connectionQuality: String,
    userPathX: Float,
    userPathY: Float,
    stateThemeColor: Color,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        // App Header
        CenterAlignedTopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wifi",
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
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = DeepDarkBlue),
            actions = {
                // Interactive Mode Switcher with glow borders
                IconButton(
                    onClick = {
                        if (isSimulationMode) {
                            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (hasFine) {
                                viewModel.setSimulationMode(false)
                                Toast.makeText(context, "বাস্তব ওয়াই-ফাই ডেমো বন্ধ", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, "সিমুলেশন মোড সক্রিয়", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("dashboard_mode_button")
                ) {
                    Icon(
                        imageVector = if (isSimulationMode) Icons.Default.DeveloperMode else Icons.Default.CompassCalibration,
                        contentDescription = "Toggle Mock",
                        tint = if (isSimulationMode) RadarYellow else RadarNeonGreen
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            // Panel Row 1: Distance & Direction
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Router Distance Panel
                    Card(
                        modifier = Modifier
                            .weight(1.1f)
                            .height(210.dp),
                        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "রাউটারের দূরত্ব",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "আপনার থেকে রাউটারের দূরত্ব",
                                color = RadarTextMuted,
                                fontSize = 10.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "$distanceMeters মিটার",
                                    color = RadarNeonGreen,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "(±$errorMargin m)",
                                    color = RadarTextMuted,
                                    fontSize = 10.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // 3D Room isometric preview Canvas
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.15f))
                            ) {
                                IsometricRoomCanvas(
                                    userX = userPathX,
                                    userY = userPathY,
                                    pulseColor = stateThemeColor
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "সিগন্যাল শক্তি: $currentRssi dBm",
                                color = if (currentRssi >= -55) RadarNeonGreen else RadarYellow,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }

                    // Router Direction Panel
                    Card(
                        modifier = Modifier
                            .weight(0.9f)
                            .height(210.dp),
                        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "রাউটারের দিক নির্দেশনা",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "রাউটার আপনার কোন দিকে আছে",
                                color = RadarTextMuted,
                                fontSize = 9.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = directionName,
                                color = RadarNeonGreen,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${compassHeading.toInt()}°",
                                color = RadarCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Beautiful Compass dial view
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                CompassDialView(
                                    heading = compassHeading,
                                    modifier = Modifier.size(85.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Panel Row 2: 3D Path Direction (Full block pathfinding)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "3D পথ (রাস্তাসহ) নির্দেশনা",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "আপনি থেকে রাউটার পর্যন্ত পথ",
                            color = RadarTextMuted,
                            fontSize = 10.sp
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        // High fidelity 3D path visualizer
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                        ) {
                            IsometricStreetCanvas(userX = userPathX, userY = userPathY)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Legend tags row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LegendBadge("আপনি", RadarCyan)
                                LegendBadge("রাস্তা", Color(0xFF424242))
                                LegendBadge("প্রাচীর", RadarYellow)
                                LegendBadge("রাউটার", RadarNeonGreen)
                            }
                            
                            Text(
                                text = "মোট দূরত্ব: $distanceMeters মিটার",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Panel Row 3: Grid of 3 panels: Live Radar, AI Presence, Warning HUD
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Panel 1: Live Radar Sweep
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp),
                        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "লাইভ রাডার",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "সিগন্যাল শক্তি ও দূরত্ব",
                                color = RadarTextMuted,
                                fontSize = 8.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                SimpleMiniSweepView(
                                    modifier = Modifier.size(90.dp),
                                    statusType = statusType,
                                    currentRssi = currentRssi,
                                    distance = distanceMeters,
                                    compassHeading = compassHeading
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("দুর্বল", color = RadarTextMuted, fontSize = 7.sp)
                                Box(
                                    modifier = Modifier
                                        .width(50.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(RadarRed, RadarYellow, RadarNeonGreen)
                                            )
                                        )
                                )
                                Text("শক্তিশালী", color = RadarNeonGreen, fontSize = 7.sp)
                            }
                        }
                    }

                    // Panel 2: What's ahead AI Detection
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp),
                        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "সামনে কি আছে? (AI)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "সামনের দিকের লাইভ ভিউ",
                                color = RadarTextMuted,
                                fontSize = 8.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.2f))
                            ) {
                                DepthSenseWireframe(
                                    humanProb = humanProbability,
                                    obstacleProb = obstacleProbability,
                                    obstacleDist = obstacleDistance
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (humanProbability > 50f) RadarRed else RadarNeonGreen)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (humanProbability > 50f) "বাধা শনাক্ত!" else "স্ট্যাটাস: পথ পরিষ্কার ✓",
                                    color = if (humanProbability > 50f) RadarRed else RadarNeonGreen,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Panel 3: Warning HUD
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp),
                        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(0.5.dp, stateThemeColor.copy(alpha = 0.2f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "দূরে গেলে এলার্ট",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "রাউটার থেকে দূরত্ব",
                                color = RadarTextMuted,
                                fontSize = 8.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "$distanceMeters মিটার",
                                color = if (distanceMeters > 12.0) RadarRed else RadarNeonGreen,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "সিগন্যাল শক্তি",
                                color = RadarTextMuted,
                                fontSize = 8.sp
                            )
                            Text(
                                text = "$currentRssi dBm",
                                color = if (currentRssi < -70) RadarRed else RadarNeonGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (currentRssi < -65 || distanceMeters > 11.5) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(RadarRed.copy(alpha = 0.15f))
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = "সিগন্যাল দুর্বল হচ্ছে! রাউটারের কাছে যান",
                                        color = RadarRed,
                                        fontSize = 7.sp,
                                        lineHeight = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(RadarNeonGreen.copy(alpha = 0.1f))
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = "সিগন্যাল সংযোগ স্থিতিশীল আছে",
                                        color = RadarNeonGreen,
                                        fontSize = 7.sp,
                                        lineHeight = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Panel Row 4: Status / Inference Text
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, stateThemeColor.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "পরিবেশ বিশ্লেষণ (Live State)",
                                color = RadarTextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = stateThemeColor.copy(alpha = 0.15f)),
                                border = BorderStroke(1.dp, stateThemeColor.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = status,
                                    color = stateThemeColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = inference,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        LiveWaveChart(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            history = signalHistory,
                            statusType = statusType
                        )
                    }
                }
            }
        }

        // Action controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { viewModel.toggleMonitoring() },
                modifier = Modifier
                    .weight(1.5f)
                    .height(52.dp)
                    .testTag("start_stop_monitoring_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMonitoring) RadarRed else RadarNeonGreen
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isMonitoring) Color.White else DeepDarkBlue
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isMonitoring) "মনিটরিং বন্ধ করুন" else "মনিটরিং শুরু করুন (Start)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMonitoring) Color.White else DeepDarkBlue
                    )
                }
            }

            Button(
                onClick = { viewModel.setTab("settings") },
                modifier = Modifier
                    .weight(0.7f)
                    .height(52.dp)
                    .testTag("dashboard_settings_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("সেটিংস", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun LegendBadge(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, color = RadarTextMuted, fontSize = 9.sp)
    }
}

// ---------------------------------------------------------
// COMPASS DIAL VIEW
// ---------------------------------------------------------
@Composable
fun CompassDialView(
    heading: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.95f

        // Draw compass circle dial
        drawCircle(
            color = Color.White.copy(alpha = 0.08f),
            radius = radius,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = RadarNeonGreen.copy(alpha = 0.1f),
            radius = radius * 0.85f,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Cardinal directions ticks (N, E, S, W) relative to heading
        // If heading is 30 deg, north (0) is at -30 deg relative to phone top
        val relativeHeadingRad = Math.toRadians(-heading.toDouble())

        val directions = listOf(
            "N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0
        )

        directions.forEach { (label, degree) ->
            val angleRad = relativeHeadingRad + Math.toRadians(degree) - Math.PI / 2
            val tickStart = Offset(
                center.x + (radius * 0.7f) * cos(angleRad).toFloat(),
                center.y + (radius * 0.7f) * sin(angleRad).toFloat()
            )
            val tickEnd = Offset(
                center.x + (radius * 0.82f) * cos(angleRad).toFloat(),
                center.y + (radius * 0.82f) * sin(angleRad).toFloat()
            )
            drawLine(
                color = if (label == "N") RadarRed else Color.White.copy(alpha = 0.4f),
                start = tickStart,
                end = tickEnd,
                strokeWidth = 1.5.dp.toPx()
            )
        }

        // Glowing green arrow needle pointing Northeast (30 deg absolute, which is relative angle 30 - heading)
        val arrowAngleRad = Math.toRadians((30f - heading - 90f).toDouble())
        val arrowLength = radius * 0.75f
        val arrowTip = Offset(
            center.x + arrowLength * cos(arrowAngleRad).toFloat(),
            center.y + arrowLength * sin(arrowAngleRad).toFloat()
        )

        val leftWing = Offset(
            center.x + (radius * 0.25f) * cos(arrowAngleRad - Math.PI * 0.8).toFloat(),
            center.y + (radius * 0.25f) * sin(arrowAngleRad - Math.PI * 0.8).toFloat()
        )
        val rightWing = Offset(
            center.x + (radius * 0.25f) * cos(arrowAngleRad + Math.PI * 0.8).toFloat(),
            center.y + (radius * 0.25f) * sin(arrowAngleRad + Math.PI * 0.8).toFloat()
        )

        val path = Path().apply {
            moveTo(arrowTip.x, arrowTip.y)
            lineTo(leftWing.x, leftWing.y)
            lineTo(center.x, center.y)
            lineTo(rightWing.x, rightWing.y)
            close()
        }

        drawPath(
            path = path,
            color = RadarNeonGreen
        )

        // Center hub
        drawCircle(
            color = DeepDarkBlue,
            radius = 4.dp.toPx()
        )
        drawCircle(
            color = Color.White,
            radius = 2.dp.toPx()
        )
    }
}

// ---------------------------------------------------------
// 3D ISOMETRIC ROOM CANVAS
// ---------------------------------------------------------
@Composable
fun IsometricRoomCanvas(
    userX: Float,
    userY: Float,
    pulseColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RoomPulse")
    val pulseProg by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseProgress"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val cx = w / 2f
        val cy = h / 2f + 10.dp.toPx()

        // Helper to convert normalized Room coordinates (0 to 1) to isometric screen coordinates
        // Room bounds: X goes -100 to 100, Y goes -100 to 100
        fun toIso(rx: Float, ry: Float, rz: Float = 0f): Offset {
            val scale = size.minDimension * 0.38f
            // Iso transform
            val xScreen = cx + (rx - ry) * cos(Math.toRadians(30.0)).toFloat() * scale
            val yScreen = cy + (rx + ry) * sin(Math.toRadians(30.0)).toFloat() * scale - rz * scale
            return Offset(xScreen, yScreen)
        }

        // Draw isometric Grid Floor (-1 to 1)
        val divisions = 5
        for (i in 0..divisions) {
            val ratio = i.toFloat() / divisions
            // Grid lines along X
            val line1Start = toIso(-0.6f, -0.6f + ratio * 1.2f)
            val line1End = toIso(0.6f, -0.6f + ratio * 1.2f)
            drawLine(
                color = Color.White.copy(alpha = 0.05f),
                start = line1Start,
                end = line1End,
                strokeWidth = 1.dp.toPx()
            )

            // Grid lines along Y
            val line2Start = toIso(-0.6f + ratio * 1.2f, -0.6f)
            val line2End = toIso(-0.6f + ratio * 1.2f, 0.6f)
            drawLine(
                color = Color.White.copy(alpha = 0.05f),
                start = line2Start,
                end = line2End,
                strokeWidth = 1.dp.toPx()
            )
        }

        // Draw Room transparent 3D walls
        val backCorner = toIso(-0.6f, -0.6f)
        val leftCorner = toIso(0.6f, -0.6f)
        val rightCorner = toIso(-0.6f, 0.6f)
        val frontCorner = toIso(0.6f, 0.6f)

        val wallHeight = 0.45f
        val backCornerTop = toIso(-0.6f, -0.6f, wallHeight)
        val leftCornerTop = toIso(0.6f, -0.6f, wallHeight)
        val rightCornerTop = toIso(-0.6f, 0.6f, wallHeight)

        // Draw Wall structure
        drawLine(Color.White.copy(alpha = 0.15f), backCorner, leftCorner, 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.15f), backCorner, rightCorner, 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.1f), leftCorner, frontCorner, 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.1f), rightCorner, frontCorner, 1.dp.toPx())

        // Vertical pillars
        drawLine(Color.White.copy(alpha = 0.15f), backCorner, backCornerTop, 1.5.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.15f), leftCorner, leftCornerTop, 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.15f), rightCorner, rightCornerTop, 1.dp.toPx())

        // Top walls
        drawLine(Color.White.copy(alpha = 0.15f), backCornerTop, leftCornerTop, 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.15f), backCornerTop, rightCornerTop, 1.dp.toPx())

        // Draw Router Marker (at constant room coordinate: rx = -0.4, ry = -0.4)
        val routerPos = toIso(-0.4f, -0.4f)
        val routerPosTop = toIso(-0.4f, -0.4f, 0.15f)

        // Draw little router 3D stand
        drawLine(RadarNeonGreen.copy(alpha = 0.5f), routerPos, routerPosTop, 1.5.dp.toPx())
        drawRect(
            color = RadarNeonGreen,
            topLeft = Offset(routerPosTop.x - 6.dp.toPx(), routerPosTop.y - 3.dp.toPx()),
            size = Size(12.dp.toPx(), 6.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        // Router antennas
        drawLine(RadarNeonGreen, routerPosTop, Offset(routerPosTop.x - 3.dp.toPx(), routerPosTop.y - 12.dp.toPx()), 1.dp.toPx())
        drawLine(RadarNeonGreen, routerPosTop, Offset(routerPosTop.x + 3.dp.toPx(), routerPosTop.y - 12.dp.toPx()), 1.dp.toPx())

        // Draw Router label
        drawContext.canvas.nativeCanvas.drawText(
            "রাউটার",
            routerPosTop.x,
            routerPosTop.y - 16.dp.toPx(),
            android.graphics.Paint().apply {
                color = RadarNeonGreen.toArgb()
                textSize = 9.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )

        // Draw User position ('আপনি' pin)
        // map normalized ViewModel coordinates (0.2, 0.3) to isometric space (-0.2, 0.2)
        val mappedRx = (userX - 0.5f) * 0.8f
        val mappedRy = (userY - 0.5f) * 0.8f
        val userPos = toIso(mappedRx, mappedRy)
        val userPosTop = toIso(mappedRx, mappedRy, 0.18f)

        // Draw connection beam line (Green Neon Beam)
        val pathProgressOffset = Offset(
            routerPosTop.x + (userPosTop.x - routerPosTop.x) * pulseProg,
            routerPosTop.y + (userPosTop.y - routerPosTop.y) * pulseProg
        )
        drawLine(
            color = pulseColor.copy(alpha = 0.12f),
            start = routerPosTop,
            end = userPosTop,
            strokeWidth = 3.dp.toPx()
        )
        drawLine(
            color = pulseColor,
            start = routerPosTop,
            end = userPosTop,
            strokeWidth = 1.dp.toPx()
        )
        // Pulse bullet
        drawCircle(
            color = pulseColor,
            radius = 3.dp.toPx(),
            center = pathProgressOffset
        )

        // User Anchor Base
        drawCircle(
            color = RadarCyan.copy(alpha = 0.2f),
            radius = 8.dp.toPx(),
            center = userPos
        )
        drawLine(RadarCyan, userPos, userPosTop, 1.5.dp.toPx())
        
        // Pin head
        drawCircle(
            color = RadarCyan,
            radius = 4.5.dp.toPx(),
            center = userPosTop
        )
        drawCircle(
            color = Color.White,
            radius = 1.5.dp.toPx(),
            center = userPosTop
        )

        // User Label
        drawContext.canvas.nativeCanvas.drawText(
            "আপনি",
            userPosTop.x,
            userPosTop.y - 10.dp.toPx(),
            android.graphics.Paint().apply {
                color = RadarCyan.toArgb()
                textSize = 9.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )
    }
}

// ---------------------------------------------------------
// 3D ISOMETRIC TOWN/STREET CANVAS
// ---------------------------------------------------------
@Composable
fun IsometricStreetCanvas(
    userX: Float,
    userY: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "StreetAnims")
    val flowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Flow"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f + 5.dp.toPx()

        fun toIso(rx: Float, ry: Float, rz: Float = 0f): Offset {
            val scale = size.minDimension * 0.44f
            val xScreen = cx + (rx - ry) * cos(Math.toRadians(30.0)).toFloat() * scale
            val yScreen = cy + (rx + ry) * sin(Math.toRadians(30.0)).toFloat() * scale - rz * scale
            return Offset(xScreen, yScreen)
        }

        // 1. Draw Ground
        val gStart = toIso(-0.7f, -0.7f)
        val gLeft = toIso(0.7f, -0.7f)
        val gRight = toIso(-0.7f, 0.7f)
        val gFront = toIso(0.7f, 0.7f)

        val groundPath = Path().apply {
            moveTo(gStart.x, gStart.y)
            lineTo(gLeft.x, gLeft.y)
            lineTo(gFront.x, gFront.y)
            lineTo(gRight.x, gRight.y)
            close()
        }
        drawPath(
            path = groundPath,
            color = Color(0xFF0C101A)
        )

        // 2. Draw 3D Roads (isometric path)
        // Path starts at User (-0.3, 0.3) -> junction (-0.3, -0.2) -> Router (0.4, -0.2)
        val roadWidth = 14.dp.toPx()
        val corner1 = toIso(-0.3f, 0.35f)
        val corner2 = toIso(-0.3f, -0.2f)
        val corner3 = toIso(0.45f, -0.2f)

        // Draw road lines
        drawLine(Color(0xFF1E2638), corner1, corner2, roadWidth)
        drawLine(Color(0xFF1E2638), corner2, corner3, roadWidth)

        // Road middle dashed line
        drawLine(Color.White.copy(alpha = 0.2f), corner1, corner2, 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f)))
        drawLine(Color.White.copy(alpha = 0.2f), corner2, corner3, 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f)))

        // 3. Draw Isometric Blocks / Obstacles (Houses)
        fun drawIsoBlock(rx: Float, ry: Float, sx: Float, sy: Float, sz: Float, color: Color) {
            val b0 = toIso(rx, ry)
            val b1 = toIso(rx + sx, ry)
            val b2 = toIso(rx + sx, ry + sy)
            val b3 = toIso(rx, ry + sy)

            val t0 = toIso(rx, ry, sz)
            val t1 = toIso(rx + sx, ry, sz)
            val t2 = toIso(rx + sx, ry + sy, sz)
            val t3 = toIso(rx, ry + sy, sz)

            // Draw Base
            val bp = Path().apply {
                moveTo(b0.x, b0.y)
                lineTo(b1.x, b1.y)
                lineTo(b2.x, b2.y)
                lineTo(b3.x, b3.y)
                close()
            }
            drawPath(bp, color.copy(alpha = 0.2f))

            // Draw left side
            val leftSide = Path().apply {
                moveTo(b0.x, b0.y)
                lineTo(t0.x, t0.y)
                lineTo(t3.x, t3.y)
                lineTo(b3.x, b3.y)
                close()
            }
            drawPath(leftSide, color.copy(alpha = 0.35f))

            // Draw right side
            val rightSide = Path().apply {
                moveTo(b3.x, b3.y)
                lineTo(t3.x, t3.y)
                lineTo(t2.x, t2.y)
                lineTo(b2.x, b2.y)
                close()
            }
            drawPath(rightSide, color.copy(alpha = 0.45f))

            // Draw Top
            val topSide = Path().apply {
                moveTo(t0.x, t0.y)
                lineTo(t1.x, t1.y)
                lineTo(t2.x, t2.y)
                lineTo(t3.x, t3.y)
                close()
            }
            drawPath(topSide, color.copy(alpha = 0.6f))

            // Draw Outlines
            drawLine(Color.White.copy(alpha = 0.15f), t0, t1, 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.15f), t1, t2, 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.15f), t2, t3, 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.15f), t3, t0, 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.12f), b0, t0, 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.12f), b1, t1, 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.12f), b2, t2, 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.12f), b3, t3, 0.5.dp.toPx())
        }

        // Draw obstacles / Houses around the streets
        drawIsoBlock(-0.1f, 0.1f, 0.25f, 0.25f, 0.22f, Color(0xFF323F5E)) // House 1
        drawIsoBlock(-0.6f, -0.1f, 0.2f, 0.2f, 0.18f, Color(0xFF233649)) // House 2
        drawIsoBlock(0.15f, -0.6f, 0.25f, 0.25f, 0.2f, Color(0xFF383842)) // House 3

        // Obstacle wall (Yellow obstacle layer mentioned in legend)
        drawIsoBlock(0.05f, -0.12f, 0.05f, 0.22f, 0.12f, RadarYellow)

        // 4. Draw Neon Pulse Routing Trail
        // Path starts from corner1 -> corner2 -> corner3
        val pathPoints = listOf(corner1, corner2, corner3)
        val totalSegments = 2
        
        val pLine = Path().apply {
            moveTo(corner1.x, corner1.y)
            lineTo(corner2.x, corner2.y)
            lineTo(corner3.x, corner3.y)
        }
        drawPath(
            path = pLine,
            color = RadarNeonGreen,
            style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)
        )

        // Draw sliding neon bullet along the path
        val globalProgress = flowOffset // 0 to 1
        val bulletPos = if (globalProgress < 0.5f) {
            val ratio = globalProgress / 0.5f
            Offset(
                corner1.x + (corner2.x - corner1.x) * ratio,
                corner1.y + (corner2.y - corner1.y) * ratio
            )
        } else {
            val ratio = (globalProgress - 0.5f) / 0.5f
            Offset(
                corner2.x + (corner3.x - corner2.x) * ratio,
                corner2.y + (corner3.y - corner2.y) * ratio
            )
        }

        drawCircle(
            color = RadarNeonGreen,
            radius = 3.5.dp.toPx(),
            center = bulletPos
        )

        // 5. Draw User Anchor Pin at Start AP (-0.3, 0.3)
        val userPos = toIso(-0.3f, 0.3f)
        val userPinTop = toIso(-0.3f, 0.3f, 0.15f)
        drawLine(RadarCyan, userPos, userPinTop, 1.5.dp.toPx())
        drawCircle(RadarCyan, 4.dp.toPx(), userPinTop)
        drawCircle(Color.White, 1.2.dp.toPx(), userPinTop)

        // 6. Draw Router Location AP (0.4, -0.2)
        val routerPos = toIso(0.4f, -0.2f)
        val routerPinTop = toIso(0.4f, -0.2f, 0.15f)
        drawLine(RadarNeonGreen, routerPos, routerPinTop, 1.5.dp.toPx())
        drawCircle(RadarNeonGreen, 4.dp.toPx(), routerPinTop)
        drawCircle(Color.White, 1.2.dp.toPx(), routerPinTop)
    }
}

// ---------------------------------------------------------
// RADAR PANEL (4D SCANNING)
// ---------------------------------------------------------
@Composable
fun SimpleMiniSweepView(
    modifier: Modifier = Modifier,
    statusType: RadarViewModel.StatusType,
    currentRssi: Int,
    distance: Double,
    compassHeading: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweepMini")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Sweep"
    )

    val color = when (statusType) {
        RadarViewModel.StatusType.CALM -> RadarNeonGreen
        RadarViewModel.StatusType.MOTION -> RadarYellow
        RadarViewModel.StatusType.ALERT -> RadarRed
        RadarViewModel.StatusType.WARNING -> RadarCyan
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.9f

        // Draw grid
        drawCircle(color.copy(alpha = 0.15f), radius, style = Stroke(width = 1.dp.toPx()))
        drawCircle(color.copy(alpha = 0.08f), radius * 0.6f, style = Stroke(width = 1.dp.toPx()))

        // Rotate Sweep Line
        val rad = Math.toRadians(angle.toDouble())
        val endX = center.x + radius * cos(rad).toFloat()
        val endY = center.y + radius * sin(rad).toFloat()

        drawLine(color.copy(alpha = 0.6f), center, Offset(endX, endY), 1.5.dp.toPx())

        // Radar reflection target point (representing the Router)
        // Calculated dynamically using the actual compass angle and distance!
        val relativeAngleRad = Math.toRadians((360.0 - compassHeading) % 360.0)
        val maxRange = 15.0
        val fraction = (distance / maxRange).coerceIn(0.15, 0.95)
        val targetRadius = (radius * fraction).toFloat()
        val targetPos = Offset(
            center.x + targetRadius * sin(relativeAngleRad).toFloat(),
            center.y - targetRadius * cos(relativeAngleRad).toFloat()
        )
        val targetIntensity = ((currentRssi + 95f) / 65f).coerceIn(0.1f, 1f)

        // Draw target dot representing router presence
        drawCircle(
            color = color.copy(alpha = targetIntensity),
            radius = 5.dp.toPx(),
            center = targetPos
        )
        drawCircle(
            color = Color.White.copy(alpha = targetIntensity),
            radius = 1.5.dp.toPx(),
            center = targetPos
        )

        // Draw central device node
        drawCircle(Color.White, 3.dp.toPx(), center)
    }
}

// ---------------------------------------------------------
// DEPTH SENSE AI WIREFRAME
// ---------------------------------------------------------
@Composable
fun DepthSenseWireframe(
    humanProb: Float,
    obstacleProb: Float,
    obstacleDist: Double
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Draw vertical scanning gridlines (3D perspective chamber)
        val horizons = 4
        for (i in 0..horizons) {
            val ratio = i.toFloat() / horizons
            val y = h * (0.3f + ratio * 0.7f)
            val widthOffset = w * (0.2f * (1f - ratio))
            drawLine(
                color = Color.White.copy(alpha = 0.06f),
                start = Offset(widthOffset, y),
                end = Offset(w - widthOffset, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Draw chamber diagonals
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, h * 0.2f), Offset(w * 0.35f, h * 0.45f), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.08f), Offset(w, h * 0.2f), Offset(w * 0.65f, h * 0.45f), 1.dp.toPx())

        // 1. Draw HUMAN WIREFRAME (Only show if probability is notable)
        if (humanProb >= 40f) {
            val targetX = w * 0.38f
            val targetY = h * 0.55f
            
            // Draw Red Bounding Box around human
            drawRoundRect(
                color = RadarRed,
                topLeft = Offset(targetX - 16.dp.toPx(), targetY - 35.dp.toPx()),
                size = Size(32.dp.toPx(), 65.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Simplistic humanoid vector outline
            val headCenter = Offset(targetX, targetY - 25.dp.toPx())
            drawCircle(RadarRed, 5.dp.toPx(), headCenter) // Head
            drawLine(RadarRed, Offset(targetX, targetY - 20.dp.toPx()), Offset(targetX, targetY + 10.dp.toPx()), 2.dp.toPx()) // Body
            drawLine(RadarRed, Offset(targetX - 8.dp.toPx(), targetY - 14.dp.toPx()), Offset(targetX + 8.dp.toPx(), targetY - 14.dp.toPx()), 1.5.dp.toPx()) // Arms
            drawLine(RadarRed, Offset(targetX, targetY + 10.dp.toPx()), Offset(targetX - 6.dp.toPx(), targetY + 26.dp.toPx()), 1.5.dp.toPx()) // Left Leg
            drawLine(RadarRed, Offset(targetX, targetY + 10.dp.toPx()), Offset(targetX + 6.dp.toPx(), targetY + 26.dp.toPx()), 1.5.dp.toPx()) // Right Leg

            // Human text indicator
            drawContext.canvas.nativeCanvas.drawText(
                "মানুষ",
                targetX,
                targetY + 41.dp.toPx(),
                android.graphics.Paint().apply {
                    color = RadarRed.toArgb()
                    textSize = 8.sp.toPx()
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
            drawContext.canvas.nativeCanvas.drawText(
                "4.2 m",
                targetX,
                targetY + 50.dp.toPx(),
                android.graphics.Paint().apply {
                    color = Color.White.toArgb()
                    textSize = 8.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }

        // 2. Draw WALL/OBSTACLE WIREFRAME
        val wallX = w * 0.7f
        val wallY = h * 0.58f

        // Yellow Bounding Box representing estimated obstacle location
        drawRoundRect(
            color = RadarYellow,
            topLeft = Offset(wallX - 15.dp.toPx(), wallY - 32.dp.toPx()),
            size = Size(30.dp.toPx(), 58.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )

        // Draw crossed hatch patterns in box
        drawLine(RadarYellow.copy(alpha = 0.3f), Offset(wallX - 15.dp.toPx(), wallY - 32.dp.toPx()), Offset(wallX + 15.dp.toPx(), wallY + 26.dp.toPx()), 0.5.dp.toPx())
        drawLine(RadarYellow.copy(alpha = 0.3f), Offset(wallX + 15.dp.toPx(), wallY - 32.dp.toPx()), Offset(wallX - 15.dp.toPx(), wallY + 26.dp.toPx()), 0.5.dp.toPx())

        // Label
        drawContext.canvas.nativeCanvas.drawText(
            "দেয়াল",
            wallX,
            wallY + 38.dp.toPx(),
            android.graphics.Paint().apply {
                color = RadarYellow.toArgb()
                textSize = 8.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${obstacleDist} m",
            wallX,
            wallY + 47.dp.toPx(),
            android.graphics.Paint().apply {
                color = Color.White.toArgb()
                textSize = 8.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )
    }
}

// ---------------------------------------------------------
// FULLSCREEN INTERACTIVE 3D MAP SCREEN
// ---------------------------------------------------------
@Composable
fun InteractiveMapScreen(
    viewModel: RadarViewModel,
    userPathX: Float,
    userPathY: Float,
    distanceMeters: Double,
    statusType: RadarViewModel.StatusType,
    stateThemeColor: Color
) {
    val scannedNetworks by viewModel.scannedNetworks.collectAsState()
    var showHeatmap by remember { mutableStateOf(true) }
    var showGridOverlay by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepDarkBlue)
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Map Control Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "3D ওয়াই-ফাই রাডার মানচিত্র",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "রিয়েল-টাইম ঘরের সিগন্যাল ওঠানামা ম্যাপিং",
                    color = RadarTextMuted,
                    fontSize = 12.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showHeatmap,
                    onClick = { showHeatmap = !showHeatmap },
                    label = { Text("হিটম্যাপ", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RadarCyan.copy(alpha = 0.2f),
                        selectedLabelColor = RadarCyan
                    )
                )
                FilterChip(
                    selected = showGridOverlay,
                    onClick = { showGridOverlay = !showGridOverlay },
                    label = { Text("গ্রিড", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RadarNeonGreen.copy(alpha = 0.2f),
                        selectedLabelColor = RadarNeonGreen
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Large 3D Map viewport card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            colors = CardDefaults.cardColors(containerColor = RadarCardBg),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Interactive 3D Canvas
                Map3DCanvas(
                    userX = userPathX,
                    userY = userPathY,
                    showHeatmap = showHeatmap,
                    showGrid = showGridOverlay,
                    statusType = statusType
                )

                // Quick overlay HUD Info card
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(RadarNeonGreen))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("রাউটার (AP Lock): Locked", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("দূরত্ব: $distanceMeters m (±1.2m error)", color = RadarTextMuted, fontSize = 11.sp)
                        Text("সংকেত গুণমান: চমৎকার", color = RadarCyan, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Instructions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.2f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "আপনি ঘরের মধ্যে হাঁটলে বা নড়াচড়া করলে আপনার আপেক্ষিক অবস্থান (আপনি Pin) এবং সিগন্যালের তীব্রতার তাপীয় মানচিত্র (Heatmap) লাইভ পরিবর্তিত হবে।",
                    color = RadarTextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Multi AP Orbit Map Header
        Text(
            text = "আশেপাশের ওয়াই-ফাই সিগন্যাল ম্যাপ",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "চারপাশের ওয়াই-ফাই রাউটারগুলোর দূরত্ব ও সিগন্যাল শতাংশ লাইভ গ্রাফ ম্যাপ",
            color = RadarTextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // WiFi Orbit Map Canvas Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(RadarCardBg)
                .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            WifiOrbitMapCanvas(networks = scannedNetworks)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // List of all scanned routers
        Text(
            text = "আশেপাশের রাউটার তালিকা (${scannedNetworks.size}টি সচল)",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (scannedNetworks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RadarCardBg),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "কোনো ওয়াই-ফাই রাউটার স্ক্যান তথ্য নেই।", color = RadarTextMuted, fontSize = 12.sp)
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scannedNetworks.forEach { ap ->
                    val signalPercent = (ap.rssi + 100).coerceIn(0, 100)
                    val distanceEst = Math.pow(10.0, (-32.0 - ap.rssi) / 20.0).coerceIn(0.5, 35.0)
                    val distanceFormatted = String.format("%.1f", distanceEst)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (ap.ssid.isEmpty()) "লুকানো SSID" else ap.ssid,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "BSSID: ${ap.bssid}",
                                    color = RadarTextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "সিগন্যাল: $signalPercent%",
                                    color = if (ap.rssi > -65) RadarNeonGreen else if (ap.rssi > -80) RadarYellow else RadarRed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "~$distanceFormatted m | ${ap.rssi} dBm",
                                    color = RadarCyan,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WifiOrbitMapCanvas(networks: List<RadarViewModel.WifiApInfo>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val maxRadius = Math.min(w, h) / 2f - 16.dp.toPx()

        // Draw orbital grid rings
        val orbits = listOf(0.33f, 0.66f, 1f)
        orbits.forEach { frac ->
            drawCircle(
                color = RadarCyan.copy(alpha = 0.1f),
                radius = maxRadius * frac,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Draw crosshairs
        drawLine(RadarCyan.copy(alpha = 0.08f), Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y))
        drawLine(RadarCyan.copy(alpha = 0.08f), Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius))

        // Center Node representing the user's phone
        drawCircle(Color.White, 5.dp.toPx(), center)
        drawCircle(RadarCyan.copy(alpha = 0.25f), 10.dp.toPx(), center, style = Stroke(width = 1.dp.toPx()))

        // Plot nearby APs
        networks.forEachIndexed { index, ap ->
            val angle = index * (360.0 / Math.max(1, networks.size)) + 30.0
            val angleRad = Math.toRadians(angle)
            
            // Map RSSI to radius (inverted so stronger signals are closer)
            val rssiCoerced = ap.rssi.coerceIn(-100, -30)
            val frac = ((rssiCoerced + 100) / 70f).coerceIn(0.1f, 1.0f)
            val invFrac = (1.0f - (frac * 0.75f)).coerceIn(0.15f, 0.95f)
            
            val radius = maxRadius * invFrac
            val apPos = Offset(
                center.x + radius * cos(angleRad).toFloat(),
                center.y + radius * sin(angleRad).toFloat()
            )

            // Draw router dot
            val color = if (ap.rssi > -65) RadarNeonGreen else if (ap.rssi > -80) RadarYellow else RadarRed
            drawCircle(color, 5.dp.toPx(), apPos)
            drawCircle(color.copy(alpha = 0.2f), 11.dp.toPx(), apPos)

            // Label SSID
            drawContext.canvas.nativeCanvas.drawText(
                if (ap.ssid.isEmpty()) "Wifi AP" else if (ap.ssid.length > 10) ap.ssid.take(10) + ".." else ap.ssid,
                apPos.x,
                apPos.y - 8.dp.toPx(),
                android.graphics.Paint().apply {
                    this.color = android.graphics.Color.WHITE
                    textSize = 8.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }
    }
}

@Composable
fun Map3DCanvas(
    userX: Float,
    userY: Float,
    showHeatmap: Boolean,
    showGrid: Boolean,
    statusType: RadarViewModel.StatusType
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f + 20.dp.toPx()

        fun toIso(rx: Float, ry: Float, rz: Float = 0f): Offset {
            val scale = size.minDimension * 0.52f
            val xScreen = cx + (rx - ry) * cos(Math.toRadians(30.0)).toFloat() * scale
            val yScreen = cy + (rx + ry) * sin(Math.toRadians(30.0)).toFloat() * scale - rz * scale
            return Offset(xScreen, yScreen)
        }

        // Draw Ground limits
        val back = toIso(-0.7f, -0.7f)
        val left = toIso(0.7f, -0.7f)
        val right = toIso(-0.7f, 0.7f)
        val front = toIso(0.7f, 0.7f)

        // Draw heat map ripples if enabled
        if (showHeatmap) {
            // Signal propagation rings centered around router at (-0.4, -0.4)
            val routerScreen = toIso(-0.4f, -0.4f)
            val rings = 4
            for (i in 1..rings) {
                val ringRad = size.minDimension * 0.24f * i
                drawCircle(
                    color = when (statusType) {
                        RadarViewModel.StatusType.CALM -> RadarNeonGreen.copy(alpha = 0.03f)
                        RadarViewModel.StatusType.MOTION -> RadarYellow.copy(alpha = 0.03f)
                        else -> RadarRed.copy(alpha = 0.03f)
                    },
                    radius = ringRad,
                    center = routerScreen,
                    style = Stroke(width = 15.dp.toPx())
                )
            }
        }

        // Draw Grid Lines
        if (showGrid) {
            val divs = 8
            for (i in 0..divs) {
                val r = i.toFloat() / divs
                drawLine(
                    color = Color.White.copy(alpha = 0.04f),
                    start = toIso(-0.7f, -0.7f + r * 1.4f),
                    end = toIso(0.7f, -0.7f + r * 1.4f),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.04f),
                    start = toIso(-0.7f + r * 1.4f, -0.7f),
                    end = toIso(-0.7f + r * 1.4f, 0.7f),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // Drawing surrounding 3D transparent grid blocks representing obstacles (Estimated Walls)
        fun drawWallPlane(rx: Float, ry: Float, rx2: Float, ry2: Float, height: Float, color: Color) {
            val b1 = toIso(rx, ry)
            val b2 = toIso(rx2, ry2)
            val t1 = toIso(rx, ry, height)
            val t2 = toIso(rx2, ry2, height)

            val p = Path().apply {
                moveTo(b1.x, b1.y)
                lineTo(b2.x, b2.y)
                lineTo(t2.x, t2.y)
                lineTo(t1.x, t1.y)
                close()
            }
            drawPath(p, color.copy(alpha = 0.15f))
            drawLine(color, b1, b2, 1.dp.toPx())
            drawLine(color, t1, t2, 1.5.dp.toPx())
            drawLine(color, b1, t1, 0.5.dp.toPx())
            drawLine(color, b2, t2, 0.5.dp.toPx())
        }

        // Draw boundary outer walls
        drawWallPlane(-0.7f, -0.7f, 0.7f, -0.7f, 0.4f, Color.White.copy(alpha = 0.12f))
        drawWallPlane(-0.7f, -0.7f, -0.7f, 0.7f, 0.4f, Color.White.copy(alpha = 0.12f))

        // Draw an inner barrier obstacle (Estimated wall between user and AP)
        drawWallPlane(-0.1f, -0.3f, -0.1f, 0.2f, 0.25f, RadarYellow.copy(alpha = 0.4f))

        // Connections path trail
        val rPos = toIso(-0.4f, -0.4f)
        val uPos = toIso((userX - 0.5f) * 0.9f, (userY - 0.5f) * 0.9f)
        
        drawLine(RadarNeonGreen, rPos, uPos, 1.dp.toPx())

        // Router Base & Node
        drawCircle(RadarNeonGreen, 5.dp.toPx(), rPos)
        drawCircle(Color.White, 1.5.dp.toPx(), rPos)

        // User Anchor Base & Glowing Node
        drawCircle(RadarCyan.copy(alpha = 0.3f), 10.dp.toPx(), uPos)
        drawCircle(RadarCyan, 5.dp.toPx(), uPos)
        drawCircle(Color.White, 2.dp.toPx(), uPos)
    }
}

// ---------------------------------------------------------
// RADAR TAB (FULLSCREEN SWEEPER WITH SYNTH AUDIO PING)
// ---------------------------------------------------------
@Composable
fun ImmersiveRadarScreen(
    viewModel: RadarViewModel,
    statusType: RadarViewModel.StatusType,
    currentRssi: Int,
    distanceMeters: Double,
    stateThemeColor: Color
) {
    val compassHeading by viewModel.compassHeading.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "RadarImmersive")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Sweeping"
    )

    // Trigger Synth Beep Audio periodically when sweep arm passes the lock target (around 300 degrees)
    val sweepInt = angle.toInt()
    LaunchedEffect(sweepInt) {
        if (sweepInt in 298..302) {
            // High frequency crisp ping matching sweeping radar
            playSonarBeep(1040.0, 150)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "লাইভ রাডার (4D সিগন্যাল ভিউ)",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "৩৬০° সিগন্যাল প্রতিফলন ও গতিবিধি ডিটেকশন",
                color = RadarTextMuted,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // Massive Immersive Radar sweeps view
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(RadarCardBg)
                .border(BorderStroke(2.dp, stateThemeColor.copy(alpha = 0.3f)), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            RadarImmersiveSweep(
                angle = angle,
                statusType = statusType,
                rssi = currentRssi,
                dist = distanceMeters,
                compassHeading = compassHeading
            )
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // Warning or Alert prompt inside radar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = RadarCardBg),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, stateThemeColor.copy(alpha = 0.15f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = RadarNeonGreen)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("অনলাইন সোনার পিং (Audio Sound)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("রাডারের হাতটি প্রতি ঘূর্ণনে লক সোর্সের ওপর দিয়ে গেলে পিং ধ্বনি হবে।", color = RadarTextMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun RadarImmersiveSweep(
    angle: Float,
    statusType: RadarViewModel.StatusType,
    rssi: Int,
    dist: Double,
    compassHeading: Float
) {
    val color = when (statusType) {
        RadarViewModel.StatusType.CALM -> RadarNeonGreen
        RadarViewModel.StatusType.MOTION -> RadarYellow
        RadarViewModel.StatusType.ALERT -> RadarRed
        RadarViewModel.StatusType.WARNING -> RadarCyan
    }

    Canvas(modifier = Modifier.fillMaxSize(0.92f)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2 * 0.95f

        // Draw radial grid
        drawCircle(color.copy(alpha = 0.2f), radius, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(color.copy(alpha = 0.12f), radius * 0.75f, style = Stroke(width = 1.dp.toPx()))
        drawCircle(color.copy(alpha = 0.08f), radius * 0.5f, style = Stroke(width = 1.dp.toPx()))
        drawCircle(color.copy(alpha = 0.05f), radius * 0.25f, style = Stroke(width = 1.dp.toPx()))

        // Concentric degrees lines
        for (i in 0..7) {
            val dAngleRad = Math.toRadians((i * 45).toDouble())
            val endPoint = Offset(
                center.x + radius * cos(dAngleRad).toFloat(),
                center.y + radius * sin(dAngleRad).toFloat()
            )
            drawLine(
                color = color.copy(alpha = 0.06f),
                start = center,
                end = endPoint,
                strokeWidth = 1.dp.toPx()
            )
        }

        // Sweeping beam path
        val sweepAngleRad = Math.toRadians(angle.toDouble())
        val beamEnd = Offset(
            center.x + radius * cos(sweepAngleRad).toFloat(),
            center.y + radius * sin(sweepAngleRad).toFloat()
        )

        // Draw rotating line
        drawLine(
            color = color.copy(alpha = 0.8f),
            start = center,
            end = beamEnd,
            strokeWidth = 2.dp.toPx()
        )

        // Draw fading trail arc
        drawArc(
            color = color.copy(alpha = 0.12f),
            startAngle = angle - 35f,
            sweepAngle = 35f,
            useCenter = true,
            size = Size(radius * 2, radius * 2),
            topLeft = Offset(center.x - radius, center.y - radius)
        )

        // Draw Lock point coordinate target (Calculated dynamically using compass heading!)
        val targetRad = Math.toRadians((360.0 - compassHeading) % 360.0)
        val maxRange = 15.0
        val fraction = (dist / maxRange).coerceIn(0.15, 0.95)
        val targetRadius = radius * fraction.toFloat()
        val targetPos = Offset(
            center.x + targetRadius * sin(targetRad).toFloat(),
            center.y - targetRadius * cos(targetRad).toFloat()
        )

        // Target pulsing shell
        drawCircle(
            color = color.copy(alpha = 0.15f),
            radius = 12.dp.toPx(),
            center = targetPos
        )
        drawCircle(
            color = color,
            radius = 6.dp.toPx(),
            center = targetPos
        )
        drawCircle(
            color = Color.White,
            radius = 2.dp.toPx(),
            center = targetPos
        )

        // Target Tag bubble label
        drawContext.canvas.nativeCanvas.drawText(
            "$rssi dBm",
            targetPos.x,
            targetPos.y - 12.dp.toPx(),
            android.graphics.Paint().apply {
                setColor(color.toArgb())
                textSize = 9.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${dist} m",
            targetPos.x,
            targetPos.y + 19.dp.toPx(),
            android.graphics.Paint().apply {
                setColor(Color.White.toArgb())
                textSize = 8.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )

        // Core central phone icon representing user receiver location
        drawCircle(Color.White, 5.dp.toPx(), center)
    }
}

// ---------------------------------------------------------
// EVENT TIMELINE & GEMINI AI ANALYSIS PANEL (HISTORY)
// ---------------------------------------------------------
@Composable
fun HistoryScreen(
    viewModel: RadarViewModel,
    loggedEvents: List<RadarEvent>,
    isAnalyzingWithAi: Boolean,
    aiAnalysisResult: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text(
            text = "ডিটেকশন ইতিহাস ও এআই বিশ্লেষণ",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "ঘরের সংকেত বিঘ্ন রেকর্ডের তালিকা এবং জেমিনি ডায়াগনস্টিক",
            color = RadarTextMuted,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Gemini AI Section Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = RadarCardBg),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RadarCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("জেমিনি এআই ডায়াগনস্টিক", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.analyzeLogsWithGemini() },
                        colors = ButtonDefaults.buttonColors(containerColor = RadarCyan.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isAnalyzingWithAi,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("ai_history_analyze_button")
                    ) {
                        if (isAnalyzingWithAi) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), color = RadarCyan, strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("বিশ্লেষণ হচ্ছে...", color = RadarCyan, fontSize = 11.sp)
                        } else {
                            Icon(Icons.Default.Analytics, contentDescription = null, tint = RadarCyan, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("রিপোর্ট তৈরি", color = RadarCyan, fontSize = 11.sp)
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
                        Text("Gemini AI আপনার সংকেত ইতিহাস মূল্যায়ন করছে...", color = RadarTextMuted, fontSize = 12.sp)
                    }
                } else if (aiAnalysisResult != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .padding(10.dp)
                    ) {
                        LazyColumn {
                            item {
                                Text(
                                    text = aiAnalysisResult,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = RadarTextMuted, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ইতিহাস লগের ওপর ভিত্তি করে নিখুঁত ঘরের সুরক্ষার নির্দেশনা ও টিপস পেতে 'রিপোর্ট তৈরি' করুন।", color = RadarTextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Log Timeline Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "সংকেত পরিবর্তন লগ (${loggedEvents.size})",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            if (loggedEvents.isNotEmpty()) {
                TextButton(
                    onClick = { viewModel.clearHistory() },
                    modifier = Modifier.testTag("clear_history_btn")
                ) {
                    Text("মুছে ফেলুন", color = RadarRed, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // History list items
        if (loggedEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.HistoryToggleOff, contentDescription = null, tint = RadarTextMuted, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("কোনো ইতিহাস বা সংকেত বিঘ্ন রেকর্ড পাওয়া যায়নি", color = RadarTextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(loggedEvents, key = { it.id }) { event ->
                    RadarEventLogItem(event = event)
                }
            }
        }
    }
}

// ---------------------------------------------------------
// CONFIGURATIONS & AP SECTOR LOCK (SETTINGS)
// ---------------------------------------------------------
@Composable
fun SettingsScreen(
    viewModel: RadarViewModel,
    isSimulationMode: Boolean,
    trackedSsid: String,
    trackedBssid: String,
    scannedNetworks: List<RadarViewModel.WifiApInfo>
) {
    var customRefRssi by remember { mutableStateOf("-40") }
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "ওয়াই-ফাই রাডার সেটিংস",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "সেন্সিং ক্যালিব্রেশন এবং রাউটার ট্র্যাকিং নির্বাচন",
                    color = RadarTextMuted,
                    fontSize = 12.sp
                )
            }
        }

        // Simulator Preset Triggering (Offline Demo controls requested)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, RadarYellow.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("অনলাইন / অফলাইন সিমুলেটর কন্ট্রোল", color = RadarYellow, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("ঘরে বিভিন্ন মানুষের অনুপ্রবেশ বা গতিবিধির পরিবেশ অনুকরণ করতে নিচের যেকোনো মোড ট্রিগার করুন:", color = RadarTextMuted, fontSize = 11.sp, lineHeight = 16.sp)
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { viewModel.triggerSimulationPreset(RadarViewModel.SimulationPreset.CALM) },
                            modifier = Modifier.weight(1f).testTag("settings_preset_calm"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("শান্ত (Calm)", color = Color.White, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.triggerSimulationPreset(RadarViewModel.SimulationPreset.SLOW_WALK) },
                            modifier = Modifier.weight(1f).testTag("settings_preset_slow"),
                            colors = ButtonDefaults.buttonColors(containerColor = RadarYellow.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("ধীর হাঁটা", color = RadarYellow, fontSize = 11.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { viewModel.triggerSimulationPreset(RadarViewModel.SimulationPreset.FAST_INTRUDER) },
                            modifier = Modifier.weight(1f).testTag("settings_preset_fast"),
                            colors = ButtonDefaults.buttonColors(containerColor = RadarRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("অনুপ্রবেশ", color = RadarRed, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.triggerSimulationPreset(RadarViewModel.SimulationPreset.CHAOTIC) },
                            modifier = Modifier.weight(1f).testTag("settings_preset_chaos"),
                            colors = ButtonDefaults.buttonColors(containerColor = RadarCyan.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("একাধিক মানুষ", color = RadarCyan, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Calibration Options
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ক্যালিব্রেশন রেফারেন্স", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("১ মিটার দূরত্বে সিগন্যাল রেফারেন্স মান (Typically -40 to -45 dBm):", color = RadarTextMuted, fontSize = 11.sp)
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customRefRssi,
                            onValueChange = { customRefRssi = it },
                            placeholder = { Text("-40") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RadarNeonGreen,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedLabelColor = RadarNeonGreen,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f).testTag("ref_rssi_input")
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = {
                                val parsed = customRefRssi.toIntOrNull()
                                if (parsed != null && parsed < 0 && parsed > -100) {
                                    viewModel.setOneMeterRssi(parsed)
                                    Toast.makeText(context, "১ মিটার বেসলাইন আপডেট সম্পন্ন", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "ভুল ইনপুট! -৩০ থেকে -৯০ দিন", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RadarNeonGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("ref_rssi_save_button")
                        ) {
                            Text("সংরক্ষণ", color = DeepDarkBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Real Network list to Lock Transmitter
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("সংযুক্ত ওয়াই-ফাই ও লক সোর্স", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .padding(10.dp)
                    ) {
                        Text("লকড SSID: $trackedSsid", color = RadarNeonGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("BSSID: $trackedBssid", color = RadarTextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("আশেপাশের স্ক্যানকৃত ওয়াই-ফাই নেটওয়ার্ক:", color = RadarTextMuted, fontSize = 11.sp)

                    if (isSimulationMode) {
                        Text("💡 আপনি বাস্তব ওয়াই-ফাই সেন্সর মোড চালু করলে আশেপাশের সব নেটওয়ার্কের স্ক্যান তালিকা এখানে দেখতে পাবেন এবং সেটিতে লক করতে পারবেন।", color = RadarCyan, fontSize = 11.sp, lineHeight = 16.sp)
                    } else if (scannedNetworks.isEmpty()) {
                        Text("নেটওয়ার্ক স্ক্যান করা হচ্ছে...", color = RadarTextMuted, fontSize = 11.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            scannedNetworks.take(6).forEach { ap ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .clickable {
                                            viewModel.selectNetwork(ap.ssid, ap.bssid)
                                            Toast.makeText(context, "${ap.ssid} সোর্স লক করা হয়েছে", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(ap.ssid, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(ap.bssid, color = RadarTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = RadarNeonGreen.copy(alpha = 0.15f))
                                    ) {
                                        Text("${ap.rssi} dBm", color = RadarNeonGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
        val w = size.width
        val h = size.height

        if (history.size < 2) return@Canvas

        val maxRssi = -30f
        val minRssi = -95f
        val range = maxRssi - minRssi

        val path = Path()
        val stepX = w / (history.size - 1)

        history.forEachIndexed { index, rssi ->
            val x = index * stepX
            val normalizedY = ((rssi.toFloat() - minRssi) / range).coerceIn(0f, 1f)
            val y = h - (normalizedY * h)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)
        )

        val fillPath = Path().apply {
            addPath(path)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = fillPath,
            color = color.copy(alpha = 0.08f)
        )
    }
}

@Composable
fun RadarEventLogItem(event: RadarEvent) {
    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = sdf.format(Date(event.timestamp))

    val typeColor = when {
        event.status.contains("সতর্ক") || event.status.contains("Alert") || event.status.contains("বিপদ") -> RadarRed
        event.status.contains("গতিবিধি") || event.status.contains("Motion") || event.status.contains("অনুপ্রবেশ") -> RadarYellow
        event.status.contains("দুর্বল") -> RadarCyan
        else -> RadarNeonGreen
    }

    val typeLabel = when {
        event.status.contains("সতর্ক") || event.status.contains("Alert") || event.status.contains("বিপদ") -> "সতর্কতা"
        event.status.contains("গতিবিধি") || event.status.contains("Motion") || event.status.contains("অনুপ্রবেশ") -> "গতিবিধি"
        event.status.contains("দুর্বল") -> "সিস্টেম"
        else -> "শান্ত"
    }

    val estimatedDist = remember(event.rssi, event.baseRssi) {
        val exponent = 2.8
        val ratio = (event.baseRssi.toDouble() - event.rssi.toDouble()) / (10.0 * exponent)
        val calculated = Math.pow(10.0, ratio)
        String.format(Locale.US, "%.1f", calculated)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(typeColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = typeLabel,
                        color = typeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = timeStr,
                        color = RadarTextMuted,
                        fontSize = 10.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.inference,
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Text(
                    text = "সিগন্যাল: ${event.rssi} dBm | ড্রপ: ${event.dropMagnitude} dB | দূরত্ব: ${estimatedDist}m",
                    color = RadarTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------
// BLUETOOTH RADAR SCREEN
// ---------------------------------------------------------
@Composable
fun BluetoothDeviceRadarScreen(
    viewModel: RadarViewModel,
    isScanning: Boolean,
    scanStatus: String,
    devices: List<RadarViewModel.BluetoothDeviceInfo>
) {
    val context = LocalContext.current
    
    // Auto start Bluetooth Scan if permissions exist and Bluetooth is enabled on entering tab
    LaunchedEffect(Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter != null && adapter.isEnabled) {
            viewModel.startBluetoothScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepDarkBlue)
            .padding(16.dp)
    ) {
        // Tab Header
        Text(
            text = "আশেপাশের ডিভাইস ট্র্যাকার",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "আশেপাশের সচল ব্লুটুথ ডিভাইস, ফোন এবং স্মার্ট ব্যান্ড রিয়েল-টাইমে স্ক্যান করুন",
            color = RadarTextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = RadarCardBg),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ব্লুটুথ স্ট্যাটাস:",
                        color = RadarTextMuted,
                        fontSize = 11.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isScanning) RadarNeonGreen else RadarRed)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = scanStatus,
                            color = if (isScanning) RadarNeonGreen else Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = {
                        if (isScanning) {
                            viewModel.stopBluetoothScan()
                        } else {
                            viewModel.startBluetoothScan()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) RadarRed.copy(alpha = 0.2f) else RadarNeonGreen.copy(alpha = 0.2f),
                        contentColor = if (isScanning) RadarRed else RadarNeonGreen
                    ),
                    border = BorderStroke(1.dp, if (isScanning) RadarRed else RadarNeonGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = if (isScanning) "স্ক্যান বন্ধ করুন" else "স্ক্যান শুরু করুন", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Bluetooth hardware / Permission alert (if bluetooth is off)
        val adapter = remember { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter }
        if (adapter == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = RadarRed.copy(alpha = 0.1f)),
                border = BorderStroke(0.5.dp, RadarRed.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "দুঃখিত, এই ডিভাইসে ব্লুটুথ হার্ডওয়্যার পাওয়া যায়নি।",
                    color = RadarRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else if (!adapter.isEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = RadarYellow.copy(alpha = 0.12f)),
                border = BorderStroke(0.5.dp, RadarYellow.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "⚠️ ব্লুটুথ বন্ধ আছে!",
                        color = RadarYellow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "আশেপাশের সক্রিয় ফোন, ব্লুটুথ এবং স্মার্ট ডিভাইসগুলির সিগন্যাল ট্র্যাক করার জন্য আপনার ফোনের ব্লুটুথ চালু করা আবশ্যক। অনুগ্রহ করে কুইক প্যানেল বা সেটিংস থেকে ব্লুটুথ অন করুন।",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 3D/2D Radar Map Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.1f)
                .clip(RoundedCornerShape(12.dp))
                .background(RadarCardBg)
                .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            BluetoothRadarMapCanvas(devices = devices, isScanning = isScanning)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device List Header
        Text(
            text = "শনাক্তকৃত ডিভাইসের তালিকা (${devices.size}টি)",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Device List
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RadarCardBg.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Bluetooth,
                        contentDescription = "No Devices",
                        tint = RadarTextMuted,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isScanning) "কোনো সক্রিয় ডিভাইস পাওয়া যায়নি। স্ক্যানিং চলছে..." else "ডিভাইস খুঁজতে উপরের 'স্ক্যান শুরু করুন' চাপুন",
                        color = RadarTextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.9f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { dev ->
                    BluetoothDeviceRow(device = dev)
                }
            }
        }
    }
}

@Composable
fun BluetoothRadarMapCanvas(
    devices: List<RadarViewModel.BluetoothDeviceInfo>,
    isScanning: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("bluetooth_radar_canvas")
    ) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val maxRadius = Math.min(w, h) / 2f - 16.dp.toPx()

        // 1. Draw Grid Rings
        val rings = listOf(0.25f, 0.5f, 0.75f, 1f)
        rings.forEach { rFrac ->
            drawCircle(
                color = RadarNeonGreen.copy(alpha = 0.1f),
                radius = maxRadius * rFrac,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Draw Crosshairs
        drawLine(
            color = RadarNeonGreen.copy(alpha = 0.15f),
            start = Offset(center.x - maxRadius, center.y),
            end = Offset(center.x + maxRadius, center.y),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = RadarNeonGreen.copy(alpha = 0.15f),
            start = Offset(center.x, center.y - maxRadius),
            end = Offset(center.x, center.y + maxRadius),
            strokeWidth = 1.dp.toPx()
        )

        // Draw Center Node (Self)
        drawCircle(
            color = RadarNeonGreen,
            radius = 6.dp.toPx(),
            center = center
        )
        drawCircle(
            color = RadarNeonGreen.copy(alpha = 0.25f),
            radius = 12.dp.toPx(),
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // 2. Draw sweep line
        if (isScanning) {
            val angleRad = Math.toRadians(rotation.toDouble() - 90.0)
            val lineEnd = Offset(
                center.x + maxRadius * cos(angleRad).toFloat(),
                center.y + maxRadius * sin(angleRad).toFloat()
            )
            drawLine(
                color = RadarNeonGreen.copy(alpha = 0.4f),
                start = center,
                end = lineEnd,
                strokeWidth = 2.dp.toPx()
            )
        }

        // 3. Draw Scanned Devices around Center
        devices.forEachIndexed { idx, dev ->
            // Distribute devices around radar circle based on hash or index
            val angleOffset = (idx * (360.0 / Math.max(1, devices.size))) + 15.0
            val angleRad = Math.toRadians(angleOffset)
            
            // Map distance to radius fraction
            val maxRange = 15.0
            val fraction = (dev.distanceMeters / maxRange).coerceIn(0.15, 0.95)
            val deviceRadius = (maxRadius * fraction).toFloat()
            
            val devicePos = Offset(
                center.x + deviceRadius * cos(angleRad).toFloat(),
                center.y + deviceRadius * sin(angleRad).toFloat()
            )

            // Glow based on signal strength
            val glowAlpha = (dev.rssi + 100) / 100f
            val color = when (dev.deviceType) {
                "Phone" -> RadarNeonGreen
                "Smartwatch" -> RadarYellow
                "Audio Device" -> RadarCyan
                else -> RadarTextMuted
            }

            drawCircle(
                color = color.copy(alpha = 0.25f * glowAlpha.coerceIn(0.2f, 1f)),
                radius = 12.dp.toPx(),
                center = devicePos
            )
            drawCircle(
                color = color,
                radius = 5.dp.toPx(),
                center = devicePos
            )
        }
    }
}

@Composable
fun BluetoothDeviceRow(device: RadarViewModel.BluetoothDeviceInfo) {
    val typeColor = when (device.deviceType) {
        "Phone" -> RadarNeonGreen
        "Smartwatch" -> RadarYellow
        "Audio Device" -> RadarCyan
        else -> RadarTextMuted
    }

    val icon = when (device.deviceType) {
        "Phone" -> Icons.Filled.Smartphone
        "Smartwatch" -> Icons.Filled.DeveloperBoard
        "Audio Device" -> Icons.Filled.Bluetooth
        else -> Icons.Filled.Bluetooth
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bt_device_${device.macAddress}"),
        colors = CardDefaults.cardColors(containerColor = RadarCardBg),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(typeColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = device.deviceType,
                    tint = typeColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.deviceType,
                        color = typeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "•  MAC: ${device.macAddress}",
                        color = RadarTextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            // Distance & RSSI
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${device.distanceMeters}m দূরে",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${device.rssi} dBm",
                    color = if (device.rssi > -70) RadarNeonGreen else if (device.rssi > -85) RadarYellow else RadarRed,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ActiveSonarScreen(
    viewModel: com.example.ui.RadarViewModel,
    isActive: Boolean,
    status: String,
    detectedDistance: Double,
    echoIntensity: Float,
    targetAngle: Float,
    compassHeading: Float
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepDarkBlue)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "অ্যাক্টিভ সোনার (Acoustic Echo)",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "High-frequency ultrasonic wave tracking",
                    color = RadarTextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Big visual canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(RadarCardBg)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize(0.9f)) {
                val center = Offset(size.width / 2, size.height / 2)
                val maxRadius = size.minDimension / 2

                // Draw emitting rings if active
                if (isActive) {
                    val time = System.currentTimeMillis() % 1500
                    val fraction = time / 1500f
                    drawCircle(
                        color = RadarCyan.copy(alpha = (1f - fraction) * 0.5f),
                        radius = maxRadius * fraction,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                    
                    val fraction2 = (time + 750) % 1500 / 1500f
                    drawCircle(
                        color = RadarCyan.copy(alpha = (1f - fraction2) * 0.3f),
                        radius = maxRadius * fraction2,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Grid rings
                drawCircle(Color.White.copy(alpha = 0.05f), maxRadius, center, style = Stroke(1.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.05f), maxRadius * 0.66f, center, style = Stroke(1.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.05f), maxRadius * 0.33f, center, style = Stroke(1.dp.toPx()))

                // Draw User
                drawCircle(RadarNeonGreen, 6.dp.toPx(), center)
                drawCircle(RadarNeonGreen.copy(alpha = 0.2f), 12.dp.toPx(), center)

                // Draw echo target if intensity > 0
                if (isActive && echoIntensity > 0f) {
                    val relativeAngleRad = Math.toRadians((360.0 - (targetAngle - compassHeading)) % 360.0)
                    val distFraction = (detectedDistance / 10.0).coerceIn(0.1, 0.95)
                    val r = (maxRadius * distFraction).toFloat()
                    
                    val echoPos = Offset(
                        center.x + (r * Math.sin(relativeAngleRad)).toFloat(),
                        center.y - (r * Math.cos(relativeAngleRad)).toFloat()
                    )

                    val color = if (echoIntensity > 0.6f) RadarRed else if (echoIntensity > 0.3f) RadarYellow else RadarNeonGreen
                    
                    // Arc shadow for echo
                    drawArc(
                        color = color.copy(alpha = echoIntensity * 0.5f),
                        startAngle = (targetAngle - compassHeading - 90f - 15f).toFloat(),
                        sweepAngle = 30f,
                        useCenter = true,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(r * 2f, r * 2f)
                    )
                    
                    drawCircle(color, (4 + 6 * echoIntensity).dp.toPx(), echoPos)
                    drawCircle(color.copy(alpha = 0.3f), (10 + 10 * echoIntensity).dp.toPx(), echoPos)

                    // Target line
                    drawLine(
                        color = color.copy(alpha = echoIntensity * 0.4f),
                        start = center,
                        end = echoPos,
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            
            // HUD Text overlay
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = status,
                    color = if (isActive) RadarNeonGreen else RadarTextMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isActive && echoIntensity > 0f) {
                    Text(
                        text = "দূরত্ব: $detectedDistance m | তীব্রতা: ${(echoIntensity * 100).toInt()}%",
                        color = RadarCyan,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Control Button
        Button(
            onClick = { viewModel.toggleSonar() },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive) RadarRed else RadarCyan
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.Stop else Icons.Default.GraphicEq,
                contentDescription = null,
                tint = if (isActive) Color.White else DeepDarkBlue
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isActive) "সোনার বন্ধ করুন" else "সোনার চালু করুন",
                color = if (isActive) Color.White else DeepDarkBlue,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
