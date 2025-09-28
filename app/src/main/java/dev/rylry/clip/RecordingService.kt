package dev.rylry.clip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.math.min


class RecordingService : Service() {
    private var fileName: String ? = null
    var sampleRateHz: Int = 44100 // Example sample rate
    var recordDurationSeconds: Int = 30
    var channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    var audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    var audioBufferSize: Int = sampleRateHz * recordDurationSeconds * 2 // Because PCM16 is 2 bytes/sample
    var audioBuffer = ByteArray(audioBufferSize)
    var audioWritePointer: Int = 0
    var audioChunkSize: Int = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, audioFormat)
    var isRecording: Boolean = false
    var isRecordingVideo: Boolean = false
    var recordingThread: Thread? = null
    var record: AudioRecord? = null

    lateinit var cameraDevice: CameraDevice
    lateinit var imageReader: ImageReader

    private val binder = AudioServiceBinder()

    inner class AudioServiceBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }


    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO])
    override fun onCreate() {
        super.onCreate()
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            channelConfig,
            audioFormat,
            audioBufferSize
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create notification
        val channelId = "RecordingServiceChannel"
        createNotificationChannel(channelId)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Audio")
            .setContentText("Your audio is being recorded")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        // Start foreground
        startForeground(1, notification)

        // Start recording in background thread
        startRecording()
        startCamera()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        record?.release()
        record = null
    }

    private fun createNotificationChannel(channelId: String) {
        val channel = NotificationChannel(
            channelId,
            "Audio Recording Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun startRecording(){
        isRecording = true
        record?.startRecording()
        recordingThread = Thread {
            while (isRecording) {
                record?.read(audioBuffer, audioWritePointer, if(audioWritePointer + audioChunkSize < audioBufferSize) audioChunkSize else audioBufferSize - audioWritePointer)
                if(audioWritePointer + audioChunkSize >= audioBufferSize) record?.read(audioBuffer, 0, audioChunkSize - audioBufferSize + audioWritePointer)
                audioWritePointer = (audioWritePointer + audioChunkSize) % audioBufferSize
            }
        }
        recordingThread?.start()
    }

    fun stopRecording() {
        isRecording = false
        try {
            recordingThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        record?.stop()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun startCamera() {
        val manager: CameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        // Get the right camera
        // --------------------
        val cameras = manager.cameraIdList.filter {
            val characteristics = manager.getCameraCharacteristics(it)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

            // Check if the camera has LENS_FACING property and it's facing backward
            lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }

        val camera = cameras[0]

        manager.openCamera(camera, object : CameraDevice.StateCallback() {
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
            }

            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                setupCaptureSession()
            }
        }, null)

        // Create an ImageReader for the camera to record to
        // -------------------------------------------------
        val characteristics = manager.getCameraCharacteristics(camera)
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) as StreamConfigurationMap

        val size = (map.getOutputSizes(ImageFormat.YUV_420_888))?.maxByOrNull { it.width * it.height }

        imageReader = ImageReader.newInstance( // ImageReader is a canvas that raw camera pixels can be fed to
            size?.width ?: 1,
            size?.height ?: 1, ImageFormat.YUV_420_888, 2
        )

        imageReader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(reader: ImageReader?) {
                reader?.let { reader ->
                    val rawFrame = reader.acquireLatestImage()
                    val format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        imageReader.width,
                        imageReader.height
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    }

                }
            }
        }, null)
    }

    private fun setupCaptureSession() {
        val surfaces = listOf(imageReader.surface)
        cameraDevice.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(imageReader.surface) // Surface where frames will go
                }

                // Start the session
                session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) { /* ... */ }
        }, null)
    }
    fun saveBuffersMP4() {

        val outputFile = File(filesDir, "audio_${System.currentTimeMillis()}.m4a")

        val first = audioBuffer.copyOfRange(audioWritePointer, audioBuffer.size)
        val second = audioBuffer.copyOfRange(0, audioWritePointer)
        val pcmData = first + second

        val sampleRate = 44100
        val channelCount = 1 // mono
        val bitRate = 128_000

        // --- Setup encoder ---
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // --- Setup muxer ---
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val inputBuffers = encoder.inputBuffers
        val outputBuffers = encoder.outputBuffers

        var pcmOffset = 0

        while (pcmOffset < pcmData.size) {
            // Feed PCM to encoder
            val inputIndex = encoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = inputBuffers[inputIndex]
                inputBuffer.clear()

                val bytesToWrite = min(inputBuffer.capacity(), pcmData.size - pcmOffset)
                inputBuffer.put(pcmData, pcmOffset, bytesToWrite)

                val presentationTimeUs = (pcmOffset.toLong() * 1_000_000L) / (sampleRate * 2 * channelCount)
                encoder.queueInputBuffer(inputIndex, 0, bytesToWrite, presentationTimeUs, 0)
                pcmOffset += bytesToWrite
            }

            // Get encoded output
            var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            while (outputIndex >= 0) {
                val encodedData = outputBuffers[outputIndex]
                encodedData.position(bufferInfo.offset)
                encodedData.limit(bufferInfo.offset + bufferInfo.size)

                if (!muxerStarted) {
                    val newFormat = encoder.outputFormat
                    muxerTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }

                muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo)
                encoder.releaseOutputBuffer(outputIndex, false)
                outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        }

        // --- Signal end of stream ---
        val inputIndex = encoder.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        // Drain remaining output
        var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
        while (outputIndex >= 0) {
            val encodedData = outputBuffers[outputIndex]
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)

            muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo)
            encoder.releaseOutputBuffer(outputIndex, false)
            outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
        }

        // --- Cleanup ---
        encoder.stop()
        encoder.release()
        muxer.stop()
        muxer.release()
    }
}
