package dev.rylry.clip

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.io.File
import java.io.FileInputStream
import kotlin.math.min

private const val TIMEOUT_MCRS: Long = 10000

class RecordingService : Service() {

  inner class AudioServiceBinder : Binder() {
    fun getService(): RecordingService = this@RecordingService
  }
  private val binder = AudioServiceBinder()

  private lateinit var wakeLock: PowerManager.WakeLock
  private lateinit var handler: Handler
  private val watchdogInterval = 5000L // 5 seconds
  private val watchdogRunnable =
          object : Runnable {
            override fun run() {
              try {
                val active = NativeAudio.isRecordingActive() // Add this JNI method
                if (!active && wakeLock.isHeld) {
                  NativeAudio.stop()
                  NativeAudio.start()
                }
              } catch (e: Exception) {
                e.printStackTrace()
                NativeAudio.stop()
                NativeAudio.start()
              } finally {
                handler.postDelayed(this, watchdogInterval)
              }
            }
          }

  var expirationDelay: Long = 0

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()

    handler = Handler(getMainLooper())

    registerReceiver(saveReceiver, IntentFilter("dev.rylry.SAVE_BUFFERS"), RECEIVER_EXPORTED)

    registerReceiver(stopReceiver, IntentFilter("dev.rylry.STOP_RECORDING"), RECEIVER_EXPORTED)
  }

  @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO])
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

    intent?.let { expirationDelay = it.getLongExtra("TIME_ALIVE_MS", 0L) }
    handler.postDelayed({ stopSelf() }, expirationDelay)

    val stopIntent: PendingIntent =
            PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent("dev.rylry.STOP_RECORDING"),
                    PendingIntent.FLAG_IMMUTABLE
            )
    val saveIntent: PendingIntent =
            PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent("dev.rylry.SAVE_BUFFERS"),
                    PendingIntent.FLAG_IMMUTABLE
            )

    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Clip::Recording")
    startRecording()

    val channelId = "RecordingServiceChannel"
    createNotificationChannel(channelId)

    val notification =
            NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Recording Audio")
                    .setContentText("Tap button to clip last 30 seconds")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .addAction(android.R.drawable.ic_menu_add, "Save", saveIntent)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                    .setStyle(
                            MediaNotificationCompat.MediaStyle()
                                    .setShowActionsInCompactView(0) // show first action collapsed
                    )
                    .build()

    startForeground(1, notification)

    return START_STICKY
  }

  private val saveReceiver =
          object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
              saveBuffersM4A()
            }
          }

  private val stopReceiver: BroadcastReceiver =
          object : BroadcastReceiver() {
            override fun onReceive(context: Context?, internt: Intent?) {
              // stopRecording()
              stopSelf()
            }
          }

  override fun onDestroy() {
    stopRecording()
    unregisterReceiver(saveReceiver)
    super.onDestroy()
  }

  private fun createNotificationChannel(channelId: String) {
    val channel =
            NotificationChannel(
                            channelId,
                            "Audio Recording Service",
                            NotificationManager.IMPORTANCE_HIGH
                    )
                    .apply { enableVibration(true) }
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
  }

  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  fun startRecording() {
    NativeAudio.start()
    wakeLock.acquire()
    handler.post(watchdogRunnable)
  }

  fun stopRecording() {
    NativeAudio.stop()
    if (wakeLock.isHeld) {
      wakeLock.release()
    }
    handler.removeCallbacks(watchdogRunnable)
  }

  fun saveBuffersM4A() {
    val clipTime = System.currentTimeMillis()
    val outputFile = File(filesDir, "cache.m4a")

    val sampleRate = 44100
    val sampleLength = 30
    val bytesPerSample = 2
    val channelCount = 1
    val bitRate = 128_000
    val maxCodecInputBytes = 16 * 1024 // 16384

    // Fetch raw PCM from JNI layer
    val buffer = ByteArray(sampleRate * sampleLength * bytesPerSample)
    NativeAudio.copySnapshot(buffer)

    val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
                    .apply {
                      setInteger(
                              MediaFormat.KEY_AAC_PROFILE,
                              MediaCodecInfo.CodecProfileLevel.AACObjectLC
                      )
                      setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxCodecInputBytes)
                    }

    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    encoder.start()

    val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxerTrackIndex = -1
    var muxerStarted = false

    val bufferInfo = MediaCodec.BufferInfo()
    var pcmOffset = 0

    while (pcmOffset < buffer.size) {
      val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_MCRS)
      if (inputIndex >= 0) {
        val inputBuffer = encoder.getInputBuffer(inputIndex)!!
        inputBuffer.clear()
        val bytesToWrite = min(inputBuffer.capacity(), buffer.size - pcmOffset)
        inputBuffer.put(buffer, pcmOffset, bytesToWrite)

        val presentationTimeUs =
                (pcmOffset.toLong() * 1_000_000L) /
                        (sampleRate *
                                2 *
                                channelCount) // 1,000,000 to convert microseconds to seconds
        encoder.queueInputBuffer(inputIndex, 0, bytesToWrite, presentationTimeUs, 0)
        pcmOffset += bytesToWrite
      }

      var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_MCRS)
      while (outputIndex >= 0) {
        val encodedData = encoder.getOutputBuffer(outputIndex)!!
        if (!muxerStarted) {
          muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
          muxer.start()
          muxerStarted = true
        }
        muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo)
        encoder.releaseOutputBuffer(outputIndex, false)
        outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
      }
    }

    // Signal EOS
    val finalInputIndex = encoder.dequeueInputBuffer(TIMEOUT_MCRS)
    if (finalInputIndex >= 0) {
      encoder.queueInputBuffer(finalInputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    // Drain remaining output
    var finalOutputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_MCRS)
    while (finalOutputIndex >= 0) {
      val encodedData = encoder.getOutputBuffer(finalOutputIndex)!!
      muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo)
      encoder.releaseOutputBuffer(finalOutputIndex, false)
      finalOutputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
    }

    encoder.stop()
    encoder.release()
    muxer.stop()
    muxer.release()

    downloadCache(this, clipTime)
  }

  fun downloadCache(context: Context, time: Long) {
    val sourceFile = File(context.filesDir, "cache.m4a")
    if (!sourceFile.exists()) return

    val values =
            ContentValues().apply {
              put(MediaStore.Audio.Media.DISPLAY_NAME, "clip_${time}.m4a")
              put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
              put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

    val resolver = context.contentResolver
    val uri: Uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
              null
            }
                    ?: return

    resolver.openOutputStream(uri)?.use { out ->
      FileInputStream(sourceFile).use { input -> input.copyTo(out) }
    }
  }
}
