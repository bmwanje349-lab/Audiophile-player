// Immersion engine v2: measured HRTF binaural convolution plus aligned dry path,
// early reflections, adjustable air/bass shaping and slow power-based gain matching.
// SADIE II HRTFs in HrtfPresets.h are sampled at 48 kHz; the tap response is
// linearly resampled during prepare() for the playback sample rate.
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "HrtfPresets.h"

namespace ImmersionDSP {
class Engine {
public:
    static constexpr int T = ImmersionHrtf::kTaps;
    static constexpr int A = ImmersionHrtf::kAngles;
    static constexpr int kMaxTaps = 512;
    static constexpr int kErSize = 8192;
    static constexpr int kErMask = kErSize - 1;
    static constexpr int kErTaps = 3;

    Engine() noexcept { computeBulkDelay48(); }

    void prepare(int sr) noexcept {
        fs_ = std::max(8000, sr);
        taps_ = std::max(32, std::min(kMaxTaps,
            static_cast<int>(std::lround(static_cast<double>(T) * fs_ / 48000.0))));
        bulk_ = std::max(0, std::min(taps_ - 1,
            static_cast<int>(std::lround(static_cast<double>(bulk48_) * fs_ / 48000.0))));
        updateCoefs();
        reset();
        rebuild();
    }

    void reset() noexcept {
        histL_.fill(0.0f);
        histR_.fill(0.0f);
        er_.fill(0.0f);
        pos_ = 0;
        erPos_ = 0;
        erLp_ = erLo_ = lpAL_ = lpAR_ = lpBL_ = lpBR_ = sLo_ = 0.0f;
        pin_ = pout_ = 0.0f;
        gain_ = 1.0f;
        snap_ = true;
    }

    void setEnabled(bool v) noexcept { enabled_.store(v); }
    void setSpace(float v) noexcept { space_.store(clamp(v)); }
    void setAmbience(float v) noexcept { amb_.store(clamp(v)); }
    void setAir(float v) noexcept { air_.store(clamp(v)); }
    void setBass(float v) noexcept { bass_.store(clamp(v)); }
    void setProfile(float v) noexcept { profileTarget_.store(clamp(v)); }

