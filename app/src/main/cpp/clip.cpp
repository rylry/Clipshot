#include <oboe/Oboe.h>
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <vector>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NativeAudio", __VA_ARGS__)

static std::shared_ptr<oboe::AudioStream> stream;
static std::atomic<bool> gRecordingActive{false};
static std::atomic<uint64_t> gLastCallbackNs{0};

static inline int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1e9 + ts.tv_nsec;
}

class InputCallback : public oboe::AudioStreamCallback {
public:
    struct Config {
        int sampleRate = 44100;
        int durationSeconds = 30;
        int channels = 1;
    };

    // Default constructor
    InputCallback()
            : mConfig(), // defaults
              mBuffer(mConfig.sampleRate * mConfig.durationSeconds * mConfig.channels),
              mWriteIndex(0) {}

    // Thread-safe method to update configuration before starting
    void setConfig(int sampleRate, int durationSeconds, int channels) {
        const Config cfg = Config {sampleRate, durationSeconds, channels};
        std::lock_guard<std::mutex> lock(mMutex);
        mConfig = cfg;
        mBuffer.resize(cfg.sampleRate * cfg.durationSeconds * cfg.channels);
        mWriteIndex.store(0, std::memory_order_relaxed);
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream*,
            void* audioData,
            int32_t numFrames) override {

        gRecordingActive.store(true, std::memory_order_relaxed);
        gLastCallbackNs.store(nowNs(), std::memory_order_relaxed);

        int16_t* input = static_cast<int16_t*>(audioData);
        int samples = numFrames * mConfig.channels;

        int write = mWriteIndex.load(std::memory_order_relaxed);
        for (int i = 0; i < samples; ++i) {
            mBuffer[write] = input[i];
            write = (write + 1) % mBuffer.size();
        }
        mWriteIndex.store(write, std::memory_order_release);

        return oboe::DataCallbackResult::Continue;
    }

    void copySnapshot(uint8_t* out, int length) {
        int samples = length / sizeof (int16_t);
        int write = mWriteIndex.load(std::memory_order_acquire);
        int start = write - samples;
        if (start < 0) start += mBuffer.size();

        // Because buffer may wrap around, copy in two segments
        int firstChunk = std::min(samples, static_cast<int>(mBuffer.size() - start));

        // Copy first chunk
        memcpy(out, &mBuffer[start], firstChunk * sizeof(int16_t));

        // Copy second chunk if wrapped
        if (firstChunk < samples) {
            memcpy(out + firstChunk * 2, &mBuffer[0], (samples - firstChunk) * sizeof(int16_t));
        }
    }



private:
    Config mConfig;
    std::vector<int16_t> mBuffer;
    std::atomic<int> mWriteIndex;
    std::mutex mMutex; // protects setConfig & buffer resize
};

static InputCallback callback;

extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio__1start(JNIEnv*, jobject, jint sampleRate, jint durationSeconds, jint channels) {

    gRecordingActive.store(false, std::memory_order_relaxed);

    callback.setConfig(sampleRate, durationSeconds, channels);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(channels)
            ->setSampleRate(sampleRate)
            ->setCallback(&callback);

    oboe::Result r = builder.openStream(stream);
    if (r != oboe::Result::OK || !stream) {
        LOGI("Failed to open stream: %d", r);
        return;
    }

    stream->requestStart();
    LOGI("Stream started");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio__1stop(JNIEnv*, jobject) {
    gRecordingActive.store(false, std::memory_order_relaxed);

    if (stream) {
        stream->requestStop();
        stream->close();
        stream.reset();
        LOGI("Stream stopped");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rylry_clip_NativeAudio__1isRecordingActive(JNIEnv*, jobject) {

    if (!gRecordingActive.load(std::memory_order_relaxed)) {
        return JNI_FALSE;
    }

    uint64_t lastNs = gLastCallbackNs.load(std::memory_order_relaxed);

    // No callbacks in the last 2 seconds → dead
    constexpr uint64_t TIMEOUT_NS = 2e9;

    return (nowNs() - lastNs) < TIMEOUT_NS ? JNI_TRUE : JNI_FALSE;
}


// JNI function to copy the last 30 seconds into a Java short array
extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio__1copySnapshot(JNIEnv* env, jobject, jbyteArray outArray) {
    // Get length of Java byte array
    jsize len = env->GetArrayLength(outArray);

    // Lock the array for writing
    jboolean isCopy;
    jbyte* outBytes = env->GetByteArrayElements(outArray, &isCopy);
    if (outBytes == nullptr) return; // allocation failed

    // Copy snapshot into the byte array
    callback.copySnapshot(reinterpret_cast<uint8_t*>(outBytes), len);

    // Release the array back to Java
    env->ReleaseByteArrayElements(outArray, outBytes, 0); // 0 = copy back and free
}

