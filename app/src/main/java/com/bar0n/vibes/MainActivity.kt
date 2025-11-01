package com.bar0n.vibes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bar0n.vibes.ui.theme.VibesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val sharedViewModel: SharedViewModel by viewModels()
    private val vibrationViewModel: VibrationViewModel by viewModels()
    private val soundViewModel: SoundViewModel by viewModels()
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var audioReader: AudioReader? = null

    private var contentToSave: String = ""
    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(contentToSave.toByteArray())
                    Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                val (x, y, z) = event.values
                vibrationViewModel.onVibrationDataChanged(x, y, z, sharedViewModel.getElapsedTime())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        enableEdgeToEdge()
        setContent {
            VibesTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(sharedViewModel, vibrationViewModel, soundViewModel, onListenStateChanged = { listening ->
                            if (listening) {
                                if (ActivityCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    ActivityCompat.requestPermissions(
                                        this@MainActivity,
                                        arrayOf(Manifest.permission.RECORD_AUDIO),
                                        0
                                    )
                                } else {
                                    startListening()
                                }
                            } else {
                                stopListening()
                            }
                        }, onSaveData = { navController.navigate("results") })
                    }
                    composable("results") {
                        ResultsScreen(
                            vibrationViewModel = vibrationViewModel,
                            soundViewModel = soundViewModel,
                            onSaveVibrationCsv = { csv -> saveCsvToFile("vibration_data.csv", csv) },
                            onSaveSoundCsv = { csv -> saveCsvToFile("sound_data.csv", csv) },
                            onNavigateUp = { navController.navigateUp() }
                        )
                    }
                }
            }
        }
    }

    private fun startListening() {
        sharedViewModel.startListening()
        vibrationViewModel.startListening()
        sensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

        soundViewModel.startListening()
        audioReader = AudioReader( { amp -> soundViewModel.onAudioDataChanged(amp, sharedViewModel.getElapsedTime()) }, lifecycleScope)
        audioReader?.start()
    }

    private fun stopListening() {
        audioReader?.stop()
        sensorManager.unregisterListener(sensorEventListener)
        sharedViewModel.stopListening()
    }

    private fun saveCsvToFile(fileName: String, csvData: String) {
        contentToSave = csvData
        saveFileLauncher.launch(fileName)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    private class AudioReader(
        private val onAmplitudeListener: (Int) -> Unit,
        private val scope: CoroutineScope
    ) {
        private var job: Job? = null
        private val sampleRate = 44100
        private val channelConfig = AudioFormat.CHANNEL_IN_MONO
        private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        @SuppressLint("MissingPermission")
        fun start() {
            job = scope.launch(Dispatchers.IO) {
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                val buffer = ShortArray(bufferSize)
                audioRecord.startRecording()

                while (isActive) {
                    val readSize = audioRecord.read(buffer, 0, buffer.size)
                    if (readSize > 0) {
                        val maxAmplitude = buffer.maxOfOrNull { abs(it.toInt()) } ?: 0
                        onAmplitudeListener(maxAmplitude)
                    }
                }
                audioRecord.stop()
                audioRecord.release()
            }
        }

        fun stop() {
            job?.cancel()
        }
    }
}

fun <T> createCsv(data: List<T>): String {
    val header = "timestamp,value\n"
    return header + data.joinToString("\n") { point ->
        when (point) {
            is VibrationDataPoint -> "${point.timestamp},${point.value}"
            is SoundDataPoint -> "${point.timestamp},${point.value}"
            else -> ""
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    sharedViewModel: SharedViewModel,
    vibrationViewModel: VibrationViewModel,
    soundViewModel: SoundViewModel,
    onListenStateChanged: (Boolean) -> Unit,
    onSaveData: () -> Unit
) {
    val isListening by sharedViewModel.isListening.collectAsState()
    val recordingDuration by sharedViewModel.recordingDuration.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Vibes") })
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onListenStateChanged(true) }, enabled = !isListening) {
                    Text("Start")
                }
                Button(onClick = { onListenStateChanged(false); onSaveData() }, enabled = isListening) {
                    Text("Stop")
                }
            }
            Text("Recording duration: ${recordingDuration / 1000}s")

            VibrationScreen(vibrationViewModel)
            SoundScreen(soundViewModel)
        }
    }
}