    void process(const float* in, float* out, int n) noexcept {
        if (!in || !out || n <= 0) return;
        if (!enabled_.load()) {
            std::memcpy(out, in, static_cast<std::size_t>(n) * 2 * sizeof(float));
            wasOn_ = false;
            return;
        }
        if (!wasOn_) {
            reset();
            wasOn_ = true;
        }

        const float sp = space_.load();
        const float am = amb_.load();
        const float air = air_.load();
        const float ba = bass_.load();
        const float pr = profileTarget_.load();
        const float sep = 60.0f + 60.0f * sp;
        if (std::fabs(sep - separation_) > 0.05f ||
            std::fabs(pr - profile_) > 0.005f) {
            separation_ = sep;
            profile_ = pr;
            rebuild();
        }

        const float tMix = 0.20f + 0.60f * sp;
        const float tEr = 0.90f * am;
        const float tAir = 0.70f * air;
        const float tBm = 0.50f * ba;
        const float tBoost = 0.30f * ba;
        const float tSide = 0.30f + 0.90f * sp;
        if (snap_) {
            mix_ = tMix;
            er_g_ = tEr;
            airG_ = tAir;
            bm_ = tBm;
            boost_ = tBoost;
            side_ = tSide;
            snap_ = false;
        }

        constexpr float sgnL[kErTaps] = {1.0f, -1.0f, 1.0f};
        constexpr float sgnR[kErTaps] = {-1.0f, 1.0f, -1.0f};
        for (int i = 0; i < n; ++i) {
            mix_ += kSmooth * (tMix - mix_);
            er_g_ += kSmooth * (tEr - er_g_);
            airG_ += kSmooth * (tAir - airG_);
            bm_ += kSmooth * (tBm - bm_);
            boost_ += kSmooth * (tBoost - boost_);
            side_ += kSmooth * (tSide - side_);

            const float inL = safe(in[2 * i]);
            const float inR = safe(in[2 * i + 1]);
            const float mid0 = 0.5f * (inL + inR);
            const float side0 = 0.5f * (inL - inR);
            sLo_ += cSide_ * (side0 - sLo_);
            const float side1 = side0 + side_ * (side0 - sLo_);
            const float L = mid0 + side1;
            const float R = mid0 - side1;

            pos_ = (pos_ == 0) ? taps_ - 1 : pos_ - 1;
            histL_[pos_] = histL_[pos_ + taps_] = L;
            histR_[pos_] = histR_[pos_ + taps_] = R;
            const float* hl = &histL_[pos_];
            const float* hr = &histR_[pos_];

            float wl = 0.0f;
            float wr = 0.0f;
            for (int k = 0; k < taps_; ++k) {
                wl += hl[k] * hLL_[k] + hr[k] * hRL_[k];
                wr += hl[k] * hLR_[k] + hr[k] * hRR_[k];
            }

            // Align the direct sound with the main arrival of the measured HRIRs
            // before mixing; this reduces comb filtering between direct and wet paths.
            float oL = (1.0f - mix_) * hl[bulk_] + mix_ * wl;
            float oR = (1.0f - mix_) * hr[bulk_] + mix_ * wr;

            const float mid = mid0;
            erLp_ += cErLp_ * (mid - erLp_);
            erLo_ += cErLo_ * (mid - erLo_);
            erPos_ = (erPos_ + 1) & kErMask;
            er_[erPos_] = erLp_ - erLo_;
            float eL = 0.0f;
            float eR = 0.0f;
            for (int j = 0; j < kErTaps; ++j) {
                eL += sgnL[j] * kErGain[j] * er_[(erPos_ + kErSize - dL_[j]) & kErMask];
                eR += sgnR[j] * kErGain[j] * er_[(erPos_ + kErSize - dR_[j]) & kErMask];
            }
            oL += er_g_ * eL;
            oR += er_g_ * eR;

            // Smooth high-shelf-like air enhancement above about 4.5 kHz.
            lpAL_ += cAir_ * (oL - lpAL_);
            lpAR_ += cAir_ * (oR - lpAR_);
            oL += airG_ * (oL - lpAL_);
            oR += airG_ * (oR - lpAR_);

            // Low-band centre focus with a restrained shared bass-weight component.
            lpBL_ += cBass_ * (oL - lpBL_);
            lpBR_ += cBass_ * (oR - lpBR_);
            const float low = 0.5f * (lpBL_ + lpBR_);
            oL += bm_ * (low - lpBL_) + boost_ * low;
            oR += bm_ * (low - lpBR_) + boost_ * low;

            pin_ += cPow_ * ((inL * inL + inR * inR) - pin_);
            pout_ += cPow_ * ((oL * oL + oR * oR) - pout_);
            const float targetGain = pin_ > 1.0e-7f
                ? clamp(std::sqrt(pin_ / (pout_ + 1.0e-12f)), 0.5f, 1.5f)
                : 1.0f;
            gain_ += cGain_ * (targetGain - gain_);

            out[2 * i] = softClip(oL * gain_);
            out[2 * i + 1] = softClip(oR * gain_);
        }
    }

private:
    static constexpr float kSmooth = 0.002f;
    static constexpr float kErGain[kErTaps] = {0.50f, 0.35f, 0.25f};
    static constexpr float kErMsL[kErTaps] = {7.9f, 15.1f, 26.3f};
    static constexpr float kErMsR[kErTaps] = {10.3f, 19.7f, 31.7f};

    static float clamp(float x, float lo = 0.0f, float hi = 1.0f) noexcept {
        return std::max(lo, std::min(hi, x));
    }
    static float safe(float x) noexcept { return std::isfinite(x) ? x : 0.0f; }
    static float softClip(float x) noexcept {
        constexpr float k = 0.85f;
        const float a = std::fabs(x);
        if (a <= k) return x;
        const float s = k + (1.0f - k) * std::tanh((a - k) / (1.0f - k));
        return x < 0.0f ? -s : s;
    }

