#pragma once

/*
    PremiumVocalRemoverDSP.h
    ------------------------
    C++17, header-only hybrid vocal-removal post processor.

    Design:
      1. Optional neural vocal-stem subtraction path.
         A separator such as HT-Demucs supplies an aligned vocal stem.
      2. If no neural stem is supplied, a causal STFT center-vocal suppressor
         is used as a fallback. It works in the time-frequency domain instead
         of simply doing L-R phase cancellation.
      3. Existing project DSP primitives (when ProfessionalStereoWidenerDSP_v10.h
         is available) are reused for filtering, transient detection and
         true-peak limiting. The class does not duplicate those primitives.

    Important:
      - This file does NOT contain neural-network weights.
      - The fallback is a DSP vocal suppressor, not a substitute for a trained
        source-separation model.
      - Neural stem input must be sample-aligned with the mix.
*/

#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

#include "ProfessionalStereoWidenerDSP_v10.h"

namespace ProfessionalDSP {

class PremiumVocalRemoverDSP {
public:
    static constexpr std::size_t kFFTSize = 1024;
    static constexpr std::size_t kHop = 256;
    static constexpr std::size_t kBins = kFFTSize / 2 + 1;

    struct MeterSnapshot {
        float removalAmount = 0.0f;
        float centerSuppressionDb = 0.0f;
        float outputPeakDbTP = -120.0f;
        bool neuralStemActive = false;
    };

    void prepare(double sampleRate) noexcept {
        fs_ = static_cast<float>(std::max(8000.0, sampleRate));

        depth_.prepare(fs_, 25.0f, 0.90f);
        focus_.prepare(fs_, 30.0f, 1.0f);
        transientProtection_.prepare(fs_, 35.0f, 0.70f);
        stemGain_.prepare(fs_, 20.0f, 1.0f);
        dryWet_.prepare(fs_, 25.0f, 1.0f);
        outputTrim_.prepare(fs_, 25.0f, 1.0f);

        transient_.prepare(fs_);
        limiter_.prepare(fs_);
        limiter_.setCeilingDb(-1.0f);
        limiter_.setSafetyMarginDb(
            StereoLinkedTruePeakLimiter::kDefaultSafetyMarginDb);

        buildWindow();
        reset();
    }

    void reset() noexcept {
        inputCount_ = 0;
        frameCount_ = 0;

        mixInL_.assign(kFFTSize, 0.0f);
        mixInR_.assign(kFFTSize, 0.0f);
        stemInL_.assign(kFFTSize, 0.0f);
        stemInR_.assign(kFFTSize, 0.0f);

        fifoL_.assign(kFFTSize * 2, 0.0f);
        fifoR_.assign(kFFTSize * 2, 0.0f);
        fifoWrite_ = fifoRead_ = 0;
        fifoCount_ = 0;

        for (auto& x : prevMask_) x = 1.0f;
        for (auto& x : gainTmp_) x = 1.0f;

        std::fill(fftMid_.begin(), fftMid_.end(), std::complex<float>(0.0f, 0.0f));
        std::fill(fftSide_.begin(), fftSide_.end(), std::complex<float>(0.0f, 0.0f));
        overlapL_.fill(0.0f);
        overlapR_.fill(0.0f);
        overlapNorm_.fill(0.0f);
        outputQueueL_.fill(0.0f);
        outputQueueR_.fill(0.0f);
        outputQueueWrite_ = outputQueueRead_ = outputQueueCount_ = 0;

        transient_.reset();
        limiter_.reset();

        /* reset() means a deterministic new processing stream: clear all
           smoothed parameter state to the currently requested targets rather
           than carrying the previous song/block's ramp into the new stream. */
        depth_.setImmediate(depthTarget_.load(std::memory_order_relaxed));
        focus_.setImmediate(focusTarget_.load(std::memory_order_relaxed));
        transientProtection_.setImmediate(
            transientTarget_.load(std::memory_order_relaxed));
        stemGain_.setImmediate(stemGainTarget_.load(std::memory_order_relaxed));
        dryWet_.setImmediate(dryWetTarget_.load(std::memory_order_relaxed));
        outputTrim_.setImmediate(
            outputGainTarget_.load(std::memory_order_relaxed));

        meterRemoval_.store(0.0f, std::memory_order_relaxed);
        meterSuppressionDb_.store(0.0f, std::memory_order_relaxed);
        meterPeakDb_.store(-120.0f, std::memory_order_relaxed);

        lastOutputPeak_ = 0.0f;
        processedFrames_ = 0;
        stemActive_ = false;
        currentDepth_ = depthTarget_.load(std::memory_order_relaxed);
        currentFocus_ = focusTarget_.load(std::memory_order_relaxed);
        currentTransientProtection_ = transientTarget_.load(std::memory_order_relaxed);
        pendingStem_.clear();

        delayL_.fill(0.0f);
        delayR_.fill(0.0f);
        delayWrite_ = 0;
        delayCount_ = 0;

        transientFifo_.assign(kFFTSize * 2, 0.0f);

        neuralDelayL_.fill(0.0f);
        neuralDelayR_.fill(0.0f);
        neuralDelayWrite_ = 0;
        neuralDelayCount_ = 0;

        removalMeter_ = 0.0f;
        suppressionMeterDb_ = 0.0f;
    }

