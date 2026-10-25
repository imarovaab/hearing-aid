package com.example.recordaudio1

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.recordaudio1.databinding.ActivityMainBinding
import jwave.transforms.WaveletPacketTransform
import jwave.transforms.wavelets.Wavelet
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log
import com.google.api.gax.rpc.ClientStream
import com.google.api.gax.rpc.ResponseObserver
import com.google.api.gax.rpc.StreamController
import com.google.cloud.speech.v1.*
import com.google.cloud.speech.v1.RecognitionConfig.AudioEncoding
import com.google.protobuf.ByteString
import java.io.FileInputStream
import com.google.cloud.speech.v1.SpeechSettings
import kotlinx.coroutines.withContext
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null

    enum class DenoisingType {
        LOW_PASS_FILTER,
        WAVELET,
        BUTTERWORTH_FILTER
    }

    // Google Cloud Speech API variables
    private var speechClient: SpeechClient? = null
    private var clientStream: ClientStream<StreamingRecognizeRequest>? = null
    private var isSpeechToTextEnabled = false
    private var recognizedText = StringBuilder()


    private var currentDenoisingType = DenoisingType.LOW_PASS_FILTER

    // Audio configuration
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Add these variables for audio saving
    private var originalOutputStream: FileOutputStream? = null
    private var processedOutputStream: FileOutputStream? = null
    private var originalFile: File? = null
    private var processedFile: File? = null
    val minBufferSize = AudioRecord.getMinBufferSize(
        44100,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    val bufferSize = maxOf(minBufferSize, 88200)

    // Low-pass filter
    private val lpfCutoff = 4000.0
    private val lpfAlpha = calculateAlpha(lpfCutoff, sampleRate.toDouble())
    private var lastFilteredValue = 0.0

    // Wavelet transform
    private val wavelet: Wavelet = jwave.transforms.wavelets.haar.Haar1()
    private val waveletLevels = 4
    private val waveletTransform = WaveletPacketTransform(wavelet)

    // Speech to text fields
    private val responseObserver = object : ResponseObserver<StreamingRecognizeResponse> {
        override fun onStart(controller: StreamController) {
            Log.d("SpeechToText", "Speech API stream started")
        }

        override fun onResponse(response: StreamingRecognizeResponse) {
            if (response.resultsCount > 0) {
                val result = response.getResults(0)
                if (result.alternativesCount > 0) {
                    val transcript = result.getAlternatives(0).transcript
                    val isFinal = result.isFinal

                    Log.d("SpeechToText", "Transcript: $transcript, Final: $isFinal")

                    // Check for keywords
                    if (transcript.lowercase().contains("danger") ||
                        transcript.lowercase().contains("help") ||
                        transcript.lowercase().contains("emergency")) {

                        // Vibrate the device
                        runOnUiThread {
                            vibrateDevice()
                            // Optional: Visual feedback
                            Toast.makeText(this@MainActivity,
                                "⚠️ ALERT: Keyword detected!",
                                Toast.LENGTH_SHORT).show()
                        }
                    }

                    runOnUiThread {
                        if (isFinal) {
                            recognizedText.append(transcript).append("\n")
                            binding.textViewTranscript.text = recognizedText.toString()
                        } else {
                            binding.textViewInterim.text = transcript
                        }
                    }
                }
            }
        }
        override fun onError(t: Throwable) {
            Log.e("SpeechToText", "Error in speech recognition", t)
            runOnUiThread {
                Toast.makeText(this@MainActivity,
                    "Speech recognition error: ${t.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }
        override fun onComplete() {
            Log.d("SpeechToText", "Speech API stream completed")
        }
    }

    // AudioSaver object
    object AudioSaver {
        private const val TAG = "AudioSaver"

        fun createAudioFile(context: Context, prefix: String): File {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val dir = context.getExternalFilesDir("AudioRecordings")?.apply { mkdirs() }
            return File(dir, "${prefix}_${timestamp}.pcm")
        }

        fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
            val bytes = ByteArray(shorts.size * 2)
            ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(shorts)
            return bytes
        }
    }


    // Speech Client initialization
    private fun initializeSpeechClient() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Log each step
                Log.d("SpeechToText", "Starting speech client initialization")
                val credentialsFile = File(filesDir, "hearingaid-457009-fc8015c92b62.json")
                // First check if we need to copy the file from assets
                if (!credentialsFile.exists()) {
                    try {
                        // Copy from assets
                        val inputStream = assets.open("hearingaid-457009-fc8015c92b62.json")
                        val outputStream = FileOutputStream(credentialsFile)
                        inputStream.copyTo(outputStream)
                        inputStream.close()
                        outputStream.close()
                        Log.d("SpeechToText", "Copied credentials file successfully")
                    } catch (e: Exception) {
                        Log.e("SpeechToText", "Failed to copy credentials file", e)
                        throw Exception("Failed to copy credentials: ${e.message}")
                    }
                }

                // Load credentials directly
                val credentials = GoogleCredentials.fromStream(FileInputStream(credentialsFile))
                    .createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
                // Create a fixed credentials provider
                val credentialsProvider = FixedCredentialsProvider.create(credentials)
                // Build settings with the provider
                val settings = SpeechSettings.newBuilder()
                    .setCredentialsProvider(credentialsProvider)
                    .build()
                // Create client with settings
                speechClient = SpeechClient.create(settings)

                Log.d("SpeechToText", "Speech client initialized successfully")

                // Update UI
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        "Speech client initialized successfully",
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SpeechToText", "Failed to initialize Speech client", e)
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        "Speech client error: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Speech Client
        initializeSpeechClient()

        setupUI()
        binding.radioLowPass.isChecked = true

    }

    private fun setupUI() {
        // Don't disable buttons initially
        binding.button.isEnabled = true
        binding.button2.isEnabled = false

        // Add checkbox for enabling/disabling speech-to-text
        binding.checkboxSpeechToText.setOnCheckedChangeListener { _, isChecked ->
            isSpeechToTextEnabled = isChecked
            if (isChecked) {
                showToast("Speech-to-text enabled")
                binding.textViewTranscriptLabel.visibility = android.view.View.VISIBLE
                binding.textViewTranscript.visibility = android.view.View.VISIBLE
                binding.textViewInterim.visibility = android.view.View.VISIBLE
            } else {
                showToast("Speech-to-text disabled")
                binding.textViewTranscriptLabel.visibility = android.view.View.GONE
                binding.textViewTranscript.visibility = android.view.View.GONE
                binding.textViewInterim.visibility = android.view.View.GONE
            }
        }

        binding.buttonClearTranscript.setOnClickListener {
            recognizedText.clear()
            binding.textViewTranscript.text = ""
            binding.textViewInterim.text = ""
        }

        binding.radioGroup.setOnCheckedChangeListener { group, checkedId ->
            if (group.checkedRadioButtonId != checkedId) return@setOnCheckedChangeListener

            currentDenoisingType = when (checkedId) {
                R.id.radioLowPass -> {
                    binding.radioWavelet.isChecked = false
                    binding.radioButterworth.isChecked = false
                    DenoisingType.LOW_PASS_FILTER
                }
                R.id.radioWavelet -> {
                    binding.radioLowPass.isChecked = false
                    binding.radioButterworth.isChecked = false
                    DenoisingType.WAVELET
                }
                R.id.radioButterworth -> {
                    binding.radioLowPass.isChecked = false
                    binding.radioWavelet.isChecked = false
                    DenoisingType.BUTTERWORTH_FILTER
                }
                else -> currentDenoisingType
            }
            showToast("${getCurrentDenoisingName()} selected")
        }
        binding.button.setOnClickListener {
            if (!isRecording.get()) {
                if (checkPermission()) {
                    startStreamingAudio()
                    updateUIForRecording(true)
                    showToast("Started streaming with ${getCurrentDenoisingName()}")
                }
            }
        }

        binding.button2.setOnClickListener {
            stopStreamingAudio()
            updateUIForRecording(false)
            showToast("Stopped streaming")
        }
    }



    private fun checkPermission(): Boolean {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions, 111)
            return false
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                111
            )
            return false
        }
        return true
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 111 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // Start recording immediately after permission is granted
            startStreamingAudio()
            updateUIForRecording(true)
            showToast("Started streaming with ${getCurrentDenoisingName()}")
        } else {
            showToast("Microphone permission is required for recording")
        }
    }



    private fun updateUIForRecording(isRecording: Boolean) {
        binding.button.text = if (isRecording) "Recording..." else "Start"
        binding.button2.isEnabled = isRecording
        binding.radioGroup.isEnabled = !isRecording
        binding.checkboxSpeechToText.isEnabled = !isRecording

    }

    private fun startStreamingAudio() {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            ).also { recorder ->
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("Audio", "AudioRecord init failed!")
                    throw IllegalStateException("AudioRecord initialization failed")
                }
            }


            // Create audio files and streams in debug mode
            if (BuildConfig.SAVE_AUDIO_FILES){
                originalFile = AudioSaver.createAudioFile(this,
                    "original")
                processedFile = AudioSaver.createAudioFile(this,
                    "processed_${currentDenoisingType.name.lowercase()}")
                originalOutputStream = FileOutputStream(originalFile)
                processedOutputStream = FileOutputStream(processedFile)
            }

            audioTrack = AudioTrack.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            isRecording.set(true)
            lastFilteredValue = 0.0

            // Start speech to text if enabled
            if (isSpeechToTextEnabled){
                startSpeechToText()
            }

            audioTrack?.play()
            audioRecord?.startRecording()
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(bufferSize / 4) // Smaller chunks for smoother processing
                var silenceCounter = 0

                try {
                    while (isRecording.get()) {
                        val readResult = audioRecord!!.read(buffer, 0, buffer.size)
                        if (readResult > 0) {
                            // Calculate audio level for feedback
//                            val maxAmplitude = buffer.take(readResult).maxOf { abs(it.toInt()) }

                            // Log audio level every second or so
//                            if (silenceCounter % 10 == 0) {
//                                Log.d("AudioLevel", "Current max amplitude: $maxAmplitude")
//                            }

                            // Save original audio in debug mode
                            if (BuildConfig.SAVE_AUDIO_FILES){
                                originalOutputStream?.write(
                                    AudioSaver.shortArrayToByteArray(
                                        buffer.copyOfRange(0, readResult)
                                    )
                                )
                            }


                            // Process audio based on selected filter
                            val processed = processAudio(buffer.copyOfRange(0, readResult))

                            // Save processed audio in debug mode
                            if (BuildConfig.SAVE_AUDIO_FILES){
                                processedOutputStream?.write(
                                    AudioSaver.shortArrayToByteArray(processed)
                                )
                            }

                            // Send to speech recognition if enabled
                            if (isSpeechToTextEnabled) {
                                sendAudioChunk(buffer, readResult)
                            }
                            // Play processed audio
                            audioTrack?.write(processed, 0, processed.size)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AudioProcessing", "Error processing audio", e)
                }
            }

        } catch (e: Exception) {
            Log.e("streaming", "Streaming error", e)
            closeAudioFiles()
        }
    }

