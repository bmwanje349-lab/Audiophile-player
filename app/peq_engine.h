#pragma once

#include <atomic>
#include <cstdint>
#include <complex>
#include <mutex>

namespace peq {

constexpr int kMaxBands = 16;
constexpr int kGraphicBands = 10;
constexpr int kMaxChannels = 8;
constexpr int kTruePeakHistory = 12;
constexpr int kTruePeakDelayFrames = 6; // Conservative alignment for the BS.1770 4-phase FIR.
constexpr int kMaxLookaheadFrames = 8192;
constexpr int kMaxLimiterDelayFrames = kMaxLookaheadFrames + kTruePeakDelayFrames;
constexpr int kMaxLimiterRingFrames = kMaxLimiterDelayFrames + 1;
constexpr int kMaxPeakQueue = kMaxLookaheadFrames + 4;
constexpr int kDefaultLinearPhaseTaps = 513;
constexpr int kMinLinearPhaseTaps = 257;
constexpr int kMaxLinearPhaseTaps = 1025;
constexpr int kLinearPhaseFftSize = 2048;
constexpr double kPi = 3.141592653589793238462643383279502884;
constexpr float kDefaultCeilingDb = -1.0f;
constexpr float kDefaultSafetyDb = 0.10f;

enum PhaseMode : int {
    kMinimumPhase = 0,
    kLinearPhase = 1,
};

enum FilterType : int {
    kPeaking = 0,
    kLowShelf = 1,
    kHighShelf = 2,
    kHighPass = 3,
    kLowPass = 4,
    kNotch = 5,
};

struct BandParams {
    int type = kPeaking;
    float freq = 1000.0f;
    float gainDb = 0.0f;
    float q = 1.0f;
    bool enabled = false;
};

struct Coeffs {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
    bool active = false;
};

class Engine {
public:
    explicit Engine(int numBands);
    ~Engine() = default;

    void setSampleRate(int sr);
    void setBand(int index, const BandParams& p);
    void setPeqEnabled(bool on);
    void setGraphicEqEnabled(bool on);
    void setGraphicBand(int index, float gainDb, float q, bool enabled);
    void setGraphicBands(const float* gainsDb, int count, float q);
    void setInputGainDb(float db);
    void setOutputGainDb(float db);
    void setBypass(bool on);

    void setLimiterEnabled(bool on);
    void setLimiterCeilingDb(float db);
    void setLimiterSafetyDb(float db);
    void setLimiterLookaheadMs(float ms);
    void setLimiterReleaseMs(float ms);

    void setFilterSmoothingMs(float ms);
    void setGainSmoothingMs(float ms);

    void setAutoGainEnabled(bool on);
    void setAutoGainAmount(float amount);
    float autoMakeupGainDb() const { return autoMakeupGainDb_.load(std::memory_order_relaxed); }

    void setLinearPhaseEnabled(bool on);
    void setLinearPhaseTaps(int taps);

    void reset();

    // Returns the target EQ response in dB, excluding gain automation and limiting.
    void responseDb(const float* freqs, float* outDb, int n) const;

    // Processes in-place. The buffer is compacted toward the beginning when the
    // fixed processing latency has not yet been paid in the current stream.
    // Returns the number of output frames actually written.
    int processFloat(float* data, int frames, int channels);
    int processS16(int16_t* data, int frames, int channels);

    // Flushes the remaining delayed audio at end-of-stream into the supplied buffer.
    int drainFloat(float* data, int maxFrames, int channels);
    int drainS16(int16_t* data, int maxFrames, int channels);

    int pendingFrames() const { return limiterPendingFrames_.load(std::memory_order_acquire); }
    int latencyFrames() const {
        const int fir = linearPhaseEnabled_.load(std::memory_order_relaxed)
            ? linearPhaseDelayFrames_.load(std::memory_order_relaxed)
            : 0;
        return limiterTotalDelayFrames_.load(std::memory_order_acquire) + fir;
    }

private:
    static float dbToLin(float db);
    static double safeExp(double x);
    static double smoothingCoeffMs(double ms, double sr);
    static bool effectivelyIdentity(const Coeffs& c);
    static Coeffs identityCoeffs();
    static Coeffs computeCoeffs(const BandParams& p, double sr);

    void rebuildAutoGainLocked();
    void rebuildLinearFirLocked();
    static int normalizeLinearPhaseTaps(int taps);
    static void fft(std::complex<double>* data, int n, bool inverse);
    static double responseDbFromCoeffs(const Coeffs* coeffs, int numBands, double freq, double sr);

    void syncParams();
    void clearStates();
    void resetLimiter();
    void configureLimiterIfNeeded();
    void clearLinearFirState();

    // Returns true when a delayed output frame was produced.
    bool processLimiterFrame(const float* in, float* out, int channels);