    void setDepth(float value) noexcept {
        depthTarget_.store(clampf(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setVocalFocus(float value) noexcept {
        focusTarget_.store(clampf(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setTransientProtection(float value) noexcept {
        transientTarget_.store(clampf(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setVocalStemGainDb(float db) noexcept {
        stemGainTarget_.store(dbToGain(clampf(db, -12.0f, 6.0f)), std::memory_order_relaxed);
    }

    void setDryWet(float value) noexcept {
        dryWetTarget_.store(clampf(value, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setOutputGainDb(float db) noexcept {
        outputGainTarget_.store(dbToGain(clampf(db, -12.0f, 6.0f)), std::memory_order_relaxed);
    }

    void setOutputCeilingDb(float db) noexcept {
        ceilingTarget_.store(clampf(db, -12.0f, 0.0f), std::memory_order_relaxed);
    }

    void setNeuralStemMode(bool enabled) noexcept {
        stemModeTarget_.store(enabled, std::memory_order_relaxed);
    }

    /*
        Process a block with an aligned vocal stem. If neuralStemMode is true,
        vocalL/R are subtracted from the mix and the result is delayed to the
        same fixed algorithmic latency as the STFT fallback. If false, the
        spectral fallback is used and vocalL/R may be nullptr.
    */
    void processBlock(const float* mixL,
                      const float* mixR,
                      const float* vocalL,
                      const float* vocalR,
                      float* outL,
                      float* outR,
                      std::size_t n) noexcept
    {
        if (!mixL || !mixR || !outL || !outR || n == 0) return;

        const float depthTarget = depthTarget_.load(std::memory_order_relaxed);
        const float focusTarget = focusTarget_.load(std::memory_order_relaxed);
        const float transientTarget = transientTarget_.load(std::memory_order_relaxed);
        const float stemGainTarget = stemGainTarget_.load(std::memory_order_relaxed);
        const float dryWetTarget = dryWetTarget_.load(std::memory_order_relaxed);
        const float outputGainTarget = outputGainTarget_.load(std::memory_order_relaxed);
        const float ceilingTarget = ceilingTarget_.load(std::memory_order_relaxed);

        depth_.setTarget(depthTarget);
        focus_.setTarget(focusTarget);
        transientProtection_.setTarget(transientTarget);
        stemGain_.setTarget(stemGainTarget);
        dryWet_.setTarget(dryWetTarget);
        outputTrim_.setTarget(outputGainTarget);

        const bool useStem = stemModeTarget_.load(std::memory_order_relaxed) &&
                             vocalL && vocalR;
        stemActive_.store(useStem, std::memory_order_relaxed);

        for (std::size_t i = 0; i < n; ++i) {
            const float d = dryWet_.process();
            const float outputGain = outputTrim_.process();
            const float stemGain = stemGain_.process();
            currentDepth_ = depth_.process();
            currentFocus_ = focus_.process();
            currentTransientProtection_ = transientProtection_.process();

            float L = safeSample(mixL[i]);
            float R = safeSample(mixR[i]);

            /* The transient detector is sample-rate DSP: advance it once per
               input sample, never once per STFT frame. The value is stored in
               the same FIFO as the audio so each STFT frame can use the
               transient information corresponding to its actual samples. */
            const float transientValue = transient_.process(L, R);

            if (useStem) {
                /*
                    Neural path: subtract the aligned vocal estimate.
                    The supplied stem is authoritative; explicit stem gain and
                    depth control its contribution.
                */
                const float vL = safeSample(vocalL[i]);
                const float vR = safeSample(vocalR[i]);

                /*
                    The separator already provides a source estimate. Do not
                    infer its gain from mix/stem correlation: correlated
                    accompaniment is common in music and would otherwise cause
                    over-subtraction. Stem gain is the explicit gain control.
                */
                const float removedL = L - stemGain * vL;
                const float removedR = R - stemGain * vR;

                /* Depth is the shared removal-strength control in neural mode.
                   Transient protection and focus remain fallback-STFT controls. */
                L += d * currentDepth_ * (removedL - L);
                R += d * currentDepth_ * (removedR - R);

                /* Keep neural mode at the same fixed algorithmic latency as
                   the causal STFT fallback. This avoids a mode-dependent
                   latency jump in the player. */
                alignNeuralPath(L, R);
                L = neuralDelayOutputL_;
                R = neuralDelayOutputR_;
            } else {
                float delayedDryL = 0.0f;
                float delayedDryR = 0.0f;
                delayAndGetStereo(L, R, delayedDryL, delayedDryR);
                fallbackQueue(L, R, transientValue);
                float wetL = 0.0f;
                float wetR = 0.0f;
                const bool ready = popOutput(wetL, wetR);
                if (!ready) {
                    wetL = 0.0f;
                    wetR = 0.0f;
                }
                L = delayedDryL + d * (wetL - delayedDryL);
                R = delayedDryR + d * (wetR - delayedDryR);
            }

            L *= outputGain;
            R *= outputGain;

            limiter_.setCeilingDb(ceilingTarget);
            limiter_.process(L, R);

            outL[i] = L;
            outR[i] = R;
        }

        const float peak = limiter_.getOutputTruePeak();
        lastOutputPeak_ = std::max(lastOutputPeak_ * 0.997f, peak);
        meterPeakDb_.store(gainToDb(std::max(lastOutputPeak_, 1.0e-12f)),
                           std::memory_order_relaxed);
        meterRemoval_.store(removalMeter_, std::memory_order_relaxed);
        meterSuppressionDb_.store(suppressionMeterDb_, std::memory_order_relaxed);
    }

    MeterSnapshot getMeters() const noexcept {
        MeterSnapshot m;
        m.removalAmount = meterRemoval_.load(std::memory_order_relaxed);
        m.centerSuppressionDb = meterSuppressionDb_.load(std::memory_order_relaxed);
        m.outputPeakDbTP = meterPeakDb_.load(std::memory_order_relaxed);
        m.neuralStemActive = stemActive_.load(std::memory_order_relaxed);
        return m;
    }

    std::size_t latencySamples() const noexcept {
        /* Both neural and fallback paths are deliberately aligned to the same
           causal STFT-equivalent delay. */
        return limiter_.latencySamples() + kFFTSize - 1;
    }

private:
    static float softKnee(float x, float lo, float hi) noexcept {
        return smoothstep(lo, hi, x);
    }

    void buildWindow() noexcept {
        /*
            Causal overlap-add needs a non-zero left edge: unlike a centred
            offline STFT, no future frame contributes to the very first sample
            of a causal frame. A Tukey-like window with a 25% endpoint keeps
            spectral leakage well below a rectangular window while remaining
            exactly reconstructible with the per-sample OLA normalization.
        */
        constexpr float alpha = 0.25f;
        const float halfRamp = 0.5f * alpha;
        for (std::size_t n = 0; n < kFFTSize; ++n) {
            const float x = static_cast<float>(n) /
                            static_cast<float>(kFFTSize - 1);
            if (x < halfRamp) {
                const float u = x / halfRamp;
                window_[n] = 0.25f + 0.75f *
                    0.5f * (1.0f - std::cos((kTwoPi * 0.5f) * u));
            } else if (x > 1.0f - halfRamp) {
                const float u = (1.0f - x) / halfRamp;
                window_[n] = 0.25f + 0.75f *
                    0.5f * (1.0f - std::cos((kTwoPi * 0.5f) * u));
            } else {
                window_[n] = 1.0f;
            }
        }
    }

    void fft(std::array<std::complex<float>, kFFTSize>& a,
             bool inverse) noexcept
    {
        for (std::size_t i = 1, j = 0; i < kFFTSize; ++i) {
            std::size_t bit = kFFTSize >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) std::swap(a[i], a[j]);
        }

        for (std::size_t len = 2; len <= kFFTSize; len <<= 1) {
            const float ang = (inverse ? 1.0f : -1.0f) *
                              kTwoPi / static_cast<float>(len);
            const std::complex<float> wlen(std::cos(ang), std::sin(ang));
            for (std::size_t i = 0; i < kFFTSize; i += len) {
                std::complex<float> w(1.0f, 0.0f);
                const std::size_t half = len >> 1;
                for (std::size_t j = 0; j < half; ++j) {
                    const auto u = a[i + j];
                    const auto v = a[i + j + half] * w;
                    a[i + j] = u + v;
                    a[i + j + half] = u - v;
                    w *= wlen;
                }
            }
        }

        if (inverse) {
            constexpr float invN = 1.0f / static_cast<float>(kFFTSize);
            for (auto& x : a) x *= invN;
        }
    }

    void delayAndGetStereo(float L, float R,
                           float& outL, float& outR) noexcept {
        constexpr std::size_t kDelay = kFFTSize - 1;
        delayL_[delayWrite_] = L;
        delayR_[delayWrite_] = R;

        if (delayCount_ >= kDelay) {
            const std::size_t read =
                (delayWrite_ + delayL_.size() - kDelay) % delayL_.size();
            outL = delayL_[read];
            outR = delayR_[read];
        } else {
            outL = 0.0f;
            outR = 0.0f;
        }

        if (delayCount_ < delayL_.size()) ++delayCount_;
        delayWrite_ = (delayWrite_ + 1) % delayL_.size();
    }

    void alignNeuralPath(float L, float R) noexcept {
        constexpr std::size_t kDelay = kFFTSize - 1;
        neuralDelayL_[neuralDelayWrite_] = L;
        neuralDelayR_[neuralDelayWrite_] = R;

        if (neuralDelayCount_ >= kDelay) {
            const std::size_t read =
                (neuralDelayWrite_ + neuralDelayL_.size() - kDelay) %
                neuralDelayL_.size();
            neuralDelayOutputL_ = neuralDelayL_[read];
            neuralDelayOutputR_ = neuralDelayR_[read];
        } else {
            neuralDelayOutputL_ = 0.0f;
            neuralDelayOutputR_ = 0.0f;
        }

        if (neuralDelayCount_ < neuralDelayL_.size()) ++neuralDelayCount_;
        neuralDelayWrite_ = (neuralDelayWrite_ + 1) % neuralDelayL_.size();
    }

    void fallbackQueue(float L, float R, float transientValue) noexcept {
        fifoPush(L, R, transientValue);
        while (fifoCount_ >= kFFTSize) {
            processFallbackFrame();
            fifoConsumeHop();
        }
    }

    void fifoPush(float L, float R, float transientValue) noexcept {
        fifoL_[fifoWrite_] = L;
        fifoR_[fifoWrite_] = R;
        transientFifo_[fifoWrite_] = transientValue;
        fifoWrite_ = (fifoWrite_ + 1) % fifoL_.size();
        if (fifoCount_ < fifoL_.size()) {
            ++fifoCount_;
        } else {
            fifoRead_ = (fifoRead_ + 1) % fifoL_.size();
        }
    }

    void fifoConsumeHop() noexcept {
        for (std::size_t i = 0; i < kHop && fifoCount_ > 0; ++i) {
            fifoRead_ = (fifoRead_ + 1) % fifoL_.size();
            --fifoCount_;
        }
    }

    bool popOutput(float& L, float& R) noexcept {
        if (outputQueueCount_ == 0) return false;
        L = outputQueueL_[outputQueueRead_];
        R = outputQueueR_[outputQueueRead_];
        outputQueueRead_ = (outputQueueRead_ + 1) % outputQueueL_.size();
        --outputQueueCount_;
        return true;
    }

    void queueOutput(float L, float R) noexcept {
        if (outputQueueCount_ >= outputQueueL_.size()) {
            outputQueueRead_ = (outputQueueRead_ + 1) % outputQueueL_.size();
            --outputQueueCount_;
        }
        outputQueueL_[outputQueueWrite_] = L;
        outputQueueR_[outputQueueWrite_] = R;
        outputQueueWrite_ = (outputQueueWrite_ + 1) % outputQueueL_.size();
        ++outputQueueCount_;
    }

    void processFallbackFrame() noexcept {
        if (fifoCount_ < kFFTSize) return;

        std::size_t p = fifoRead_;
        for (std::size_t n = 0; n < kFFTSize; ++n) {
            const float L = fifoL_[p];
            const float R = fifoR_[p];
            const float m = 0.5f * (L + R) * window_[n];
            const float s = 0.5f * (L - R) * window_[n];
            fftMid_[n] = {m, 0.0f};
            fftSide_[n] = {s, 0.0f};
            p = (p + 1) % fifoL_.size();
        }

        fft(fftMid_, false);
        fft(fftSide_, false);

        const float frameHz = static_cast<float>(fs_) / static_cast<float>(kFFTSize);

        /* Use the strongest transient actually present in this analysis
           frame. The detector itself has already been advanced once per
           input sample in processBlock(). */
        float transient = 0.0f;
        std::size_t tp = fifoRead_;
        for (std::size_t n = 0; n < kFFTSize; ++n) {
            transient = std::max(transient, transientFifo_[tp]);
            tp = (tp + 1) % fifoL_.size();
        }

        const float protection = 1.0f - currentTransientProtection_ * transient;
        const float depth = currentDepth_;
        const float focus = currentFocus_;

        double originalEnergy = 0.0;
        double removedEnergy = 0.0;

        for (std::size_t k = 0; k < kBins; ++k) {
            const float f = frameHz * static_cast<float>(k);
            const float magM = std::abs(fftMid_[k]);
            const float magS = std::abs(fftSide_[k]);
            const float eM = magM * magM;
            const float eS = magS * magS;
            const float centerRatio = eM / (eM + eS + 1.0e-12f);

            float band = 0.0f;
            if (f >= 90.0f && f <= 9000.0f) {
                const float lo = softKnee(f, 70.0f, 150.0f);
                const float hi = 1.0f - softKnee(f, 7000.0f, 10500.0f);
                band = lo * hi;
            }

            /* De-emphasize the bass and extreme top end by construction. */
            const float centerConfidence = softKnee(centerRatio, 0.55f, 0.92f);
            const float vocalLikelihood = band * (0.45f + 0.55f * focus);

            float target = 1.0f - depth * protection *
                           centerConfidence * vocalLikelihood;
            target = clampf(target, 0.12f, 1.0f);

            originalEnergy += static_cast<double>(eM);

            /* Frequency smoothing: 3-point local average. */
            gainTmp_[k] = target;
        }

        for (std::size_t k = 0; k < kBins; ++k) {
            const float left = gainTmp_[k > 0 ? k - 1 : k];
            const float mid = gainTmp_[k];
            const float right = gainTmp_[k + 1 < kBins ? k + 1 : k];
            const float target = (left + 2.0f * mid + right) * 0.25f;
            prevMask_[k] = 0.86f * prevMask_[k] + 0.14f * target;
        }

        /* Apply the smoothed mask and measure the ACTUAL power reduction.
           This is done before the inverse FFT, while fftMid_ still contains
           the original spectral energy. */
        for (std::size_t k = 0; k < kBins; ++k) {
            const float eM = std::norm(fftMid_[k]);
            const float g = clampf(prevMask_[k], 0.0f, 1.0f);
            const float energyReduction =
                clampf(1.0f - g * g, 0.0f, 1.0f);
            removedEnergy += static_cast<double>(eM) *
                             static_cast<double>(energyReduction);

            fftMid_[k] *= g;
            if (k != 0 && k != kFFTSize / 2)
                fftMid_[kFFTSize - k] *= g;
        }

        fft(fftMid_, true);
        fft(fftSide_, true);

        /* Output the current analysis frame. Because the analysis uses
           M/S signals scaled by 0.5, synthesis is simply (M +/- S) * w.
           The OLA normalizer makes the result unity-gain despite the 75%
           overlap and the chosen analysis/synthesis window. */
        std::array<float, kFFTSize> frameL{};
        std::array<float, kFFTSize> frameR{};
        for (std::size_t n = 0; n < kFFTSize; ++n) {
            const float mid = fftMid_[n].real();
            const float side = fftSide_[n].real();
            const float w = window_[n];
            frameL[n] = (mid + side) * w;
            frameR[n] = (mid - side) * w;
            overlapL_[n] += frameL[n];
            overlapR_[n] += frameR[n];
            overlapNorm_[n] += w * w;
        }

        for (std::size_t n = 0; n < kHop; ++n) {
            const float norm = std::max(overlapNorm_[n], 1.0e-8f);
            queueOutput(overlapL_[n] / norm, overlapR_[n] / norm);
        }

        for (std::size_t n = 0; n < kFFTSize - kHop; ++n) {
            overlapL_[n] = overlapL_[n + kHop];
            overlapR_[n] = overlapR_[n + kHop];
            overlapNorm_[n] = overlapNorm_[n + kHop];
        }
        for (std::size_t n = kFFTSize - kHop; n < kFFTSize; ++n) {
            overlapL_[n] = 0.0f;
            overlapR_[n] = 0.0f;
            overlapNorm_[n] = 0.0f;
        }

        const float removal = clampf(
            static_cast<float>(removedEnergy /
                                std::max(originalEnergy, 1.0e-20)),
            0.0f, 1.0f);
        /* removal is a power fraction, so convert remaining POWER to dB. */
        const float db = -10.0f *
            std::log10(std::max(1.0f - removal, 1.0e-6f));

        removalMeter_ = 0.90f * removalMeter_ + 0.10f * removal;
        suppressionMeterDb_ = 0.90f * suppressionMeterDb_ + 0.10f * db;

        ++processedFrames_;
        (void)frameCount_;
        (void)inputCount_;
    }

    float fs_ = 48000.0f;

    SmoothedValue depth_;
    SmoothedValue focus_;
    SmoothedValue transientProtection_;
    SmoothedValue stemGain_;
    SmoothedValue dryWet_;
    SmoothedValue outputTrim_;

    StereoTransientDetector transient_;
    StereoLinkedTruePeakLimiter limiter_;

    std::atomic<float> depthTarget_{0.90f};
    std::atomic<float> focusTarget_{1.0f};
    std::atomic<float> transientTarget_{0.70f};
    std::atomic<float> stemGainTarget_{1.0f};
    std::atomic<float> dryWetTarget_{1.0f};
    std::atomic<float> outputGainTarget_{1.0f};
    std::atomic<float> ceilingTarget_{-1.0f};
    std::atomic<bool> stemModeTarget_{false};
    std::atomic<bool> stemActive_{false};

    float currentDepth_ = 0.90f;
    float currentFocus_ = 1.0f;
    float currentTransientProtection_ = 0.70f;

    std::array<float, kFFTSize> window_{};
    std::array<std::complex<float>, kFFTSize> fftMid_{};
    std::array<std::complex<float>, kFFTSize> fftSide_{};

    std::array<float, kBins> prevMask_{};
    std::array<float, kBins> gainTmp_{};

    std::vector<float> mixInL_, mixInR_, stemInL_, stemInR_;
    std::vector<float> fifoL_, fifoR_, transientFifo_;
    std::size_t fifoWrite_ = 0;
    std::size_t fifoRead_ = 0;
    std::size_t fifoCount_ = 0;

    std::array<float, kFFTSize> overlapL_{};
    std::array<float, kFFTSize> overlapR_{};
    std::array<float, kFFTSize> overlapNorm_{};

    std::array<float, 2048> delayL_{};
    std::array<float, 2048> delayR_{};
    std::size_t delayWrite_ = 0;
    std::size_t delayCount_ = 0;

    std::array<float, 2048> neuralDelayL_{};
    std::array<float, 2048> neuralDelayR_{};
    std::size_t neuralDelayWrite_ = 0;
    std::size_t neuralDelayCount_ = 0;
    float neuralDelayOutputL_ = 0.0f;
    float neuralDelayOutputR_ = 0.0f;

    std::array<float, kFFTSize * 2> outputQueueL_{};
    std::array<float, kFFTSize * 2> outputQueueR_{};
    std::size_t outputQueueWrite_ = 0;
    std::size_t outputQueueRead_ = 0;
    std::size_t outputQueueCount_ = 0;

    std::size_t inputCount_ = 0;
    std::size_t frameCount_ = 0;
    std::size_t processedFrames_ = 0;

    float removalMeter_ = 0.0f;
    float suppressionMeterDb_ = 0.0f;
    float lastOutputPeak_ = 0.0f;

    std::vector<float> pendingStem_;

    std::atomic<float> meterRemoval_{0.0f};
    std::atomic<float> meterSuppressionDb_{0.0f};
    std::atomic<float> meterPeakDb_{-120.0f};
};

} // namespace ProfessionalDSP