    const std::int16_t* angleResponse(bool h10, int angle) const noexcept {
        if (angle == 1) return h10 ? ImmersionHrtf::kH10_60.data() : ImmersionHrtf::kD1_60.data();
        if (angle == 5) return h10 ? ImmersionHrtf::kH10_300.data() : ImmersionHrtf::kD1_300.data();
        return h10 ? ImmersionHrtf::kH10_0.data() : ImmersionHrtf::kD1_0.data();
    }

    // Interpolate azimuth and resample the measured 48 kHz impulse responses
    // to the current PCM rate. This is done only while rebuilding coefficients.
    void interp(bool h10, float az, int ear, float* dst) noexcept {
        float a = std::fmod(az + 360.0f, 360.0f) / 60.0f;
        const int i0 = static_cast<int>(std::floor(a)) % A;
        const int i1 = (i0 + 1) % A;
        const float f = a - std::floor(a);
        const std::int16_t* p0 = angleResponse(h10, i0) + ear * T;
        const std::int16_t* p1 = angleResponse(h10, i1) + ear * T;
        const float sourceStep = 48000.0f / static_cast<float>(fs_);
        for (int k = 0; k < taps_; ++k) {
            const float sourcePosition = static_cast<float>(k) * sourceStep;
            const int lo = std::min(T - 1, static_cast<int>(sourcePosition));
            const int hi = std::min(T - 1, lo + 1);
            const float frac = sourcePosition - static_cast<float>(lo);
            const float v0 = (1.0f - frac) * static_cast<float>(p0[lo]) + frac * static_cast<float>(p0[hi]);
            const float v1 = (1.0f - frac) * static_cast<float>(p1[lo]) + frac * static_cast<float>(p1[hi]);
            dst[k] = ((1.0f - f) * v0 + f * v1) / 32767.0f;
        }
        std::fill(dst + taps_, dst + kMaxTaps, 0.0f);
    }

    void computeBulkDelay48() noexcept {
        int sum = 0;
        for (int h10 = 0; h10 < 2; ++h10) {
            for (int ear = 0; ear < 2; ++ear) {
                const std::int16_t* p = angleResponse(h10 != 0, 0) + ear * T;
                int best = 0;
                int bestValue = 0;
                for (int k = 0; k < T; ++k) {
                    const int value = std::abs(static_cast<int>(p[k]));
                    if (value > bestValue) { bestValue = value; best = k; }
                }
                sum += best;
            }
        }
        bulk48_ = std::max(0, std::min(T - 1, (sum + 2) / 4));
    }

    void updateCoefs() noexcept {
        const float fs = static_cast<float>(fs_);
        constexpr float tau = 6.28318530718f;
        const auto lp = [fs](float hz) { return 1.0f - std::exp(-tau * hz / fs); };
        cSide_ = lp(250.0f);
        cAir_ = lp(4500.0f);
        cBass_ = lp(130.0f);
        cErLp_ = lp(5500.0f);
        cErLo_ = lp(180.0f);
        cPow_ = 1.0f - std::exp(-1.0f / (0.40f * fs));
        cGain_ = 1.0f - std::exp(-1.0f / (0.15f * fs));
        for (int j = 0; j < kErTaps; ++j) {
            dL_[j] = std::max(1, std::min(kErSize - 1, static_cast<int>(std::lround(kErMsL[j] * 0.001f * fs))));
            dR_[j] = std::max(1, std::min(kErSize - 1, static_cast<int>(std::lround(kErMsR[j] * 0.001f * fs))));
        }
    }