    // Advances the true-peak detector by one frame. When feedZero is true,
    // the detector is advanced with a zero sample for every channel, which is
    // used only while draining the end of the stream.
    float truePeakFrame(const float* frame, int channels, bool feedZero);

    // Updates limiter gain using the current detector window.
    void updateLimiterGain(float windowPeak);

    // Advances the detector/deque without producing audio.
    void advanceLimiterDetectorWithZero(int channels);

    int numBands_;
    mutable std::mutex mutex_;

    std::atomic<double> sampleRate_{48000.0};
    BandParams params_[kMaxBands];
    Coeffs pending_[kMaxBands];

    // Audio-thread-owned coefficient states.
    Coeffs target_[kMaxBands];
    Coeffs smoothed_[kMaxBands];

    std::atomic<bool> dirty_{false};
    std::atomic<bool> resetRequested_{false};

    std::atomic<float> inputGainDb_{0.0f};
    std::atomic<float> outputGainDb_{0.0f};
    std::atomic<bool> bypass_{false};
    // The PEQ and Graphic EQ are independent stages. Each has its own filter
    // coefficients, states and ON/OFF control.
    std::atomic<bool> peqEnabled_{true};
    std::atomic<bool> graphicEnabled_{false};

    std::atomic<bool> limiterEnabled_{true};
    std::atomic<float> limiterCeilingDb_{kDefaultCeilingDb};
    std::atomic<float> limiterSafetyDb_{kDefaultSafetyDb};
    std::atomic<float> limiterLookaheadMs_{2.0f};
    std::atomic<float> limiterReleaseMs_{180.0f};
    std::atomic<float> filterSmoothingMs_{8.0f};
    std::atomic<float> gainSmoothingMs_{5.0f};

    std::atomic<bool> autoGainEnabled_{false};
    std::atomic<float> autoGainAmount_{1.0f};
    std::atomic<float> autoMakeupGainDb_{0.0f};

    std::atomic<bool> linearPhaseEnabled_{false};
    std::atomic<int> linearPhaseTaps_{kDefaultLinearPhaseTaps};
    std::atomic<int> linearPhaseDelayFrames_{(kDefaultLinearPhaseTaps - 1) / 2};
    std::atomic<bool> firDirty_{false};
    std::atomic<bool> firHardSyncRequested_{false};

    double z1_[kMaxBands][kMaxChannels] = {};
    double z2_[kMaxBands][kMaxChannels] = {};

    float inputGain_ = 1.0f;
    float outputGain_ = 1.0f;
    float inputGainCoeff_ = 1.0f;
    float outputGainCoeff_ = 1.0f;

    // Fixed-latency true-peak limiter.
    float limiterDelay_[kMaxLimiterRingFrames][kMaxChannels] = {};
    float truePeakHistory_[kMaxChannels][kTruePeakHistory] = {};
    float limiterPeakDeque_[kMaxPeakQueue] = {};
    uint64_t limiterIndexDeque_[kMaxPeakQueue] = {};

    int limiterDequeHead_ = 0;
    int limiterDequeTail_ = 0;
    int limiterWritePos_ = 0;
    int limiterReadPos_ = 0;
    int limiterLookaheadFrames_ = 96;
    std::atomic<int> limiterTotalDelayFrames_{kTruePeakDelayFrames + 96};
    int limiterRingSize_ = kTruePeakDelayFrames + 96 + 1;
    std::atomic<int> limiterPendingFrames_{0};
    uint64_t limiterSampleIndex_ = 0;

    float limiterGain_ = 1.0f;

    // Optional causal symmetric FIR used for linear-phase mode. The FIR is
    // designed off the audio thread from the requested EQ magnitude response.
    float pendingFir_[kMaxLinearPhaseTaps] = {};
    float targetFir_[kMaxLinearPhaseTaps] = {};
    float activeFir_[kMaxLinearPhaseTaps] = {};
    float linearFirState_[kMaxChannels][kMaxLinearPhaseTaps] = {};
    int linearFirWritePos_ = 0;
    int linearFirTapsActive_ = kDefaultLinearPhaseTaps;
    int firTransitionRemaining_ = 0;
    int firTransitionTotal_ = 0;
    float limiterReleaseCoeff_ = 0.0001f;
    int lastConfiguredLookaheadFrames_ = -1;

    // Independent 10-band graphic EQ state.
    BandParams graphicParams_[kGraphicBands];
    Coeffs graphicPending_[kGraphicBands];
    Coeffs graphicTarget_[kGraphicBands];
    Coeffs graphicSmoothed_[kGraphicBands];
    double graphicZ1_[kGraphicBands][kMaxChannels] = {};
    double graphicZ2_[kGraphicBands][kMaxChannels] = {};
    float graphicWet_ = 0.0f;
    float peqWet_ = 1.0f;

    bool wasBypassed_ = false;
};

} // namespace peq
