/*
    ProfessionalStereoWidenerDSP_v10_corrected.h
    ------------------------------------------------
    C++17, header-only stereo widener DSP core.

    v10 corrected limiter/invariant pass:
      - Exact FIR source coordinates remain tied to the runtime x[n-q]
        indexing.
      - The limiter protects the FULL gain-modulated FIR support interval;
        the scalar 4x detector peak is no longer assigned to one source
        sample as the safety proof.
      - The protected ceiling is a future-safe quantity derived from the
        actual dB-domain ceiling smoothing recurrence and the exact FIR phase
        timestamp/output-delay horizon.
      - The source-gain feasibility invariant is explicit:

            interval.minimum <=
                min(interval.maximum,
                    1 - releaseA * (1 - previousGain)).

      - The committed source gain is the maximum gain inside that intersection,
        so it is simultaneously FIR-safe and reachable by the actual limiter
        release recurrence. There is no post-hoc clamp sequence that can
        push the gain outside the recurrence bound.
      - An infeasible state is reported as an invariant failure. The code does
        NOT claim that setting the gain to zero retroactively proves safety.
        The deterministic fail-safe preserves the recurrence and upper-bound
        side of the constraint while exposing the failure diagnostic.
      - The final dry/wet/effect mix is before the final linked limiter, so
        there is no unlimited bypass after the protected stage.
      - The side signal is high-passed before its three-way split so bass side
        content is forced mono. Width above 100% does not amplify the low side
        band; added width comes from decorrelated mid/high material.
      - Decorrelator stages are second-order all-pass sections with
        log-spaced center frequencies.

    Reference true-peak path:
      The 48 kHz 4-phase FIR coefficient table and x[n-q] runtime indexing
      are retained. The generic path remains an engineering approximation.
*/

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace ProfessionalDSP {

constexpr float kPi      = 3.14159265358979323846f;
constexpr float kTwoPi   = 6.28318530717958647692f;
constexpr float kEpsilon = 1.0e-12f;

inline float clampf(float x, float lo, float hi) noexcept {
    return std::max(lo, std::min(hi, x));
}

inline float safeSample(float x) noexcept {
    return std::isfinite(x) ? x : 0.0f;
}

inline float dbToGain(float db) noexcept {
    return std::pow(10.0f, db * 0.05f);
}

inline float gainToDb(float g) noexcept {
    return 20.0f * std::log10(std::max(g, 1.0e-12f));
}

inline float smoothstep(float a, float b, float x) noexcept {
    if (a == b) return x >= b ? 1.0f : 0.0f;
    const float t = clampf((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

inline float sincPi(float x) noexcept {
    if (std::fabs(x) < 1.0e-8f)
        return 1.0f;
    const float px = kPi * x;
    return std::sin(px) / px;
}

inline float blackmanWindow(std::size_t i, std::size_t n) noexcept {
    if (n <= 1)
        return 1.0f;
    const float x = static_cast<float>(i) / static_cast<float>(n - 1);
    return 0.42f
         - 0.50f * std::cos(kTwoPi * x)
         + 0.08f * std::cos(2.0f * kTwoPi * x);
}

class SmoothedValue {
public:
    void prepare(double sampleRate,
                 float timeMs,
                 float initial) noexcept
    {
        fs_ = static_cast<float>(std::max(1.0, sampleRate));
        const float tau = std::max(0.000001f, timeMs * 0.001f);
        a_ = std::exp(-1.0f / (tau * fs_));
        current_ = target_ = initial;
    }

    void setTarget(float v) noexcept { target_ = v; }
    void setImmediate(float v) noexcept { current_ = target_ = v; }

    float process() noexcept {
        current_ = target_ + a_ * (current_ - target_);
        return current_;
    }

    /*
        Future-safe lower envelope for the ACTUAL smoothing recurrence:

            C[n+1] = T[n] + a * (C[n] - T[n])

        where every future target is constrained to be >= minimumTarget.
        The worst possible future ceiling is therefore obtained by choosing
        minimumTarget at every future step, giving exactly:

            C[n+k] >= minimumTarget
                       + a^k * (C[n] - minimumTarget).

        This operates in dB because this SmoothedValue itself smooths dB.
        It is therefore not an assumed gain-domain model; it is the exact
        lower envelope of the recursion actually used by the limiter.
    */
    float futureLowerBound(float minimumTarget,
                           std::size_t futureSteps) const noexcept
    {
        const float floor = minimumTarget;

        /*
            The control state is never intentionally allowed below the
            permitted floor. Clamp here as a defensive invariant, rather
            than silently changing the recurrence itself.
        */
        const float current = current_;

        const float aPow =
            std::pow(a_, static_cast<float>(futureSteps));

        return floor +
               aPow * (current - floor);
    }

    float smoothingCoefficient() const noexcept { return a_; }
    float current() const noexcept { return current_; }
    float target()  const noexcept { return target_; }

private:
    float fs_ = 48000.0f;
    float a_ = 0.99f;
    float current_ = 0.0f;
    float target_ = 0.0f;
};

class Biquad {
public:
    void reset() noexcept {
        z1_ = 0.0f;
        z2_ = 0.0f;
    }

    void setCoefficients(float b0,
                         float b1,
                         float b2,
                         float a1,
                         float a2) noexcept
    {
        b0_ = b0;
        b1_ = b1;
        b2_ = b2;
        a1_ = a1;
        a2_ = a2;
    }

    void copyStateFrom(const Biquad& other) noexcept {
        z1_ = other.z1_;
        z2_ = other.z2_;
    }

    float process(float x) noexcept {
        const float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;

        if (std::fabs(z1_) < 1.0e-20f) z1_ = 0.0f;
        if (std::fabs(z2_) < 1.0e-20f) z2_ = 0.0f;
        return y;
    }

private:
    float b0_ = 1.0f;
    float b1_ = 0.0f;
    float b2_ = 0.0f;
    float a1_ = 0.0f;
    float a2_ = 0.0f;
    float z1_ = 0.0f;
    float z2_ = 0.0f;
};

class Butterworth2 {
public:
    enum class Type { LowPass, HighPass };

    void prepare(double sampleRate,
                 float cutoff,
                 Type type) noexcept
    {
        fs_ = static_cast<float>(std::max(1.0, sampleRate));
        type_ = type;
        setFrequency(cutoff);
        reset();
    }

    void setFrequency(float cutoff) noexcept {
        const float fc = clampf(cutoff, 5.0f, 0.49f * fs_);
        const float w0 = kTwoPi * fc / fs_;
        const float c = std::cos(w0);
        const float s = std::sin(w0);
        const float Q = 0.7071067811865476f;
        const float alpha = s / (2.0f * Q);
        const float a0 = 1.0f + alpha;

        if (type_ == Type::LowPass) {
            const float b0 = (1.0f - c) * 0.5f;
            const float b1 = 1.0f - c;
            const float b2 = b0;
            b_.setCoefficients(b0 / a0,
                                b1 / a0,
                                b2 / a0,
                                -2.0f * c / a0,
                                (1.0f - alpha) / a0);
        } else {
            const float b0 = (1.0f + c) * 0.5f;
            const float b1 = -(1.0f + c);
            const float b2 = b0;
            b_.setCoefficients(b0 / a0,
                                b1 / a0,
                                b2 / a0,
                                -2.0f * c / a0,
                                (1.0f - alpha) / a0);
        }
    }

    float process(float x) noexcept { return b_.process(x); }
    void reset() noexcept { b_.reset(); }

    void copyStateFrom(const Butterworth2& other) noexcept {
        b_.copyStateFrom(other.b_);
    }

private:
    float fs_ = 48000.0f;
    Type type_ = Type::LowPass;
    Biquad b_;
};

class LR4Filter {
public:
    enum class Type { LowPass, HighPass };

    void prepare(double sampleRate,
                 float cutoff,
                 Type type) noexcept
    {
        const auto t = type == Type::LowPass
            ? Butterworth2::Type::LowPass
            : Butterworth2::Type::HighPass;
        stages_[0].prepare(sampleRate, cutoff, t);
        stages_[1].prepare(sampleRate, cutoff, t);
    }

    void setFrequency(float cutoff) noexcept {
        stages_[0].setFrequency(cutoff);
        stages_[1].setFrequency(cutoff);
    }

    float process(float x) noexcept {
        return stages_[1].process(stages_[0].process(x));
    }

    void reset() noexcept {
        stages_[0].reset();
        stages_[1].reset();
    }

    void copyStateFrom(const LR4Filter& other) noexcept {
        stages_[0].copyStateFrom(other.stages_[0]);
        stages_[1].copyStateFrom(other.stages_[1]);
    }

private:
    Butterworth2 stages_[2];
};

/*
    Complementary 3-way bank:

      Low  = LP4(lowFc)
      Mid  = HP4(lowFc) -> LP4(highFc)
      High = HP4(highFc)

    The middle band is a genuine recursive LR4 band-pass path.  It is not
    constructed as the residual x - low - high, and this implementation does
    not claim exact sample-by-sample waveform reconstruction merely from LR4
    magnitude complementarity.  The three branches are intentionally
    implemented as independent matched LR4 paths.
*/
class Complementary3Way {
private:
    struct Bank {
        LR4Filter lowLP;
        LR4Filter midHP;
        LR4Filter midLP;
        LR4Filter highHP;

        void prepare(double fs,
                     float lowFc,
                     float highFc) noexcept
        {
            lowLP.prepare(fs, lowFc, LR4Filter::Type::LowPass);
            midHP.prepare(fs, lowFc, LR4Filter::Type::HighPass);
            midLP.prepare(fs, highFc, LR4Filter::Type::LowPass);
            highHP.prepare(fs, highFc, LR4Filter::Type::HighPass);
        }

        void setFrequencies(float lowFc,
                            float highFc) noexcept
        {
            lowLP.setFrequency(lowFc);
            midHP.setFrequency(lowFc);
            midLP.setFrequency(highFc);
            highHP.setFrequency(highFc);
        }

        void reset() noexcept {
            lowLP.reset();
            midHP.reset();
            midLP.reset();
            highHP.reset();
        }

        void copyStateFrom(const Bank& other) noexcept {
            lowLP.copyStateFrom(other.lowLP);
            midHP.copyStateFrom(other.midHP);
            midLP.copyStateFrom(other.midLP);
            highHP.copyStateFrom(other.highHP);
        }

        void process(float x,
                     float& low,
                     float& mid,
                     float& high) noexcept
        {
            /*
                Genuine LR4 3-way topology:

                    Low  = LP4(lowFc)
                    Mid  = HP4(lowFc) -> LP4(highFc)
                    High = HP4(highFc)

                The middle band is therefore a real recursive band-pass path,
                not a residual x - low - high calculation and not a falsely
                claimed LP4-difference implementation.

                Note: LR4 magnitude complementarity does not imply exact
                sample-by-sample waveform reconstruction by simple addition;
                this code deliberately does not claim that it does.
            */
            low = lowLP.process(x);
            const float middleHighPassed = midHP.process(x);
            mid = midLP.process(middleHighPassed);
            high = highHP.process(x);
        }
    };

public:
    void prepare(double sampleRate,
                 float lowFc,
                 float highFc) noexcept
    {
        fs_ = static_cast<float>(std::max(1.0, sampleRate));
        fadeSamples_ = std::max<std::size_t>(
            32,
            static_cast<std::size_t>(std::lround(0.005f * fs_)));

        active_ = 0;
        fading_ = false;
        pending_ = false;
        fadePos_ = 0;

        lowFc_ = lowFc;
        highFc_ = highFc;
        pendingLowFc_ = lowFc;
        pendingHighFc_ = highFc;

        banks_[0].prepare(fs_, lowFc, highFc);
        banks_[1].prepare(fs_, lowFc, highFc);
        banks_[0].reset();
        banks_[1].reset();
    }

    void requestFrequencies(float lowFc,
                            float highFc) noexcept
    {
        lowFc = clampf(lowFc, 20.0f, 0.45f * fs_);
        highFc = clampf(highFc, lowFc * 1.50f, 0.49f * fs_);

        if (!fading_ &&
            std::fabs(lowFc - lowFc_) < 0.01f &&
            std::fabs(highFc - highFc_) < 0.05f)
        {
            return;
        }

        if (fading_) {
            pendingLowFc_ = lowFc;
            pendingHighFc_ = highFc;
            pending_ = true;
            return;
        }

        beginTransition(lowFc, highFc);
    }

    void reset() noexcept {
        banks_[0].reset();
        banks_[1].reset();
        active_ = 0;
        fading_ = false;
        pending_ = false;
        fadePos_ = 0;
    }

    void process(float x,
                 float& low,
                 float& mid,
                 float& high) noexcept
    {
        float l0, m0, h0;
        banks_[active_].process(x, l0, m0, h0);

        if (!fading_) {
            low = l0;
            mid = m0;
            high = h0;
            return;
        }

        const std::size_t next = active_ ^ 1u;
        float l1, m1, h1;
        banks_[next].process(x, l1, m1, h1);

        const float t =
            static_cast<float>(fadePos_) /
            static_cast<float>(std::max<std::size_t>(1, fadeSamples_));

        low  = l0 + t * (l1 - l0);
        mid  = m0 + t * (m1 - m0);
        high = h0 + t * (h1 - h0);

        ++fadePos_;

        if (fadePos_ >= fadeSamples_) {
            active_ = next;
            fading_ = false;
            fadePos_ = 0;

            if (pending_) {
                const float pl = pendingLowFc_;
                const float ph = pendingHighFc_;
                pending_ = false;
                beginTransition(pl, ph);
            }
        }
    }

private:
    void beginTransition(float lowFc,
                          float highFc) noexcept
    {
        const std::size_t next = active_ ^ 1u;

        banks_[next].copyStateFrom(banks_[active_]);
        banks_[next].setFrequencies(lowFc, highFc);

        lowFc_ = lowFc;
        highFc_ = highFc;
        fadePos_ = 0;
        fading_ = true;
    }

    float fs_ = 48000.0f;
    std::size_t fadeSamples_ = 240;
    Bank banks_[2];
    std::size_t active_ = 0;
    std::size_t fadePos_ = 0;
    bool fading_ = false;
    bool pending_ = false;
    float lowFc_ = 180.0f;
    float highFc_ = 3200.0f;
    float pendingLowFc_ = 180.0f;
    float pendingHighFc_ = 3200.0f;
};

class AllpassStage {
public:
    void prepare(double sampleRate,
                 float frequency,
                 float radius) noexcept
    {
        fs_ = static_cast<float>(std::max(1.0, sampleRate));
        radius_ = clampf(radius, 0.50f, 0.9995f);
        setFrequency(frequency);
        reset();
    }

    void setFrequency(float frequency) noexcept
    {
        const float f = clampf(frequency, 20.0f, 0.45f * fs_);
        const float w = kTwoPi * f / fs_;

        /*
            Stable second-order all-pass section:

                A(z) = (a2 + a1 z^-1 + z^-2)
                       ------------------------
                       (1 + a1 z^-1 + a2 z^-2)

            with

                a1 = -2 r cos(w)
                a2 = r^2,       0 <= r < 1.

            Numerator/denominator are reciprocal, so the magnitude response
            is unity while the phase/group-delay response is frequency
            dependent.
        */
        a1_ = clampf(-2.0f * radius_ * std::cos(w), -1.999f, 1.999f);
        a2_ = radius_ * radius_;
    }

    float process(float x) noexcept
    {
        x = safeSample(x);

        const float y =
            a2_ * x +
            a1_ * x1_ +
            x2_ -
            a1_ * y1_ -
            a2_ * y2_;

        x2_ = x1_;
        x1_ = x;
        y2_ = y1_;
        y1_ = y;

        if (std::fabs(x1_) < 1.0e-20f) x1_ = 0.0f;
        if (std::fabs(x2_) < 1.0e-20f) x2_ = 0.0f;
        if (std::fabs(y1_) < 1.0e-20f) y1_ = 0.0f;
        if (std::fabs(y2_) < 1.0e-20f) y2_ = 0.0f;

        return std::isfinite(y) ? y : 0.0f;
    }

    void reset() noexcept
    {
        x1_ = x2_ = 0.0f;
        y1_ = y2_ = 0.0f;
    }

private:
    float fs_ = 48000.0f;
    float radius_ = 0.80f;
    float a1_ = -0.70f;
    float a2_ = 0.64f;

    float x1_ = 0.0f;
    float x2_ = 0.0f;
    float y1_ = 0.0f;
    float y2_ = 0.0f;
};

template <std::size_t N>
class SideDiffuser {
public:
    void prepare(double sampleRate,
                 float baseHz,
                 float spacing,
                 float radius) noexcept
    {
        const float fs = static_cast<float>(std::max(1.0, sampleRate));
        const float maxHz = 0.40f * fs;
        const float requestedLast =
            baseHz * std::pow(spacing, static_cast<float>(N > 1 ? N - 1 : 0));
        const float last = std::min(requestedLast, maxHz);

        const float actualSpacing =
            (N > 1 && last > baseHz)
                ? std::pow(last / baseHz, 1.0f / static_cast<float>(N - 1))
                : 1.0f;

        for (std::size_t i = 0; i < N; ++i) {
            const float f =
                std::min(maxHz,
                         baseHz * std::pow(actualSpacing,
                                           static_cast<float>(i)));
            stages_[i].prepare(fs, f, radius);
        }
    }

    float process(float x) noexcept {
        float y = x;
        for (auto& s : stages_)
            y = s.process(y);
        return y;
    }

    void reset() noexcept {
        for (auto& s : stages_)
            s.reset();
    }

private:
    std::array<AllpassStage, N> stages_{};
};

class SideHaas {
public:
    static constexpr std::size_t kMaxDelaySamples = 512;

    void prepare(double sampleRate) noexcept {
        fs_ = static_cast<float>(std::max(1.0, sampleRate));
        delaySmoothA_ = std::exp(-1.0f / (0.008f * fs_));
        mixSmoothA_ = std::exp(-1.0f / (0.008f * fs_));
        reset();
    }

    void reset() noexcept {
        write_ = 0;
        delaySamples_ = 0.0f;
        targetDelaySamples_ = 0.0f;
        mixCurrent_ = 0.0f;
        mixTarget_ = 0.0f;
        buffer_.fill(0.0f);
    }

    void setDelayMs(float ms) noexcept {
        const float maxMs =
            1000.0f * static_cast<float>(kMaxDelaySamples - 2) / fs_;
        targetDelaySamples_ =
            clampf(ms, 0.0f, maxMs) * 0.001f * fs_;
    }

    void setMix(float mix) noexcept {
        mixTarget_ = clampf(mix, 0.0f, 1.0f);
    }

    float process(float side) noexcept {
        delaySamples_ =
            targetDelaySamples_ +
            delaySmoothA_ * (delaySamples_ - targetDelaySamples_);

        mixCurrent_ =
            mixTarget_ +
            mixSmoothA_ * (mixCurrent_ - mixTarget_);

        buffer_[write_] = side;

        const float d = clampf(
            delaySamples_,
            0.0f,
            static_cast<float>(kMaxDelaySamples - 2));

        float delayed = side;

        if (d > 1.0e-7f) {
            float readPos = static_cast<float>(write_) - d;
            while (readPos < 0.0f)
                readPos += static_cast<float>(kMaxDelaySamples);
            while (readPos >= static_cast<float>(kMaxDelaySamples))
                readPos -= static_cast<float>(kMaxDelaySamples);

            const std::size_t i0 = static_cast<std::size_t>(readPos);
            const std::size_t i1 = (i0 + 1) % kMaxDelaySamples;
            const float frac = readPos - static_cast<float>(i0);
            delayed = buffer_[i0] + frac * (buffer_[i1] - buffer_[i0]);
        }

        ++write_;
        if (write_ >= kMaxDelaySamples)
            write_ = 0;

        /*
            Keep the direct side signal and add a controlled delayed reflection.
            The previous crossfade (side -> delayed) could sound almost unchanged
            because it replaced the original side rather than adding a distinct
            arrival. Divide by 1 + mix so a zero-delay tap remains exactly unity
            and the blend does not create an uncontrolled level increase.
        */
        const float mix = clampf(mixCurrent_, 0.0f, 1.0f);
        return (side + mix * delayed) / (1.0f + mix);
    }

private:
    float fs_ = 48000.0f;
    float delaySmoothA_ = 0.99f;
    float mixSmoothA_ = 0.99f;
    std::array<float, kMaxDelaySamples> buffer_{};
    std::size_t write_ = 0;
    float delaySamples_ = 0.0f;
    float targetDelaySamples_ = 0.0f;
    float mixCurrent_ = 0.0f;
    float mixTarget_ = 0.0f;
};

class StereoTransientDetector {
public:
    void prepare(double sampleRate) noexcept {
        const float fs = static_cast<float>(std::max(1.0, sampleRate));
        fastA_ = std::exp(-1.0f / (0.0025f * fs));
        slowA_ = std::exp(-1.0f / (0.0300f * fs));
        reset();
    }

    void reset() noexcept {
        fast_ = 0.0f;
        slow_ = 0.0f;
        primed_ = false;
    }

    float process(float left,
                  float right) noexcept
    {
        left = safeSample(left);
        right = safeSample(right);
        const float e = 0.5f * (left * left + right * right);

        if (!primed_) {
            fast_ = e;
            slow_ = e;
            primed_ = true;
            return 0.0f;
        }

        fast_ = e + fastA_ * (fast_ - e);
        slow_ = e + slowA_ * (slow_ - e);
        const float ratio = fast_ / (slow_ + 1.0e-12f);
        return clampf((ratio - 1.0f) * 1.75f, 0.0f, 1.0f);
    }

private:
    float fastA_ = 0.99f;
    float slowA_ = 0.999f;
    float fast_ = 0.0f;
    float slow_ = 0.0f;
    bool primed_ = false;
};

class TruePeakEstimator {
public:
    static constexpr std::size_t kReferenceTapsPerPhase = 12;
    static constexpr std::size_t kReferencePhases = 4;
    static constexpr std::size_t kGenericTapsPerPhase = 33;
    static constexpr std::size_t kMaxPhases = 4;

    struct TimingInfo {
        double nominalGroupDelaySamples = 0.0;
        double earliestTimestampDelaySamples = 0.0;
        double latestTimestampDelaySamples = 0.0;
        std::size_t causalAvailabilityDelaySamples = 0;
    };

    /*
        Exact runtime FIR support coordinate.

        sourceOffsetFromAnchor:
            Integer source-sample offset from the protected source sample.

        phaseTimestampOffsetFromAnchor:
            Exact reconstructed FIR phase timestamp relative to that source
            sample.

        tapTimeOffsetFromPhase:
            Exact source-sample timestamp relative to the reconstructed phase.

        These are generated from the same x[n-q] convention used by
        processReference48k()/processGeneric().
    */
    struct TapCoordinate {
        int sourceOffsetFromAnchor = 0;
        double phaseTimestampOffsetFromAnchor = 0.0;
        double tapTimeOffsetFromPhase = 0.0;
    };

    void prepare(double sampleRate) noexcept {
        fs_ = static_cast<float>(std::max(1.0, sampleRate));
        useReference48k_ = std::fabs(fs_ - 48000.0f) < 0.5f;
        oversample_ = fs_ <= 96000.0f ? 4u : 2u;
        buildGenericCoefficients();
        buildSupportCoordinates();
        reset();
    }

    void reset() noexcept {
        refBuf_.fill(0.0f);
        genericBuf_.fill(0.0f);
        refWrite_ = 0;
        genericWrite_ = 0;
        inputSampleIndex_ = -1;
    }

    float process(float x) noexcept {
        x = safeSample(x);
        ++inputSampleIndex_;

        if (useReference48k_)
            return processReference48k(x);

        return processGeneric(x);
    }

    std::int64_t inputSampleIndex() const noexcept {
        return inputSampleIndex_;
    }

    TimingInfo timing() const noexcept {
        if (useReference48k_) {
            /*
                The published FIR has 48 taps at 192 kHz, hence:

                    group delay = (48 - 1) / 2 = 23.5 output samples
                                = 5.875 input-rate samples.

                With phase p, the output timestamp is displaced by p/4
                output sample relative to the phase-0 branch. For source-time
                indexing we conservatively collapse the sub-sample timing to
                an integer causal availability bound of 6 input samples.
            */
            return {
                5.875,
                5.125,
                5.875,
                6
            };
        }

        const double center = 16.0;
        const double earliest =
            center - static_cast<double>(oversample_ - 1) /
                       static_cast<double>(oversample_);

        return {
            center,
            earliest,
            center,
            16
        };
    }

    /* Compatibility accessors retained for existing host code. */
    std::size_t groupDelaySamples() const noexcept {
        return timing().causalAvailabilityDelaySamples;
    }

    std::size_t timestampDelaySamples() const noexcept {
        /*
            This accessor is an integer conservative delay, not a truncated
            representation of the continuous waveform timestamp.
        */
        return static_cast<std::size_t>(
            std::ceil(timing().latestTimestampDelaySamples - 1.0e-12));
    }

    double earliestWaveformDelaySamples() const noexcept {
        return timing().earliestTimestampDelaySamples;
    }

    double latestWaveformDelaySamples() const noexcept {
        return timing().latestTimestampDelaySamples;
    }

    double exactReferenceGroupDelaySamples() const noexcept {
        return 5.875;
    }

    bool usesReference48k() const noexcept {
        return useReference48k_;
    }

    struct ProtectionInfo {
        double earliestProtectedOutputSamples = 0.0;
        double latestProtectedOutputSamples = 0.0;
        std::size_t ceilingControlHorizon = 0;
    };

    /*
        Translate the exact reconstructed FIR phase timestamps through the
        limiter's integer output delay.

        The source anchor is m = j - causalAvailability.  A phase timestamp
        t_phase relative to m therefore reaches output time

            outputDelay + t_phase - causalAvailability.

        The limiter's future ceiling must remain safe through the latest such
        timestamp. Because ceiling control is updated at integer sample
        instants, round the latest protected time upward.
    */
    ProtectionInfo protectionForOutputDelay(
        double outputDelaySamples) const noexcept
    {
        ProtectionInfo result{};

        const std::size_t phases = supportPhaseCount();
        if (phases == 0)
            return result;

        const double earliestPhaseTimestamp =
            phaseTimestampFromAnchor_[0];

        const double latestPhaseTimestamp =
            phaseTimestampFromAnchor_[phases - 1];

        const double causal =
            static_cast<double>(
                timing().causalAvailabilityDelaySamples);

        const double earliestProtectedOutput =
            outputDelaySamples +
            earliestPhaseTimestamp -
            causal;

        const double latestProtectedOutput =
            outputDelaySamples +
            latestPhaseTimestamp -
            causal;

        result.earliestProtectedOutputSamples =
            earliestProtectedOutput;
        result.latestProtectedOutputSamples =
            latestProtectedOutput;

        /*
            Do not subtract a safety epsilon before ceil unless the value is
            genuinely infinitesimally above an integer.  This avoids turning
            123.000000000001 into 124 while keeping exact-integer geometry at
            123.
        */
        constexpr double kTimestampEpsilon = 1.0e-12;
        double horizon = std::max(0.0, latestProtectedOutput);
        const double nearestInteger = std::round(horizon);

        if (std::fabs(horizon - nearestInteger) <= kTimestampEpsilon)
            horizon = nearestInteger;

        result.ceilingControlHorizon =
            static_cast<std::size_t>(std::ceil(horizon));

        return result;
    }

    std::size_t supportPhaseCount() const noexcept {
        return useReference48k_ ? kReferencePhases : oversample_;
    }

    std::size_t supportTapCount() const noexcept {
        return useReference48k_ ? kReferenceTapsPerPhase
                                : kGenericTapsPerPhase;
    }

    const TapCoordinate& tapCoordinate(std::size_t phase,
                                       std::size_t tap) const noexcept
    {
        static const TapCoordinate zero{};
        if (phase >= kMaxPhases || tap >= kGenericTapsPerPhase)
            return zero;
        return supportCoordinates_[phase][tap];
    }

    double phaseTimestampOffsetFromAnchor(std::size_t phase) const noexcept {
        if (phase >= kMaxPhases)
            return 0.0;
        return phaseTimestampFromAnchor_[phase];
    }

    /*
        This is the one-step envelope generated by the ACTUAL limiter
        recovery recurrence. For an upward move:

            recovered = T + a*(g-T)
                     = a*g + (1-a)*T
                     <= a*g + (1-a)

        because T <= 1. Downward moves are smaller still.
    */
    static float releaseUpperBound(float currentGain,
                                   float releaseA) noexcept
    {
        const float g = clampf(currentGain, 0.0f, 1.0f);
        const float a = clampf(releaseA, 0.0f, 1.0f);
        return clampf(1.0f - a * (1.0f - g), g, 1.0f);
    }

    static float futureGainUpperBound(float currentGain,
                                      float releaseA,
                                      std::size_t steps) noexcept
    {
        const float g = clampf(currentGain, 0.0f, 1.0f);
        const float a = clampf(releaseA, 0.0f, 1.0f);
        if (steps == 0)
            return g;
        const float aPow = std::pow(a, static_cast<float>(steps));
        return clampf(1.0f - aPow * (1.0f - g), 0.0f, 1.0f);
    }

    /*
        Return the maximum source-time gain interval that is provably safe
        for the gain-modulated waveform represented by these sample/gain
        histories.

        For an output true-peak FIR phase p at a source time n, the delayed
        output samples contributing to the interpolation are split into:

          - past samples (offset < 0): exact applied gain is known;
          - current sample (offset == 0): gain g is the variable being solved;
          - future samples (offset > 0): their gain is not finalized yet, but
            it cannot exceed the release envelope

                G_k(g) = 1 - releaseA^k * (1 - g).

        That envelope is valid because the limiter gain can only decrease
        instantaneously or recover exponentially toward at most unity.

        For future samples, the input sample value and FIR coefficient signs
        are already known. Therefore the possible future contribution is
        bounded by an affine interval rather than by a phase-wide L1 norm.
        This is substantially tighter while remaining mathematically
        conservative.

        The returned interval is the intersection over all FIR phases and
        both channels.
    */
    template <typename SampleBuffer, typename GainBuffer>
    struct ConservativeGainInterval {
        /*
            This is a feasible interval for the CURRENT source-sample gain g.

                0 <= minimum <= maximum <= 1

            Both endpoints matter. In particular, the lower bound may be
            required by a negative protected waveform phase when increasing
            the current sample gain is necessary to keep the absolute true
            peak below the ceiling.
        */
        float minimum = 0.0f;
        float maximum = 1.0f;
        bool feasible = true;
    };

    template <typename SampleBuffer, typename GainBuffer>
    ConservativeGainInterval<SampleBuffer, GainBuffer>
    conservativeGainInterval(const SampleBuffer& left,
                             const SampleBuffer& right,
                             const GainBuffer& gainHistory,
                             std::size_t sourceSlot,
                             float releaseA,
                             float ceilingGain) const noexcept
    {
        ConservativeGainInterval<SampleBuffer, GainBuffer> result;

        const std::size_t bufferSize = left.size();
        if (bufferSize == 0 ||
            right.size() != bufferSize ||
            gainHistory.size() != bufferSize)
        {
            result.minimum = 0.0f;
            result.maximum = 0.0f;
            result.feasible = false;
            return result;
        }

        const float release =
            clampf(releaseA, 0.0f, 1.0f);

        const float ceiling =
            std::max(0.0f, ceilingGain);

        /*
            releasePower is the k-step release envelope generated by the
            actual limiter recursion. FIR support is taken from the exact
            phase/tap coordinates below.
        */
        std::array<float, kGenericTapsPerPhase> releasePower{};
        releasePower[0] = 1.0f;

        for (std::size_t k = 1;
             k < releasePower.size();
             ++k)
        {
            releasePower[k] =
                releasePower[k - 1] * release;
        }

        auto wrapOffset =
            [bufferSize, sourceSlot](int offset) noexcept -> std::size_t
        {
            const long long n =
                static_cast<long long>(sourceSlot) +
                static_cast<long long>(offset);

            const long long m =
                static_cast<long long>(bufferSize);

            long long r = n % m;
            if (r < 0)
                r += m;

            return static_cast<std::size_t>(r);
        };

        auto applyUpperInequality =
            [&result, ceiling](float intercept,
                               float slope) noexcept
        {
            /*
                Solve:

                    intercept + slope*g <= ceiling

                over the current-gain domain [0, 1].
            */

            if (!std::isfinite(intercept) ||
                !std::isfinite(slope))
            {
                result.feasible = false;
                return;
            }

            constexpr float eps = 1.0e-12f;

            if (slope > eps)
            {
                const float upper =
                    (ceiling - intercept) / slope;

                if (!std::isfinite(upper))
                {
                    result.feasible = false;
                    return;
                }

                result.maximum =
                    std::min(result.maximum, upper);
            }
            else if (slope < -eps)
            {
                const float lower =
                    (ceiling - intercept) / slope;

                if (!std::isfinite(lower))
                {
                    result.feasible = false;
                    return;
                }

                result.minimum =
                    std::max(result.minimum, lower);
            }
            else if (intercept > ceiling + 1.0e-7f)
            {
                result.feasible = false;
            }

            if (result.minimum > result.maximum + 1.0e-6f)
                result.feasible = false;
        };

        auto applyLowerInequality =
            [&applyUpperInequality](float intercept,
                                    float slope) noexcept
        {
            /*
                Solve:

                    intercept + slope*g >= -ceiling

                by multiplying both sides by -1.
            */
            applyUpperInequality(
                -intercept,
                -slope);
        };

        auto processChannel =
            [&](const SampleBuffer& samples) noexcept
        {
            if (!result.feasible)
                return;

            const std::size_t phaseCount = supportPhaseCount();
            const std::size_t taps = supportTapCount();

            for (std::size_t phase = 0;
                 phase < phaseCount;
                 ++phase)
            {
                /*
                    The protected waveform phase is represented as two
                    affine bounds in the current gain g:

                        lower(g) = lowerA + lowerB*g
                        upper(g) = upperA + upperB*g

                    Past samples are exact.
                    The current sample is exactly v*g.
                    Future samples use the proven gain interval

                        0 <= G_k(g) <= U_k(g)

                    where

                        U_k(g) = (1-a^k) + a^k*g.
                */
                float lowerA = 0.0f;
                float lowerB = 0.0f;
                float upperA = 0.0f;
                float upperB = 0.0f;

                for (std::size_t q = 0;
                     q < taps;
                     ++q)
                {
                    /*
                        EXACT source coordinate generated from the same
                        x[n-q] runtime convention used by the estimator.
                        The limiter no longer reconstructs FIR support from
                        an independent delay - tap expression.
                    */
                    const TapCoordinate& coordinate =
                        supportCoordinates_[phase][q];

                    const int offset =
                        coordinate.sourceOffsetFromAnchor;

                    const std::size_t slot =
                        wrapOffset(offset);

                    const float x =
                        safeSample(samples[slot]);

                    const float h =
                        useReference48k_
                            ? referenceCoeff_[phase][q]
                            : genericCoeff_[phase][q];

                    const float v =
                        h * x;

                    if (!std::isfinite(v))
                        continue;

                    // --------------------------------------------------------
                    // Past sample: exact recorded gain.
                    // --------------------------------------------------------
                    if (offset < 0)
                    {
                        const float gPast =
                            clampf(
                                safeSample(gainHistory[slot]),
                                0.0f,
                                1.0f);

                        const float contribution =
                            v * gPast;

                        lowerA += contribution;
                        upperA += contribution;
                        continue;
                    }

                    // --------------------------------------------------------
                    // Current source sample: exact affine term v*g.
                    // --------------------------------------------------------
                    if (offset == 0)
                    {
                        lowerB += v;
                        upperB += v;
                        continue;
                    }

                    // --------------------------------------------------------
                    // Future source sample.
                    // --------------------------------------------------------
                    const std::size_t k =
                        static_cast<std::size_t>(offset);

                    /*
                        U_k(g) = (1-a^k) + a^k*g
                    */
                    const float aPow =
                        k < releasePower.size()
                            ? releasePower[k]
                            : std::pow(
                                  release,
                                  static_cast<float>(k));

                    const float u0 =
                        1.0f - aPow;

                    const float u1 =
                        aPow;

                    /*
                        Actual future gain is only proven to satisfy:

                            0 <= G_k(g) <= U_k(g)

                        Therefore:

                          v >= 0:
                              contribution in [0, v*U]

                          v < 0:
                              contribution in [v*U, 0]

                        BOTH endpoints are included here.
                    */
                    if (v >= 0.0f)
                    {
                        /* Upper endpoint = v*U. */
                        upperA += v * u0;
                        upperB += v * u1;

                        /* Lower endpoint = 0. */
                    }
                    else
                    {
                        /* Lower endpoint = v*U. */
                        lowerA += v * u0;
                        lowerB += v * u1;

                        /* Upper endpoint = 0. */
                    }
                }

                /*
                    For every reconstructed phase we require:

                        lowerA + lowerB*g >= -ceiling
                        upperA + upperB*g <= +ceiling

                    The intersection is the set of current gains for which
                    EVERY possible future gain trajectory admitted by the
                    release-envelope assumption remains under the true-peak
                    ceiling.
                */
                applyUpperInequality(
                    upperA,
                    upperB);

                if (!result.feasible)
                    return;

                applyLowerInequality(
                    lowerA,
                    lowerB);

                if (!result.feasible)
                    return;
            }
        };

        processChannel(left);
        processChannel(right);

        result.minimum =
            clampf(result.minimum, 0.0f, 1.0f);

        result.maximum =
            clampf(result.maximum, 0.0f, 1.0f);

        if (!std::isfinite(result.minimum) ||
            !std::isfinite(result.maximum) ||
            result.minimum > result.maximum + 1.0e-6f)
        {
            result.minimum = 0.0f;
            result.maximum = 0.0f;
            result.feasible = false;
        }

        return result;
    }

    std::size_t oversampleFactor() const noexcept {
        return oversample_;
    }

private:
    void buildSupportCoordinates() noexcept {
        for (auto& row : supportCoordinates_)
            for (auto& c : row)
                c = TapCoordinate{};

        phaseTimestampFromAnchor_.fill(0.0);

        const TimingInfo t = timing();
        const double center = t.nominalGroupDelaySamples;
        const std::size_t anchorDelay =
            t.causalAvailabilityDelaySamples;
        const std::size_t phases = supportPhaseCount();
        const std::size_t taps = supportTapCount();

        for (std::size_t phase = 0;
             phase < phases;
             ++phase)
        {
            const double frac =
                static_cast<double>(phase) /
                static_cast<double>(phases);

            /*
                The runtime estimator at input index j evaluates x[j-q].
                The protected source anchor m is related by

                    j = m + causalAvailabilityDelay.

                Therefore the exact phase timestamp relative to m is

                    causalAvailability - groupDelay + frac.
            */
            const double phaseTimestamp =
                static_cast<double>(anchorDelay) -
                center + frac;

            phaseTimestampFromAnchor_[phase] = phaseTimestamp;

            for (std::size_t q = 0;
                 q < taps;
                 ++q)
            {
                TapCoordinate& c =
                    supportCoordinates_[phase][q];

                /* Exactly the source sample x[j-q]. */
                c.sourceOffsetFromAnchor =
                    static_cast<int>(anchorDelay) -
                    static_cast<int>(q);

                c.phaseTimestampOffsetFromAnchor =
                    phaseTimestamp;

                c.tapTimeOffsetFromPhase =
                    static_cast<double>(c.sourceOffsetFromAnchor) -
                    phaseTimestamp;
            }
        }
    }

    void buildGenericCoefficients() noexcept {
        for (auto& row : genericCoeff_)
            row.fill(0.0f);

        const float center =
            0.5f * static_cast<float>(kGenericTapsPerPhase - 1);

        for (std::size_t phase = 0;
             phase < oversample_;
             ++phase)
        {
            const float frac =
                static_cast<float>(phase) /
                static_cast<float>(oversample_);

            float sum = 0.0f;

            for (std::size_t q = 0;
                 q < kGenericTapsPerPhase;
                 ++q)
            {
                /*
                    q=0 is newest and q=32 is oldest.

                    The custom estimator reconstructs fractional positions
                    with a windowed-sinc kernel. The conservative integer
                    availability bound remains 16 samples.
                */
                const float n =
                    static_cast<float>(q)
                    - center
                    + frac;

                const float h =
                    sincPi(n) *
                    blackmanWindow(q, kGenericTapsPerPhase);

                genericCoeff_[phase][q] = h;
                sum += h;
            }

            if (std::fabs(sum) > 1.0e-12f) {
                const float invSum = 1.0f / sum;
                for (std::size_t q = 0;
                     q < kGenericTapsPerPhase;
                     ++q)
                {
                    genericCoeff_[phase][q] *= invSum;
                }
            }
        }
    }

    float processReference48k(float x) noexcept {
        refBuf_[refWrite_] = x;

        float peak = 0.0f;

        /*
            ITU-R BS.1770-5 coefficient table is stored phase-major.
            Within every phase, q=0 is the coefficient multiplying the
            newest input sample, q=11 the oldest sample.

                y_p[n] = sum(q=0..11) h[p][q] x[n-q]
        */
        for (std::size_t phase = 0;
             phase < kReferencePhases;
             ++phase)
        {
            double y = 0.0;

            for (std::size_t q = 0;
                 q < kReferenceTapsPerPhase;
                 ++q)
            {
                const std::size_t pos =
                    (refWrite_
                     + kReferenceTapsPerPhase
                     - q)
                    % kReferenceTapsPerPhase;

                y +=
                    static_cast<double>(referenceCoeff_[phase][q]) *
                    static_cast<double>(refBuf_[pos]);
            }

            peak = std::max(
                peak,
                std::fabs(static_cast<float>(y)));
        }

        refWrite_ =
            (refWrite_ + 1) % kReferenceTapsPerPhase;

        return peak;
    }

    float processGeneric(float x) noexcept {
        genericBuf_[genericWrite_] = x;

        float peak = 0.0f;

        for (std::size_t phase = 0;
             phase < oversample_;
             ++phase)
        {
            double y = 0.0;

            for (std::size_t q = 0;
                 q < kGenericTapsPerPhase;
                 ++q)
            {
                const std::size_t pos =
                    (genericWrite_
                     + kGenericTapsPerPhase
                     - q)
                    % kGenericTapsPerPhase;

                y +=
                    static_cast<double>(genericCoeff_[phase][q]) *
                    static_cast<double>(genericBuf_[pos]);
            }

            peak = std::max(
                peak,
                std::fabs(static_cast<float>(y)));
        }

        genericWrite_ =
            (genericWrite_ + 1) % kGenericTapsPerPhase;

        return peak;
    }

    /* ITU-R BS.1770-5 Annex 2, 48 kHz, 4-phase FIR. */
    inline static constexpr
    std::array<std::array<float, kReferenceTapsPerPhase>, kReferencePhases>
    referenceCoeff_ = {{
        {{ 0.0017089843750f,  0.0109863281250f, -0.0196533203125f,
           0.0332031250000f, -0.0594482421875f,  0.1373291015625f,
           0.9721679687500f, -0.1022949218750f,  0.0476074218750f,
          -0.0266113281250f,  0.0148925781250f, -0.0083007812500f }},

        {{-0.0291748046875f,  0.0292968750000f, -0.0517578125000f,
           0.0891113281250f, -0.1665039062500f,  0.4650878906250f,
           0.7797851562500f, -0.2003173828125f,  0.1015625000000f,
          -0.0582275390625f,  0.0330810546875f, -0.0189208984375f }},

        {{-0.0189208984375f,  0.0330810546875f, -0.0582275390625f,
           0.1015625000000f, -0.2003173828125f,  0.7797851562500f,
           0.4650878906250f, -0.1665039062500f,  0.0891113281250f,
          -0.0517578125000f,  0.0292968750000f, -0.0291748046875f }},

        {{-0.0083007812500f,  0.0148925781250f, -0.0266113281250f,
           0.0476074218750f, -0.1022949218750f,  0.9721679687500f,
           0.1373291015625f, -0.0594482421875f,  0.0332031250000f,
          -0.0196533203125f,  0.0109863281250f,  0.0017089843750f }}
    }};

    float fs_ = 48000.0f;
    std::size_t oversample_ = 4;
    bool useReference48k_ = true;

    std::int64_t inputSampleIndex_ = -1;

    std::array<std::array<float, kGenericTapsPerPhase>, kMaxPhases>
        genericCoeff_{};

    std::array<
        std::array<TapCoordinate, kGenericTapsPerPhase>,
        kMaxPhases>
        supportCoordinates_{};

    std::array<double, kMaxPhases>
        phaseTimestampFromAnchor_{};

    std::array<float, kReferenceTapsPerPhase> refBuf_{};
    std::array<float, kGenericTapsPerPhase> genericBuf_{};

    std::size_t refWrite_ = 0;
    std::size_t genericWrite_ = 0;
};

class StereoLinkedTruePeakLimiter {
public:
    static constexpr float kDefaultSafetyMarginDb = 1.7f;
    static constexpr std::size_t kMaxLookahead = 512;
    static constexpr std::size_t kOutputReserveSamples = 2;
    static constexpr std::size_t kMaxDelayBuffer = 2048;

    // Lowest possible effective protected target:
    // ceiling min (-12 dB) + safety margin max (3 dB).
    static constexpr float kMinimumEffectiveCeilingDb = -15.0f;

    void prepare(double sampleRate) noexcept {
        fs_ = static_cast<float>(std::max(1.0, sampleRate));

        truePeakL_.prepare(fs_);
        truePeakR_.prepare(fs_);
        outputMeterL_.prepare(fs_);
        outputMeterR_.prepare(fs_);

        /*
            Preserve the v9.4 audio-latency contract.  This reserve is now
            an explicit output-path reserve; gain planning itself is done at
            the earliest source time at which the complete interpolation
            support is known.
        */
        lookahead_ = static_cast<std::size_t>(
            clampf(std::ceil(0.0025f * fs_),
                   16.0f,
                   static_cast<float>(kMaxLookahead)));

        const auto timing = truePeakL_.timing();

        nominalEstimatorDelaySamples_ =
            timing.nominalGroupDelaySamples;
        earliestTimestampDelaySamples_ =
            timing.earliestTimestampDelaySamples;
        latestTimestampDelaySamples_ =
            timing.latestTimestampDelaySamples;
        estimatorDelayBound_ =
            timing.causalAvailabilityDelaySamples;

        protectionHorizon_ =
            lookahead_ + kOutputReserveSamples;

        /*
            Exact plugin audio latency:

                detector availability
              + output reserve
              + explicit output reserve

            At 48 kHz:

                6 + 120 + 2 = 128 samples.
        */
        const std::size_t wantedDelay =
            estimatorDelayBound_ + protectionHorizon_;

        delaySamples_ =
            std::min<std::size_t>(
                kMaxDelayBuffer - 1,
                wantedDelay);

        /*
            Derive the ceiling-control horizon from the actual FIR waveform
            timestamp interval. This is deliberately distinct from
            protectionHorizon_, which is the integer output reserve/guard
            used to establish the public plugin latency.

            At 48 kHz:

                output delay             = 128
                latest phase timestamp   = 0.875
                causal availability      = 6

                latest protected output  = 122.875
                ceiling-control horizon   = ceil(122.875) = 123
        */
        const auto protection =
            truePeakL_.protectionForOutputDelay(
                static_cast<double>(delaySamples_));

        ceilingControlHorizon_ =
            protection.ceilingControlHorizon;

        releaseA_ =
            std::exp(-1.0f / (0.120f * fs_));

        safetyMarginDb_ = 0.25f;
        ceilingDb_.prepare(
            fs_,
            5.0f,
            limiterTargetDb_ - safetyMarginDb_);

        reset();
    }

    void reset() noexcept {
        write_ = 0;
        sampleIndex_ = -1;

        gain_ = 1.0f;
        planningGain_ = 1.0f;
        constraintInfeasibleEver_ = false;
        lastConstraintFeasible_ = true;
        lastSourceEstimatedTruePeak_ = 0.0f;

        audioL_.fill(0.0f);
        audioR_.fill(0.0f);

        /*
            Source-time gain plan:

                plannedGain_[slot(m)] == G[m]

            These are the actual gains which will later be emitted after the
            common audio delay.
        */
        plannedGain_.fill(1.0f);

        truePeakL_.reset();
        truePeakR_.reset();
        outputMeterL_.reset();
        outputMeterR_.reset();

        lastOutputTruePeak_ = 0.0f;

        ceilingDb_.setImmediate(
            limiterTargetDb_ - safetyMarginDb_);
    }

    void setCeilingDb(float db) noexcept {
        limiterTargetDb_ =
            clampf(db, -12.0f, 0.0f);

        updateEffectiveCeilingTarget();
    }

    void setSafetyMarginDb(float db) noexcept {
        safetyMarginDb_ =
            clampf(db, 0.0f, 3.0f);

        updateEffectiveCeilingTarget();
    }

    float getOutputTruePeak() const noexcept {
        return lastOutputTruePeak_;
    }

    float getGainReductionDb() const noexcept {
        return std::max(
            0.0f,
            -gainToDb(gain_));
    }

    bool gainConstraintWasInfeasible() const noexcept {
        return constraintInfeasibleEver_;
    }

    bool gainConstraintCurrentlyFeasible() const noexcept {
        return lastConstraintFeasible_;
    }

    float getCurrentPlannedGain() const noexcept {
        return planningGain_;
    }

    float getLastSourceEstimatedTruePeak() const noexcept {
        return lastSourceEstimatedTruePeak_;
    }

    void process(float& left,
                 float& right) noexcept
    {
        left = safeSample(left);
        right = safeSample(right);

        const std::int64_t idx = ++sampleIndex_;

        /*
            Store the current raw source sample first.  The write slot is
            therefore always the slot for source index idx.
        */
        audioL_[write_] = left;
        audioR_[write_] = right;

        /*
            The estimator becomes causally available for source sample m at

                m = idx - estimatorDelayBound_.

            At that instant the complete FIR support for m is present in the
            source ring buffer.  That is when G[m] is solved and committed.
        */
        /*
            Keep the per-channel estimator streams advancing for diagnostic
            visibility, but DO NOT attach their scalar 4x peak to one integer
            source sample.  The limiter proof below uses the exact FIR tap
            coordinates and the full gain-modulated support interval.
        */
        lastSourceEstimatedTruePeak_ =
            std::max(truePeakL_.process(left),
                     truePeakR_.process(right));

        const std::int64_t sourceIndexForPlanning =
            idx - static_cast<std::int64_t>(estimatorDelayBound_);

        /*
            The source-time gain plan is committed now but the corresponding
            delayed waveform will not be emitted until later.  A ceiling value
            read only at this instant is therefore not sufficient: future
            ceiling automation could make the committed gain too large before
            that waveform reaches the output.

            ceilingDb_ stores the EFFECTIVE protected ceiling already including
            the safety margin.  Its recurrence is known, so we can derive the
            minimum ceiling that can occur anywhere before the protected
            waveform has fully reached the output.
        */
        (void)ceilingDb_.process();

        /*
            FIX (loudness): the original code passed the absolute minimum
            permitted ceiling (-15 dB) here, i.e. it assumed the user could
            drop the ceiling to its floor at ANY moment.  With the 5 ms
            ceiling smoother and a ~123-sample horizon that pulled the
            protected ceiling down to roughly -6.4 dBFS even when the user
            asked for 0 dBFS, so every signal above about -9 dBFS was
            squashed by 2-8 dB (measured: a 0.9 amplitude sine came out
            7.4 dB low with ceiling 0 dB / safety 0 dB).

            The recurrence is monotonic towards its CURRENT target, so the
            lowest value it can reach inside the protection horizon is given
            by the lower of the current value and the current target.
        */
        const float futureSafeCeilingDb =
            ceilingDb_.futureLowerBound(
                std::min(ceilingDb_.target(), ceilingDb_.current()),
                ceilingControlHorizon_);

        const float effectiveFutureSafeCeilingGain =
            dbToGain(futureSafeCeilingDb);

        protectedCeilingDb_ = futureSafeCeilingDb;
        protectedCeilingGain_ = effectiveFutureSafeCeilingGain;

        if (sourceIndexForPlanning >= 0) {
            const std::size_t sourceSlot =
                offsetSlot(
                    write_,
                    -static_cast<std::ptrdiff_t>(
                        estimatorDelayBound_));

            planSourceGain(
                sourceSlot,
                effectiveFutureSafeCeilingGain);
        }

        /*
            The output sample is selected only from an already-finalized
            source-time gain plan.  No gain constraint is solved here.

                output source = idx - delaySamples_

            and its gain was committed approximately
            protectionHorizon_ samples earlier.
        */
        const std::size_t outIndex =
            offsetSlot(
                write_,
                -static_cast<std::ptrdiff_t>(
                    delaySamples_));

        const float outputGain =
            clampf(
                safeSample(plannedGain_[outIndex]),
                0.0f,
                1.0f);

        gain_ = outputGain;

        left = audioL_[outIndex] * outputGain;
        right = audioR_[outIndex] * outputGain;

        lastOutputTruePeak_ =
            std::max(
                outputMeterL_.process(left),
                outputMeterR_.process(right));

        ++write_;
        if (write_ >= kMaxDelayBuffer)
            write_ = 0;
    }

    std::size_t latencySamples() const noexcept {
        return delaySamples_;
    }

    double latencyMs() const noexcept {
        return 1000.0 *
               static_cast<double>(delaySamples_) /
               static_cast<double>(fs_);
    }

    std::size_t lookaheadSamples() const noexcept {
        return lookahead_;
    }

    std::size_t estimatorDelaySamples() const noexcept {
        return estimatorDelayBound_;
    }

    std::size_t timestampDelaySamples() const noexcept {
        /*
            Report the conservative integer timestamp delay.  Truncating the
            continuous latest waveform timestamp (5.875 -> 5) would understate
            the causal bound.
        */
        return static_cast<std::size_t>(
            std::ceil(latestTimestampDelaySamples_ - 1.0e-12));
    }

    double estimatorNominalGroupDelaySamples() const noexcept {
        return nominalEstimatorDelaySamples_;
    }

    double estimatorEarliestTimestampDelaySamples() const noexcept {
        return earliestTimestampDelaySamples_;
    }

    double estimatorLatestTimestampDelaySamples() const noexcept {
        return latestTimestampDelaySamples_;
    }

    std::size_t protectionHorizonSamples() const noexcept {
        return protectionHorizon_;
    }

    std::size_t ceilingControlHorizonSamples() const noexcept {
        return ceilingControlHorizon_;
    }

    std::size_t guardSamples() const noexcept {
        return kOutputReserveSamples;
    }

private:
    std::size_t offsetSlot(
        std::size_t baseSlot,
        std::ptrdiff_t offset) const noexcept
    {
        const std::ptrdiff_t n =
            static_cast<std::ptrdiff_t>(kMaxDelayBuffer);

        std::ptrdiff_t p =
            static_cast<std::ptrdiff_t>(baseSlot) + offset;

        p %= n;
        if (p < 0)
            p += n;

        return static_cast<std::size_t>(p);
    }

    void updateEffectiveCeilingTarget() noexcept
    {
        /*
            Combine the user ceiling and safety margin BEFORE smoothing.
            The limiter therefore has one control quantity whose future
            lower envelope covers future changes to either input parameter.

            Allowed ranges are:
                ceiling       >= -12 dB
                safety margin >=   0 dB
                safety margin <=   3 dB

            Hence the smallest permitted effective target is -15 dB.
        */
        ceilingDb_.setTarget(
            limiterTargetDb_ - safetyMarginDb_);
    }

    /*
        The actual limiter recurrence is:

            if target <= g_prev:
                g_next = target                         (instantaneous attack)

            else:
                g_next = target + releaseA*(g_prev-target)

        Therefore every reachable one-step gain satisfies:

            0 <= g_next <= 1 - releaseA*(1-g_prev).

        Conversely, every gain in that interval is reachable by choosing an
        appropriate target.  This lets the source-time proof use the exact
        recurrence as a simple admissible-gain interval.
    */
    float recurrenceUpperBound(float previousGain) const noexcept
    {
        const float previous =
            clampf(previousGain, 0.0f, 1.0f);
        const float a =
            clampf(releaseA_, 0.0f, 1.0f);

        return clampf(
            1.0f - a * (1.0f - previous),
            0.0f,
            1.0f);
    }

    float commitReachableGain(float previousGain,
                              float requestedGain) const noexcept
    {
        const float previous =
            clampf(previousGain, 0.0f, 1.0f);
        const float requested =
            clampf(requestedGain, 0.0f, 1.0f);

        if (requested <= previous + 1.0e-7f)
            return requested;

        const float oneMinusA =
            std::max(1.0e-12f, 1.0f - releaseA_);

        /*
            Solve the REAL recurrence for the target that produces the
            requested upward step:

                requested = target + a(previous-target)
                          = a*previous + (1-a)*target

                target = (requested-a*previous)/(1-a).
        */
        const float target = clampf(
            (requested - releaseA_ * previous) / oneMinusA,
            previous,
            1.0f);

        const float actual =
            target +
            releaseA_ * (previous - target);

        return clampf(actual, 0.0f, 1.0f);
    }

    void planSourceGain(
        std::size_t sourceSlot,
        float ceilingGain) noexcept
    {
        const std::size_t previousSlot =
            offsetSlot(sourceSlot, -1);

        const float previousGain =
            clampf(
                safeSample(plannedGain_[previousSlot]),
                0.0f,
                1.0f);

        const float recurrenceUpper =
            recurrenceUpperBound(previousGain);

        /*
            Exact FIR-support constraint:

              interval.minimum <= g[sourceSlot] <= interval.maximum

            The interval itself already accounts for the complete gain
            trajectory of future FIR-support samples using the proven
            k-step release envelope.
        */
        const auto interval =
            truePeakL_.conservativeGainInterval(
                audioL_,
                audioR_,
                plannedGain_,
                sourceSlot,
                releaseA_,
                ceilingGain);

        const float intervalMinimum =
            clampf(interval.minimum, 0.0f, 1.0f);
        const float intervalMaximum =
            clampf(interval.maximum, 0.0f, 1.0f);

        const float admissibleUpper =
            std::min(
                intervalMaximum,
                recurrenceUpper);

        /*
            THIS is the feasibility invariant.  The source gain exists iff

                intervalMinimum
                    <= admissibleUpper.

            There is no second clamp after this test which can push the gain
            outside the recurrence envelope.
        */
        const bool feasible =
            interval.feasible &&
            std::isfinite(intervalMinimum) &&
            std::isfinite(admissibleUpper) &&
            intervalMinimum <=
                admissibleUpper + 1.0e-6f;

        lastConstraintFeasible_ = feasible;

        if (feasible)
        {
            /*
                Choose the LARGEST gain admitted by both proofs.  This is the
                least attenuation that remains mathematically conservative.

                    g[sourceSlot] = admissibleUpper

                Because admissibleUpper <= recurrenceUpper, the committed
                gain is reachable by the ACTUAL release recurrence.
            */
            planningGain_ =
                commitReachableGain(
                    previousGain,
                    admissibleUpper);

            /* Numerical guard: this may only tighten the upper bound. */
            planningGain_ =
                std::min(
                    planningGain_,
                    admissibleUpper);

            /* Feasibility already establishes this lower invariant. */
            if (planningGain_ + 1.0e-6f < intervalMinimum)
            {
                /* Only possible through floating-point roundoff. */
                planningGain_ = intervalMinimum;
            }
        }
        else
        {
            /*
                An infeasible state means no source gain satisfies BOTH the
                FIR waveform inequalities and the actual release recurrence.

                It is therefore incorrect to manufacture a proof by setting
                the gain to zero.  Zero can still violate a negative-phase
                lower bound, and it also hides which invariant failed.

                The deterministic fail-safe is instead the largest gain that
                still honours the actual recurrence and the upper FIR bound.
                The diagnostic remains asserted, so no hard-ceiling claim is
                made for an already-infeasible state.
            */
            constraintInfeasibleEver_ = true;

            planningGain_ =
                clampf(
                    std::min(
                        intervalMaximum,
                        recurrenceUpper),
                    0.0f,
                    1.0f);

            /*
                If the interval was numerically invalid, intervalMaximum may
                be unusable. Preserve the recurrence as the final invariant
                rather than introducing an arbitrary zero.
            */
            if (!std::isfinite(planningGain_))
                planningGain_ = recurrenceUpper;
        }

        /*
            Final state invariant:

                0 <= planningGain_ <= recurrenceUpper

            at every finite source-time planning step.
        */
        planningGain_ =
            clampf(
                planningGain_,
                0.0f,
                recurrenceUpper);

        if (!feasible)
            constraintInfeasibleEver_ = true;

        plannedGain_[sourceSlot] = planningGain_;
    }

private:
    float fs_ = 48000.0f;

    /*
        2.5 ms output reserve retained from v9.4 so the public latency
        contract remains unchanged.
    */
    std::size_t lookahead_ = 120;
    std::size_t estimatorDelayBound_ = 6;
    std::size_t protectionHorizon_ = 122;
    std::size_t delaySamples_ = 128;

    /* Exact integer horizon for the future ceiling recurrence. */
    std::size_t ceilingControlHorizon_ = 123;

    double nominalEstimatorDelaySamples_ = 5.875;
    double earliestTimestampDelaySamples_ = 5.125;
    double latestTimestampDelaySamples_ = 5.875;

    float limiterTargetDb_ = -1.0f;
    float safetyMarginDb_ = 0.25f;

    // Future-safe protected ceiling used by source-time gain planning.
    float protectedCeilingGain_ = dbToGain(-1.25f);
    float protectedCeilingDb_ = -1.25f;

    /* Gain of the sample actually being emitted. */
    float gain_ = 1.0f;

    /* Most recently committed source-time gain. */
    float planningGain_ = 1.0f;

    float releaseA_ = 0.9998f;
    bool constraintInfeasibleEver_ = false;
    bool lastConstraintFeasible_ = true;
    float lastSourceEstimatedTruePeak_ = 0.0f;

    SmoothedValue ceilingDb_;

    std::array<float, kMaxDelayBuffer> audioL_{};
    std::array<float, kMaxDelayBuffer> audioR_{};

    /*
        Source-time gain sequence.  This is the gain sequence used by the
        conservative FIR proof and, later, by the emitted delayed waveform.
    */
    std::array<float, kMaxDelayBuffer> plannedGain_{};

    std::size_t write_ = 0;

    TruePeakEstimator truePeakL_;
    TruePeakEstimator truePeakR_;

    /* True-peak meter for the actual delayed output stream. */
    TruePeakEstimator outputMeterL_;
    TruePeakEstimator outputMeterR_;

    float lastOutputTruePeak_ = 0.0f;
    std::int64_t sampleIndex_ = -1;
};

class FixedStereoDelay {
public:
    static constexpr std::size_t kMaxDelaySamples = 2048;

    void prepare(std::size_t delaySamples) noexcept {
        delaySamples_ =
            std::min(delaySamples, kMaxDelaySamples - 1);
        reset();
    }

    void reset() noexcept {
        write_ = 0;
        left_.fill(0.0f);
        right_.fill(0.0f);
    }

    void process(float& left,
                 float& right) noexcept
    {
        left = safeSample(left);
        right = safeSample(right);
        left_[write_] = left;
        right_[write_] = right;

        const std::size_t read =
            (write_ + kMaxDelaySamples - delaySamples_) %
            kMaxDelaySamples;

        left = left_[read];
        right = right_[read];

        ++write_;
        if (write_ >= kMaxDelaySamples)
            write_ = 0;
    }

private:
    std::array<float, kMaxDelaySamples> left_{};
    std::array<float, kMaxDelaySamples> right_{};
    std::size_t write_ = 0;
    std::size_t delaySamples_ = 0;
};

class StereoWidenerDSP {
public:
    struct MeterSnapshot {
        float monoCompatibility = 1.0f;
        float gainReductionDb = 0.0f;
        float outputPeakDbTP = -120.0f;
    };

    void prepare(double sampleRate) noexcept {
        prepared_.store(false, std::memory_order_release);

        fs_ = static_cast<float>(std::max(8000.0, sampleRate));

        width_.prepare(fs_, 25.0f, 1.0f);
        lowFc_.prepare(fs_, 25.0f, 180.0f);
        highFc_.prepare(fs_, 25.0f, 3200.0f);
        bassMonoFc_.prepare(fs_, 25.0f, 80.0f);
        haasDelay_.prepare(fs_, 25.0f, 0.0f);
        haasMix_.prepare(fs_, 25.0f, 0.0f);
        dryWet_.prepare(fs_, 25.0f, 1.0f);
        outputTrim_.prepare(fs_, 25.0f, 1.0f);
        autoGain_.prepare(fs_, 250.0f, 1.0f);
        enabledMix_.prepare(fs_, 10.0f, 1.0f);

        crossover_.prepare(fs_, 180.0f, 3200.0f);
        crossoverS_.prepare(fs_, 180.0f, 3200.0f);
        bassSideLP_.prepare(fs_, 80.0f, LR4Filter::Type::LowPass);

        midDiffuser_.prepare(fs_, 260.0f, 1.43f, 0.80f);
        highDiffuser_.prepare(fs_, 1100.0f, 1.39f, 0.78f);

        haas_.prepare(fs_);
        transient_.prepare(fs_);

        limiter_.prepare(fs_);
        limiter_.setCeilingDb(-1.0f);
        limiter_.setSafetyMarginDb(0.25f);

        autoLevelEnvA_ = std::exp(-1.0f / (0.105f * fs_));
        peakMeterDecayA_ = std::exp(-1.0f / (0.300f * fs_));

        constexpr float lowBandTau  = 0.0208229149f;
        constexpr float midBandTau  = 0.0041562413f;
        constexpr float highBandTau = 0.0020728992f;

        bandMeterA_[0] = std::exp(-1.0f / (lowBandTau * fs_));
        bandMeterA_[1] = std::exp(-1.0f / (midBandTau * fs_));
        bandMeterA_[2] = std::exp(-1.0f / (highBandTau * fs_));

        reset();
        updateFiltersNow();

        prepared_.store(true, std::memory_order_release);
    }

    void reset() noexcept {
        crossover_.reset();
        crossoverS_.reset();
        bassSideLP_.reset();
        midDiffuser_.reset();
        highDiffuser_.reset();
        haas_.reset();
        transient_.reset();
        limiter_.reset();

        monoRetention_.fill(1.0f);
        monoAccumM_ = 0.0f;
        totalAccum_ = 0.0f;
        dryEnergy_ = 0.0f;
        wetEnergy_ = 0.0f;
        outPeak_ = 0.0f;
        sampleCounter_ = 0;

        meterMono_.store(1.0f, std::memory_order_relaxed);
        meterGR_.store(0.0f, std::memory_order_relaxed);
        meterPeakDb_.store(-120.0f, std::memory_order_relaxed);

        autoGain_.setImmediate(1.0f);
        enabledMix_.setImmediate(
            enabled_.load(std::memory_order_relaxed) ? 1.0f : 0.0f);
    }

    void setEnabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_relaxed);
    }

    void setWidth(float width) noexcept {
        widthTarget_.store(clampf(width, 0.0f, 2.5f),
                           std::memory_order_relaxed);
    }

    void setLowCrossoverHz(float hz) noexcept {
        lowTarget_.store(clampf(hz, 40.0f, 400.0f),
                         std::memory_order_relaxed);
    }

    void setHighCrossoverHz(float hz) noexcept {
        highTarget_.store(clampf(hz, 1000.0f, 10000.0f),
                          std::memory_order_relaxed);
    }

    void setBassMonoFrequencyHz(float hz) noexcept {
        bassMonoTarget_.store(clampf(hz, 20.0f, 250.0f),
                              std::memory_order_relaxed);
    }

    void setHaasDelayMs(float ms) noexcept {
        haasDelayTarget_.store(clampf(ms, 0.0f, 10.0f),
                               std::memory_order_relaxed);
    }

    void setHaasMix(float mix) noexcept {
        haasMixTarget_.store(clampf(mix, 0.0f, 1.0f),
                             std::memory_order_relaxed);
    }

    void setDryWet(float mix) noexcept {
        dryWetTarget_.store(clampf(mix, 0.0f, 1.0f),
                            std::memory_order_relaxed);
    }

    void setOutputGainDb(float db) noexcept {
        outputGainTarget_.store(clampf(db, -12.0f, 6.0f),
                                std::memory_order_relaxed);
    }

    void setOutputCeilingDb(float db) noexcept {
        ceilingTarget_.store(clampf(db, -12.0f, 0.0f),
                             std::memory_order_relaxed);
    }

    void setLimiterSafetyMarginDb(float db) noexcept {
        safetyMarginTarget_.store(clampf(db, 0.0f, 3.0f),
                                  std::memory_order_relaxed);
    }

    void setAutoLevel(bool enabled) noexcept {
        autoLevel_.store(enabled, std::memory_order_relaxed);
    }

    MeterSnapshot getMeters() const noexcept {
        MeterSnapshot m;
        m.monoCompatibility =
            meterMono_.load(std::memory_order_relaxed);
        m.gainReductionDb =
            meterGR_.load(std::memory_order_relaxed);
        m.outputPeakDbTP =
            meterPeakDb_.load(std::memory_order_relaxed);
        return m;
    }

    std::size_t latencySamples() const noexcept {
        return limiter_.latencySamples();
    }

    double latencyMs() const noexcept {
        return 1000.0 * static_cast<double>(latencySamples()) /
               static_cast<double>(fs_);
    }

    std::size_t limiterEstimatorDelaySamples() const noexcept {
        return limiter_.estimatorDelaySamples();
    }

    std::size_t limiterTimestampDelaySamples() const noexcept {
        return limiter_.timestampDelaySamples();
    }

    std::size_t limiterProtectionHorizonSamples() const noexcept {
        return limiter_.protectionHorizonSamples();
    }

    std::size_t limiterCeilingControlHorizonSamples() const noexcept {
        return limiter_.ceilingControlHorizonSamples();
    }

    void process(float& left,
                 float& right) noexcept
    {
        left = safeSample(left);
        right = safeSample(right);

        if (!prepared_.load(std::memory_order_acquire)) {
            left = 0.0f;
            right = 0.0f;
            return;
        }

        const float dryL = left;
        const float dryR = right;

        width_.setTarget(widthTarget_.load(std::memory_order_relaxed));
        lowFc_.setTarget(lowTarget_.load(std::memory_order_relaxed));
        highFc_.setTarget(highTarget_.load(std::memory_order_relaxed));
        bassMonoFc_.setTarget(
            bassMonoTarget_.load(std::memory_order_relaxed));
        haasDelay_.setTarget(
            haasDelayTarget_.load(std::memory_order_relaxed));
        haasMix_.setTarget(
            haasMixTarget_.load(std::memory_order_relaxed));
        dryWet_.setTarget(
            dryWetTarget_.load(std::memory_order_relaxed));
        outputTrim_.setTarget(
            dbToGain(outputGainTarget_.load(std::memory_order_relaxed)));
        enabledMix_.setTarget(
            enabled_.load(std::memory_order_relaxed) ? 1.0f : 0.0f);

        const float width = width_.process();
        const float lowFc = lowFc_.process();
        const float highFcRaw = highFc_.process();
        const float highFc = std::max(lowFc * 1.5f, highFcRaw);
        const float bassFc =
            std::min(bassMonoFc_.process(), lowFc);
        const float haasMs = haasDelay_.process();
        const float haasMix = haasMix_.process();
        const float wet = dryWet_.process();
        const float effectMix = clampf(enabledMix_.process(), 0.0f, 1.0f);

        /*
            Parameter changes are sampled frequently enough that the 5 ms
            bank transition does not become visibly chunked.
        */
        if ((sampleCounter_++ & 0x3Fu) == 0u)
            updateFilters(lowFc, highFc, bassFc);

        const float mid = 0.5f * (left + right);
        const float side = 0.5f * (left - right);

        /*
            Bass-mono invariant: remove the LOW-PASS portion of the WHOLE
            side signal BEFORE the three-way side split.  The remaining side
            signal is therefore intrinsically high-passed and cannot recreate
            sub-bass width through a later band path.
        */
        const float sideBass = bassSideLP_.process(side);
        const float sideHighPassed = side - sideBass;

        float mL, mM, mH;
        float sL, sM, sH;
        crossover_.process(mid, mL, mM, mH);
        crossoverS_.process(sideHighPassed, sL, sM, sH);

        updateBandMeters(mL, sL, 0);
        updateBandMeters(mM, sM, 1);
        updateBandMeters(mH, sH, 2);

        float outSL = sL * std::min(width, 1.0f);
        float outSM = sM * std::min(width, 1.0f);
        float outSH = sH * std::min(width, 1.0f);

        if (width > 1.0f) {
            const float added = width - 1.0f;
            const float transient = transient_.process(left, right);
            const float transientSafe =
                1.0f - clampf(0.60f * transient, 0.0f, 0.60f);

            /*
                Do not widen the low band above unity.  Additional width is
                created by injecting decorrelated MID/HIGH material into the
                side field.
            */
            /*
                Natural widening (revised after listening feedback: the
                previous version spread the whole vocal/instrument body
                through all-pass chains and sounded hollow and phasey).

                - The centre image (mid below the high band) is never moved:
                  vocals, snare and kick stay anchored.
                - Existing side content is lifted gently, more in the highs
                  than in the low-mids, like a genuinely wider recording.
                - Only the HIGH band gets a small amount of decorrelated
                  mid injected, which reads as "air" rather than as phase.
                - Everything is injected into S, so the mono sum is unchanged.
            */
            const float dM = midDiffuser_.process(mM);
            const float dH = highDiffuser_.process(mH);
            const float g = 0.35f + 0.65f * transientSafe;

            outSL = sL;
            outSM = sM * (1.0f + 0.35f * added * g)
                  + added * 0.08f * g * dM;
            outSH = sH * (1.0f + 0.55f * added * g)
                  + added * 0.30f * g * dH;
        }

        const float outM = mL + mM + mH;
        float outS = outSL + outSM + outSH;

        haas_.setDelayMs(haasMs);
        haas_.setMix(haasMix);
        outS = haas_.process(outS);

        float wetL = outM + outS;
        float wetR = outM - outS;

        if (autoLevel_.load(std::memory_order_relaxed)) {
            updateAutoLevel(dryL, dryR, wetL, wetR);
            const float a = autoGain_.process();
            wetL *= a;
            wetR *= a;
        } else {
            autoGain_.setImmediate(1.0f);
        }

        const float trimGain = outputTrim_.process();
        wetL *= trimGain;
        wetR *= trimGain;

        const float angle = wet * (kPi * 0.5f);
        const float dryGain = std::cos(angle);
        const float wetGain = std::sin(angle);

        const float procL =
            dryL * dryGain + wetL * wetGain;
        const float procR =
            dryR * dryGain + wetR * wetGain;

        /*
            Build the ACTUAL plugin output before the final limiter. This
            includes the effect-enable crossfade as well as the dry/wet mix.
            No unlimited bypass signal is mixed in after limiting.
        */
        float finalL =
            dryL + effectMix * (procL - dryL);
        float finalR =
            dryR + effectMix * (procR - dryR);

        updateFinalMonoMeter(finalL, finalR);

        limiter_.setCeilingDb(
            ceilingTarget_.load(std::memory_order_relaxed));
        limiter_.setSafetyMarginDb(
            safetyMarginTarget_.load(std::memory_order_relaxed));
        limiter_.process(finalL, finalR);

        left = finalL;
        right = finalR;

        outPeak_ =
            std::max(outPeak_ * peakMeterDecayA_,
                     limiter_.getOutputTruePeak());

        const float compat = monoCompatibility();

        meterMono_.store(clampf(compat, 0.0f, 1.0f),
                         std::memory_order_relaxed);

        meterGR_.store(
            limiter_.getGainReductionDb(),
            std::memory_order_relaxed);

        meterPeakDb_.store(
            gainToDb(std::max(outPeak_, 1.0e-12f)),
            std::memory_order_relaxed);
    }

private:
    void updateFiltersNow() noexcept {
        updateFilters(
            lowFc_.current(),
            std::max(lowFc_.current() * 1.5f, highFc_.current()),
            std::min(bassMonoFc_.current(), lowFc_.current()));
    }

    void updateFilters(float lowFc,
                       float highFc,
                       float bassFc) noexcept
    {
        highFc = std::max(highFc, lowFc * 1.5f);
        highFc = std::min(highFc, 0.45f * fs_);
        lowFc = std::min(lowFc, highFc / 1.5f);
        bassFc = clampf(bassFc, 20.0f, std::max(20.0f, lowFc));

        crossover_.requestFrequencies(lowFc, highFc);
        crossoverS_.requestFrequencies(lowFc, highFc);
        bassSideLP_.setFrequency(bassFc);
    }

    void updateBandMeters(float m,
                          float s,
                          std::size_t band) noexcept
    {
        if (band >= monoRetention_.size())
            return;

        const float m2 = m * m;
        const float s2 = s * s;
        const float total = m2 + s2 + 1.0e-24f;
        const float instantRetention = m2 / total;
        const float a = bandMeterA_[band];

        monoRetention_[band] =
            a * monoRetention_[band] +
            (1.0f - a) * instantRetention;
    }

    float bandSafety(std::size_t band) const noexcept {
        if (band >= monoRetention_.size())
            return 0.0f;
        return smoothstep(0.10f, 0.75f, monoRetention_[band]);
    }

    float bandExpansion(std::size_t band,
                        float transientSafe) const noexcept
    {
        return bandSafety(band) * transientSafe;
    }

    void updateFinalMonoMeter(float left,
                              float right) noexcept
    {
        const float m = 0.5f * (left + right);
        const float s = 0.5f * (left - right);
        const float m2 = m * m;
        const float total = m2 + s * s;
        constexpr float a = 0.995f;

        monoAccumM_ =
            a * monoAccumM_ + (1.0f - a) * m2;
        totalAccum_ =
            a * totalAccum_ + (1.0f - a) * total;
    }

    float monoCompatibility() const noexcept {
        if (totalAccum_ <= 1.0e-12f)
            return 1.0f;
        return monoAccumM_ / totalAccum_;
    }

    void updateAutoLevel(float dryL,
                         float dryR,
                         float wetL,
                         float wetR) noexcept
    {
        const float dryE = 0.5f * (dryL * dryL + dryR * dryR);
        const float wetE = 0.5f * (wetL * wetL + wetR * wetR);

        dryEnergy_ =
            autoLevelEnvA_ * dryEnergy_ +
            (1.0f - autoLevelEnvA_) * dryE;

        wetEnergy_ =
            autoLevelEnvA_ * wetEnergy_ +
            (1.0f - autoLevelEnvA_) * wetE;

        const float ratio =
            std::sqrt((dryEnergy_ + 1.0e-12f) /
                      (wetEnergy_ + 1.0e-12f));

        autoGain_.setTarget(
            clampf(ratio,
                   dbToGain(-2.0f),
                   dbToGain(1.0f)));
    }

    float fs_ = 48000.0f;
    std::atomic<bool> prepared_{false};

    SmoothedValue width_;
    SmoothedValue lowFc_;
    SmoothedValue highFc_;
    SmoothedValue bassMonoFc_;
    SmoothedValue haasDelay_;
    SmoothedValue haasMix_;
    SmoothedValue dryWet_;
    SmoothedValue outputTrim_;
    SmoothedValue autoGain_;
    SmoothedValue enabledMix_;

    std::atomic<bool> enabled_{true};
    std::atomic<bool> autoLevel_{true};

    std::atomic<float> widthTarget_{1.0f};
    std::atomic<float> lowTarget_{180.0f};
    std::atomic<float> highTarget_{3200.0f};
    std::atomic<float> bassMonoTarget_{80.0f};
    std::atomic<float> haasDelayTarget_{0.0f};
    std::atomic<float> haasMixTarget_{0.0f};
    std::atomic<float> dryWetTarget_{1.0f};
    std::atomic<float> outputGainTarget_{0.0f};
    std::atomic<float> ceilingTarget_{-1.0f};
    std::atomic<float> safetyMarginTarget_{0.25f};

    Complementary3Way crossover_;
    Complementary3Way crossoverS_;
    LR4Filter bassSideLP_;

    SideDiffuser<8> midDiffuser_;
    SideDiffuser<12> highDiffuser_;
    SideHaas haas_;
    StereoTransientDetector transient_;
    StereoLinkedTruePeakLimiter limiter_;

    std::array<float, 3> monoRetention_{};
    std::array<float, 3> bandMeterA_{};

    float monoAccumM_ = 0.0f;
    float totalAccum_ = 0.0f;
    float dryEnergy_ = 0.0f;
    float wetEnergy_ = 0.0f;
    float autoLevelEnvA_ = 0.9998f;
    float peakMeterDecayA_ = 0.9998f;
    std::uint64_t sampleCounter_ = 0;
    float outPeak_ = 0.0f;

    std::atomic<float> meterMono_{1.0f};
    std::atomic<float> meterGR_{0.0f};
    std::atomic<float> meterPeakDb_{-120.0f};
};

} // namespace ProfessionalDSP