@Composable
fun VibrationScreen(viewModel: VibrationViewModel) {
    val vibrationData by viewModel.vibrationData.collectAsState()
    val currentVibration by viewModel.currentVibration.collectAsState()
    val overallAverage by viewModel.overallVibrationAverage.collectAsState()
    val windowAverage by viewModel.windowVibrationAverage.collectAsState()
    val maxVibration by viewModel.maxVibration.collectAsState()
    val minVibration by viewModel.minVibration.collectAsState()

    val graphData = remember(vibrationData) { vibrationData.map { it.timestamp to it.value } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Vibration", style = MaterialTheme.typography.titleLarge)
            Text(text = "Current: %.2f".format(currentVibration), style = MaterialTheme.typography.bodyLarge)
            Text(text = "Overall Average: %.2f".format(overallAverage), style = MaterialTheme.typography.bodyMedium)
            Text(text = "Window Average: %.2f".format(windowAverage), style = MaterialTheme.typography.bodyMedium)
            Text(text = "Max Value: %.2f".format(maxVibration), style = MaterialTheme.typography.bodyMedium)
            Text(text = "Min Value: %.2f".format(minVibration), style = MaterialTheme.typography.bodyMedium)
            DataGraph(graphData, MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SoundScreen(viewModel: SoundViewModel) {
    val decibelData by viewModel.decibelData.collectAsState()
    val currentDecibel by viewModel.currentDecibel.collectAsState()
    val overallAverage by viewModel.overallDecibelAverage.collectAsState()
    val windowAverage by viewModel.windowDecibelAverage.collectAsState()
    val maxValue by viewModel.maxDecibel.collectAsState()
    val minValue by viewModel.minDecibel.collectAsState()

    val graphData = remember(decibelData) { decibelData.map { it.timestamp to it.value } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Sound (Decibels)", style = MaterialTheme.typography.titleLarge)
            Text(text = "Current: %.2f dB".format(currentDecibel), style = MaterialTheme.typography.bodyLarge)
            Text(text = "Overall Average: %.2f dB".format(overallAverage), style = MaterialTheme.typography.bodyMedium)
            Text(text = "Window Average: %.2f dB".format(windowAverage), style = MaterialTheme.typography.bodyMedium)
            Text(text = "Max Value: %.2f dB".format(maxValue), style = MaterialTheme.typography.bodyMedium)
            Text(text = "Min Value: %.2f dB".format(minValue), style = MaterialTheme.typography.bodyMedium)
            DataGraph(graphData, MaterialTheme.colorScheme.secondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    vibrationViewModel: VibrationViewModel,
    soundViewModel: SoundViewModel,
    onSaveVibrationCsv: (String) -> Unit,
    onSaveSoundCsv: (String) -> Unit,
    onNavigateUp: () -> Unit
) {
    val vibrationData by vibrationViewModel.vibrationData.collectAsState()
    val soundData by soundViewModel.decibelData.collectAsState()

    val vibrationValues = remember(vibrationData) { vibrationData.map { it.value } }
    val soundValues = remember(soundData) { soundData.map { it.value } }

    val vibrationCsv = remember(vibrationData) { createCsv(vibrationData) }
    val soundCsv = remember(soundData) { createCsv(soundData) }

    val vibrationStats = remember(vibrationValues) {
        if (vibrationValues.isNotEmpty()) {
            val sorted = vibrationValues.sorted()
            mapOf(
                "Min" to sorted.first(),
                "Max" to sorted.last(),
                "Mean" to vibrationValues.average().toFloat(),
                "Median" to if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f else sorted[sorted.size / 2]
            )
        } else {
            emptyMap()
        }
    }

    val soundStats = remember(soundValues) {
        if (soundValues.isNotEmpty()) {
            val sorted = soundValues.sorted()
            mapOf(
                "Min" to sorted.first(),
                "Max" to sorted.last(),
                "Mean" to soundValues.average().toFloat(),
                "Median" to if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f else sorted[sorted.size / 2]
            )
        } else {
            emptyMap()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Results") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Vibration Data", style = MaterialTheme.typography.titleLarge)
                    DataGraph(vibrationData.map { it.timestamp to it.value }, MaterialTheme.colorScheme.primary)
                    vibrationStats.forEach { (key, value) ->
                        Text(text = "$key: %.2f".format(value), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { onSaveVibrationCsv(vibrationCsv) }) {
                        Text("Save Vibration as CSV")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Sound Data", style = MaterialTheme.typography.titleLarge)
                    DataGraph(soundData.map { it.timestamp to it.value }, MaterialTheme.colorScheme.secondary)
                    soundStats.forEach { (key, value) ->
                        Text(text = "$key: %.2f dB".format(value), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { onSaveSoundCsv(soundCsv) }) {
                        Text("Save Sound as CSV")
                    }
                }
            }
        }
    }
}

@Composable
fun DataGraph(data: List<Pair<Long, Float>>, color: Color) {
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val paint = remember {
        Paint().apply {
            textAlign = Paint.Align.CENTER
            textSize = 35f
        }
    }
    paint.color = onSurfaceColor.copy(alpha = 0.87f).hashCode()

    Canvas(modifier = Modifier.fillMaxWidth().height(250.dp).padding(16.dp)) {
        if (data.size > 1) {
            val values = data.map { it.second }
            val timestamps = data.map { it.first }

            val maxVal = values.maxOrNull() ?: 1f
            val minVal = values.minOrNull() ?: 0f
            val startTime = timestamps.first()
            val endTime = timestamps.last()
            val totalDuration = (endTime - startTime).toFloat()

            val padding = 40.dp.toPx()
            val yAxisLabelPadding = 60.dp.toPx()
            val xAxisLabelPadding = 40.dp.toPx()

            // Draw Y-axis labels
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f".format(maxVal),
                yAxisLabelPadding - 20,
                padding,
                paint
            )
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f".format(minVal),
                yAxisLabelPadding - 20,
                size.height - padding,
                paint
            )

            // Draw X-axis labels
            drawContext.canvas.nativeCanvas.drawText(
                "${(startTime / 1000)}s",
                yAxisLabelPadding,
                size.height - xAxisLabelPadding + 35,
                paint
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${(endTime / 1000)}s",
                size.width - yAxisLabelPadding,
                size.height - xAxisLabelPadding + 35,
                paint
            )

            val graphWidth = size.width - 2 * yAxisLabelPadding
            val graphHeight = size.height - 2 * padding
            val valueRange = if (maxVal > minVal) maxVal - minVal else 1f

            if (totalDuration > 0) {
                for (i in 0 until data.size - 1) {
                    val p1 = data[i]
                    val p2 = data[i+1]

                    val startX = yAxisLabelPadding + graphWidth * ((p1.first - startTime) / totalDuration)
                    val startY = size.height - padding - (graphHeight * ((p1.second - minVal) / valueRange))
                    val endX = yAxisLabelPadding + graphWidth * ((p2.first - startTime) / totalDuration)
                    val endY = size.height - padding - (graphHeight * ((p2.second - minVal) / valueRange))

                    drawLine(
                        color = color,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 3f
                    )
                }
            }
        }
    }
}