    void rebuild() noexcept {
        std::array<float, kMaxTaps> dLL{}, dLR{}, dRL{}, dRR{}, hLL{}, hLR{}, hRL{}, hRR{};
        const float half = std::min(60.0f, separation_ * 0.5f);
        interp(false, half, 0, dLL.data()); interp(false, half, 1, dLR.data());
        interp(false, -half, 0, dRL.data()); interp(false, -half, 1, dRR.data());
        interp(true, half, 0, hLL.data()); interp(true, half, 1, hLR.data());
        interp(true, -half, 0, hRL.data()); interp(true, -half, 1, hRR.data());
        double energyL = 0.0;
        double energyR = 0.0;
        for (int k = 0; k < taps_; ++k) {
            hLL_[k] = (1.0f - profile_) * dLL[k] + profile_ * hLL[k];
            hLR_[k] = (1.0f - profile_) * dLR[k] + profile_ * hLR[k];
            hRL_[k] = (1.0f - profile_) * dRL[k] + profile_ * hRL[k];
            hRR_[k] = (1.0f - profile_) * dRR[k] + profile_ * hRR[k];
            energyL += static_cast<double>(hLL_[k]) * hLL_[k] + static_cast<double>(hRL_[k]) * hRL_[k];
            energyR += static_cast<double>(hLR_[k]) * hLR_[k] + static_cast<double>(hRR_[k]) * hRR_[k];
        }
        const double g = std::sqrt(0.5 * (energyL + energyR));
        const float scale = g > 1.0e-6 ? static_cast<float>(1.0 / g) : 1.0f;
        for (int k = 0; k < taps_; ++k) {
            hLL_[k] *= scale; hLR_[k] *= scale;
            hRL_[k] *= scale; hRR_[k] *= scale;
        }
        for (int k = taps_; k < kMaxTaps; ++k) {
            hLL_[k] = hLR_[k] = hRL_[k] = hRR_[k] = 0.0f;
        }
    }

    int fs_ = 48000;
    int taps_ = T;
    int pos_ = 0;
    int erPos_ = 0;
    int bulk48_ = 100;
    int bulk_ = 100;
    int dL_[kErTaps] = {380, 725, 1262};
    int dR_[kErTaps] = {494, 946, 1522};
    float separation_ = 81.0f;
    float profile_ = 0.5f;
    float cSide_ = 0.03f, cAir_ = 0.4f, cBass_ = 0.017f, cErLp_ = 0.5f, cErLo_ = 0.02f;
    float cPow_ = 0.0001f, cGain_ = 0.0001f;
    float erLp_ = 0.0f, erLo_ = 0.0f, lpAL_ = 0.0f, lpAR_ = 0.0f;
    float lpBL_ = 0.0f, lpBR_ = 0.0f, sLo_ = 0.0f;
    float pin_ = 0.0f, pout_ = 0.0f, gain_ = 1.0f;
    float mix_ = 0.41f, er_g_ = 0.27f, airG_ = 0.21f, bm_ = 0.15f, boost_ = 0.09f, side_ = 0.615f;
    bool snap_ = true, wasOn_ = false;
    std::array<float, 2 * kMaxTaps> histL_{}, histR_{};
    std::array<float, kMaxTaps> hLL_{}, hLR_{}, hRL_{}, hRR_{};
    std::array<float, kErSize> er_{};
};
} // namespace ImmersionDSP

extern "C" {
void* immersion_create() { return new ImmersionDSP::Engine(); }
void immersion_destroy(void* p) { delete static_cast<ImmersionDSP::Engine*>(p); }
void immersion_prepare(void* p, int sr) { if (p) static_cast<ImmersionDSP::Engine*>(p)->prepare(sr); }
void immersion_reset(void* p) { if (p) static_cast<ImmersionDSP::Engine*>(p)->reset(); }
void immersion_enabled(void* p, int v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setEnabled(v != 0); }
void immersion_space(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setSpace(v); }
void immersion_ambience(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setAmbience(v); }
void immersion_air(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setAir(v); }
void immersion_bass(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setBass(v); }
void immersion_profile(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setProfile(v); }
void immersion_process(void* p, const float* in, float* out, int n) {
    if (p) static_cast<ImmersionDSP::Engine*>(p)->process(in, out, n);
}
}