private fun closeAudioFiles() {
    try {
        if (BuildConfig.SAVE_AUDIO_FILES) {
            originalOutputStream?.close()
            processedOutputStream?.close()
        }
        originalOutputStream = null
        processedOutputStream = null
        originalFile = null
        processedFile = null
    } catch (e: Exception) {
        Log.e("AudioClose", "Error closing audio files", e)
    }
}

    private fun stopStreamingAudio() {
        isRecording.set(false)
        recordingJob?.cancel()

        if (isSpeechToTextEnabled) {
            stopSpeechToText()
        }

        try {
            audioRecord?.stop()
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.e("streaming", "Error Stopping stream", e)
        }

        closeAudioFiles()

        audioRecord?.release()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null
        lastFilteredValue = 0.0

        // Show saved files information
        runOnUiThread {
            val originalSize = originalFile?.length() ?: 0
            val processedSize = processedFile?.length() ?: 0
            showToast("Saved: Original(${originalSize/1024}KB) " +
                    "Processed(${processedSize/1024}KB)")
        }
    }

    // Speech to Text configurations
    private fun startSpeechToText() {
        try {
            // Check if Speech client is initialized
            if (speechClient == null) {
                showToast("Speech client not initialized")
                return
            }

            Log.d("SpeechToText", "Starting speech recognition...")

            val recognitionConfig = RecognitionConfig.newBuilder()
                .setEncoding(AudioEncoding.LINEAR16)
                .setSampleRateHertz(sampleRate)
                .setLanguageCode("en-US")
                .setModel("default")
                .setEnableAutomaticPunctuation(true)
                .setUseEnhanced(true)
                .addSpeechContexts(SpeechContext.newBuilder()
                    .addAllPhrases(listOf("audio", "recording", "speech", "test"))
                    .build())
                .build()
            
            val streamingConfig = StreamingRecognitionConfig.newBuilder()
                .setConfig(recognitionConfig)
                .setInterimResults(true)  // Show intermediate results
                .setSingleUtterance(false) // Process continuous speech
                .build()

            // First request must contain only config, no audio
            val initialRequest = StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(streamingConfig)
                .build()
            // Create bidirectional streaming RPC
            clientStream = speechClient!!.streamingRecognizeCallable().splitCall(responseObserver)
            // Send config request
            clientStream!!.send(initialRequest)
            // Reset recognized text
            recognizedText.clear()
            runOnUiThread {
                binding.textViewTranscript.text = ""
                binding.textViewInterim.text = ""
            }
            Log.d("SpeechToText", "Speech to text streaming started successfully")

        } catch (e: Exception) {
            Log.e("SpeechToText", "Error starting speech to text", e)
            showToast("Error starting speech recognition: ${e.message}")
        }
    }

    // Send audio chunks to Google Cloud API
    private fun sendAudioChunk(buffer: ShortArray, size: Int) {
        try {
            if (clientStream == null || !isRecording.get() || !isSpeechToTextEnabled) return

            // Log to see if we're getting audio data
            val maxAmplitude = buffer.take(size).maxOf { abs(it.toInt()) }
            Log.d("SpeechToText", "Sending audio chunk, size: $size, max amplitude: $maxAmplitude")

            // Only send audio if there's actual sound
            if (maxAmplitude > 50) {
                // Convert audio chunk to ByteString
                val audioBytes = AudioSaver.shortArrayToByteArray(buffer.copyOfRange(0, size))
                val audioData = ByteString.copyFrom(audioBytes)

                // Create audio content request
                val request = StreamingRecognizeRequest.newBuilder()
                    .setAudioContent(audioData)
                    .build()

                // Send audio chunk
                clientStream?.send(request)
            }

        } catch (e: Exception) {
            Log.e("SpeechToText", "Error sending audio chunk", e)
        }
    }

    private fun stopSpeechToText() {
        try {
            // Mark the end of the audio stream
            if (clientStream != null) {
                clientStream?.closeSend()
                clientStream = null
            }
            // Display final transcript
            runOnUiThread {
                binding.textViewInterim.text = ""
                if (recognizedText.isEmpty()) {
                    binding.textViewTranscript.text = "(No speech detected)"
                }
            }

        } catch (e: Exception) {
            Log.e("SpeechToText", "Error stopping speech to text", e)
        }
    }


    // vibrate the device
    private fun vibrateDevice() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
        Log.d("KeywordDetection", "Vibration triggered")
    }


    private fun processAudio(buffer: ShortArray): ShortArray {
        return when (currentDenoisingType) {
            DenoisingType.LOW_PASS_FILTER -> processWithLowPassFilter(buffer)
            DenoisingType.WAVELET -> processWithWavelet(buffer)
            DenoisingType.BUTTERWORTH_FILTER -> processWithButterworthFilter(buffer)
        }
    }

    // Process audio buffer through low-pass filter
    private fun processWithLowPassFilter(buffer: ShortArray): ShortArray {
        val processed = ShortArray(buffer.size)
        for (i in buffer.indices) {
            // Convert short to double for processing
            val input = buffer[i].toDouble()
            // Apply low-pass filter
            lastFilteredValue += lpfAlpha * (input - lastFilteredValue)
            processed[i] = lastFilteredValue
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .toInt()
                .toShort()
        }
        return processed
    }

    class ButterworthFilter(order: Int, sampleRate: Double, cutoffFreq: Double) {
        private val sections: Array<SecondOrderSection>

        init {
            // Calculate number of 2nd-order sections (biquads)
            val numSections = order / 2 + order % 2
            sections = Array(numSections) { SecondOrderSection() }

            // Calculate filter coefficients
            val omega = 2.0 * Math.PI * cutoffFreq / sampleRate
            val alpha = Math.sin(omega) / (2.0 * order)

            for (i in 0 until numSections) {
                // For each section, calculate pole location
                val theta = Math.PI * (2.0 * i + 1.0) / (2.0 * order)
                val realPole = -Math.sin(theta) * alpha
                val imagPole = Math.cos(theta) * alpha
                // Convert to filter coefficients
                sections[i].b0 = omega * omega
                sections[i].b1 = 2.0 * omega * omega
                sections[i].b2 = omega * omega
                sections[i].a1 = 2.0 * realPole
                sections[i].a2 = realPole * realPole + imagPole * imagPole
                // Normalize
                val gain = 1.0 / (1.0 + sections[i].a1 + sections[i].a2)
                sections[i].b0 *= gain
                sections[i].b1 *= gain
                sections[i].b2 *= gain
            }
        }

        fun process(input: Double): Double {
            var result = input
            // Process through each section
            for (section in sections) {
                result = section.process(result)
            }
            return result
        }

        // Inner class representing a 2nd order section (biquad)
        inner class SecondOrderSection {
            // Filter coefficients
            var b0 = 0.0
            var b1 = 0.0
            var b2 = 0.0
            var a1 = 0.0
            var a2 = 0.0
            // State variables
            private var x1 = 0.0
            private var x2 = 0.0
            private var y1 = 0.0
            private var y2 = 0.0
            fun process(input: Double): Double {
                // Direct Form II implementation
                val result = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                // Update state
                x2 = x1
                x1 = input
                y2 = y1
                y1 = result
                return result
            }
        }
    }

    // Create a 4th order Butterworth low-pass filter
    private val butterworth = ButterworthFilter(4, sampleRate.toDouble(), lpfCutoff)

    private fun processWithButterworthFilter(buffer: ShortArray): ShortArray {
        val processed = ShortArray(buffer.size)
        for (i in buffer.indices) {
            processed[i] = butterworth.process(buffer[i].toDouble())
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .toInt()
                .toShort()
        }
        return processed
    }


    private fun processWithWavelet(buffer: ShortArray): ShortArray {
        if (buffer.isEmpty()) return buffer
        try {
            // Ensure proper buffer size for wavelet transform
            val transformSize = nextPowerOf2(buffer.size)
            val doubleBuffer = DoubleArray(transformSize) {
                if (it < buffer.size) buffer[it].toDouble() else 0.0
            }

            // Perform wavelet transform and denoising
            val coefficients = waveletTransform.forward(doubleBuffer, waveletLevels)
            val threshold = calculateThreshold(coefficients)
            val denoisedCoefficients = applyThreshold(coefficients, threshold)
            val denoisedBuffer = waveletTransform.reverse(denoisedCoefficients, waveletLevels)

            // Convert back to original size
            return ShortArray(buffer.size) { i ->
                denoisedBuffer[i]
                    .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                    .toInt()
                    .toShort()
            }
        } catch (e: Exception) {
            Log.e("WaveletDenoising", "Error processing wavelet")
            return buffer
        }
    }

    // Helper functions

    private fun calculateAlpha(cutoffFreq: Double, samplingRate: Double): Double {
        val dt = 1.0 / samplingRate
        val rc = 1.0 / (2.0 * Math.PI * cutoffFreq)
        return dt / (rc + dt)
    }

    private fun nextPowerOf2(n: Int): Int = 1 shl (32 - Integer.numberOfLeadingZeros(n - 1))

    private fun calculateThreshold(coefficients: DoubleArray): Double {
        val mad = calculateMAD(coefficients)
        return mad * sqrt(2.0 * ln(coefficients.size.toDouble()))
    }

    private fun calculateMAD(data: DoubleArray): Double {
        if (data.isEmpty()) return 0.0

        val median = data.sorted().let {
            if (it.size % 2 == 0) (it[it.size/2] + it[it.size/2 - 1]) / 2
            else it[it.size/2]
        }

        val deviations = data.map { abs(it - median) }.sorted()
        return if (deviations.isEmpty()) 0.0
        else if (deviations.size % 2 == 0) (deviations[deviations.size/2] + deviations[deviations.size/2 - 1]) / 2
        else deviations[deviations.size/2]
    }

    private fun applyThreshold(coefficients: DoubleArray, threshold: Double): DoubleArray {
        return DoubleArray(coefficients.size) { i ->
            when {
                abs(coefficients[i]) <= threshold -> 0.0
                coefficients[i] > threshold -> coefficients[i] - threshold
                else -> coefficients[i] + threshold
            }
        }
    }

    private fun getCurrentDenoisingName() = when (currentDenoisingType) {
        DenoisingType.LOW_PASS_FILTER -> "Low pass filter"
        DenoisingType.WAVELET -> "Wavelet denoising"
        DenoisingType.BUTTERWORTH_FILTER -> "Butterworth Filter"

    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeAudioFiles()
    }
    override fun onStop() {
        super.onStop()
        stopStreamingAudio()
    }
}