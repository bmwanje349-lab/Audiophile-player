#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

namespace ImmersionDSP {
namespace {
constexpr int kHrirSourceTaps = 256;
constexpr int kBlock = 512;
constexpr int kFft = kBlock * 2;
constexpr int kMaxHrirTaps = 1024;
constexpr int kMaxRoomParts = 64;
constexpr float kPi = 3.14159265358979323846f;
constexpr float kInv32767 = 1.0f / 32767.0f;
constexpr std::size_t kHeaderBytes = 28;

struct Complex { float re = 0.0f; float im = 0.0f; };
inline Complex add(Complex a, Complex b) noexcept { return {a.re + b.re, a.im + b.im}; }
inline Complex multiply(Complex a, Complex b) noexcept {
    return {a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re};
}

void fft(std::array<Complex, kFft>& a, bool inverse) noexcept {
    for (int i = 1, j = 0; i < kFft; ++i) {
        int bit = kFft >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (int length = 2; length <= kFft; length <<= 1) {
        const float angle = (inverse ? 2.0f : -2.0f) * kPi / static_cast<float>(length);
        const Complex wlen{std::cos(angle), std::sin(angle)};
        for (int i = 0; i < kFft; i += length) {
            Complex w{1.0f, 0.0f};
            const int half = length >> 1;
            for (int j = 0; j < half; ++j) {
                const Complex u = a[i + j];
                const Complex v = multiply(a[i + j + half], w);
                a[i + j] = {u.re + v.re, u.im + v.im};
                a[i + j + half] = {u.re - v.re, u.im - v.im};
                w = multiply(w, wlen);
            }
        }
    }
    if (inverse) {
        const float scale = 1.0f / static_cast<float>(kFft);
        for (auto& v : a) { v.re *= scale; v.im *= scale; }
    }
}

uint32_t readU32Le(const uint8_t* p) noexcept {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
float readF32Le(const uint8_t* p) noexcept {
    const uint32_t bits = readU32Le(p);
    float out = 0.0f;
    std::memcpy(&out, &bits, sizeof(out));
    return out;
}
int16_t readI16Le(const uint8_t* p) noexcept {
    const uint16_t u = static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
    int16_t out = 0;
    std::memcpy(&out, &u, sizeof(out));
    return out;
}

bool checkHeader(const uint8_t* bytes, std::size_t size, const char* magic,
                 uint32_t* rate, uint32_t* count, uint32_t* receivers, uint32_t* taps) noexcept {
    if (!bytes || size < kHeaderBytes || std::memcmp(bytes, magic, 8) != 0) return false;
    if (readU32Le(bytes + 8) != 1) return false;
    *rate = readU32Le(bytes + 12);
    *count = readU32Le(bytes + 16);
    *receivers = readU32Le(bytes + 20);
    *taps = readU32Le(bytes + 24);
    return *rate == 48000;
}

struct Direction { float x = 1.0f, y = 0.0f, z = 0.0f; };

struct Assets {
    bool hrirReady = false;
    bool brirReady = false;
    uint32_t hrirCount = 0;
    uint32_t hrirTaps = kHrirSourceTaps;
    uint32_t brirTaps = 16384;
    std::vector<Direction> directions;
    std::array<int, 360> equator{};
    std::vector<int16_t> hrir;
    std::vector<float> brir;
    Assets() { equator.fill(-1); }
};

Assets gAssets;

void buildEquatorLookup(Assets& assets, const uint8_t* positions) {
    std::array<float, 360> bestError{};
    bestError.fill(std::numeric_limits<float>::max());
    for (uint32_t i = 0; i < assets.hrirCount; ++i) {
        const float azRaw = readF32Le(positions + static_cast<std::size_t>(i) * 8);
        const float el = readF32Le(positions + static_cast<std::size_t>(i) * 8 + 4);
        if (std::fabs(el) > 0.05f) continue;
        float az = std::fmod(azRaw, 360.0f);
        if (az < 0.0f) az += 360.0f;
        const int degree = static_cast<int>(std::lround(az)) % 360;
        float delta = std::fabs(az - static_cast<float>(degree));
        delta = std::min(delta, 360.0f - delta);
        if (delta < 0.05f && delta < bestError[degree]) {
            assets.equator[degree] = static_cast<int>(i);
            bestError[degree] = delta;
        }
    }
    // The SADIE II regular grid includes integer-degree samples at every horizon azimuth.
}

bool parseAssets(const uint8_t* hrirBytes, std::size_t hrirSize,
                 const uint8_t* brirBytes, std::size_t brirSize) {
    Assets parsed;
    uint32_t rate = 0, count = 0, receivers = 0, taps = 0;
    if (!checkHeader(hrirBytes, hrirSize, "D1HRIR1\0", &rate, &count, &receivers, &taps) ||
        receivers != 2 || taps != kHrirSourceTaps || count < 360 || count > 20000) return false;
    const std::size_t posBytes = static_cast<std::size_t>(count) * 2 * sizeof(float);
    const std::size_t irSamples = static_cast<std::size_t>(count) * receivers * taps;
    if (kHeaderBytes + posBytes + irSamples * sizeof(int16_t) != hrirSize) return false;
    parsed.hrirCount = count;
    parsed.directions.resize(count);
    const uint8_t* pos = hrirBytes + kHeaderBytes;
    for (uint32_t i = 0; i < count; ++i) {
        const float az = readF32Le(pos + static_cast<std::size_t>(i) * 8) * (kPi / 180.0f);
        const float el = readF32Le(pos + static_cast<std::size_t>(i) * 8 + 4) * (kPi / 180.0f);
        const float ce = std::cos(el);
        parsed.directions[i] = {ce * std::cos(az), ce * std::sin(az), std::sin(el)};
    }
    buildEquatorLookup(parsed, pos);
    for (int index : parsed.equator) if (index < 0) return false;
    const uint8_t* coefficients = pos + posBytes;
    parsed.hrir.resize(irSamples);
    for (std::size_t i = 0; i < irSamples; ++i) parsed.hrir[i] = readI16Le(coefficients + i * 2);
    parsed.hrirReady = true;

    if (!checkHeader(brirBytes, brirSize, "D1BRIR1\0", &rate, &count, &receivers, &taps) ||
        count != 2 || receivers != 2 || taps != 16384) return false;
    const std::size_t brirPosBytes = static_cast<std::size_t>(count) * 2 * sizeof(float);
    const std::size_t brirSamples = static_cast<std::size_t>(count) * receivers * taps;
    if (kHeaderBytes + brirPosBytes + brirSamples * sizeof(float) != brirSize) return false;
    const uint8_t* brirPos = brirBytes + kHeaderBytes;
    for (std::size_t i = 0; i < static_cast<std::size_t>(count) * 2; ++i) {
        if (!std::isfinite(readF32Le(brirPos + i * 4))) return false;
    }
    const uint8_t* brirCoefficients = brirPos + brirPosBytes;
    parsed.brir.resize(brirSamples);
    for (std::size_t i = 0; i < brirSamples; ++i) {
        parsed.brir[i] = readF32Le(brirCoefficients + i * 4);
        if (!std::isfinite(parsed.brir[i])) return false;
    }
    parsed.brirTaps = taps;
    parsed.brirReady = true;
    gAssets = std::move(parsed);
    return true;
}

inline float clamp01(float x) noexcept { return std::max(0.0f, std::min(1.0f, x)); }
inline float safe(float x) noexcept { return std::isfinite(x) ? x : 0.0f; }
inline float normalizeAzimuth(float az) noexcept {
    az = std::fmod(az, 360.0f);
    if (az < 0.0f) az += 360.0f;
    return az;
}
} // namespace

class Engine {
public:
    void prepare(int sr) noexcept {
        fs_ = std::max(8000, sr);
        activeHrirTaps_ = std::clamp(static_cast<int>(std::lround(kHrirSourceTaps * (static_cast<double>(fs_) / 48000.0))), 128, kMaxHrirTaps);
        reset();
        separation_ = 22.0f + 84.0f * space_.load(std::memory_order_relaxed);
        rebuild();
        prepareRoomConvolver();
        resetRoomState();
    }

    void reset() noexcept {
        histL_.fill(0.0f); histR_.fill(0.0f); histPos_ = 0;
        roomLpfL_ = roomLpfR_ = airLpfL_ = airLpfR_ = 0.0f;
        limiterGain_ = 1.0f;
        blockFill_ = 0; outputReadPos_ = kBlock; outputReady_ = false;
        blockInputL_.fill(0.0f); blockInputR_.fill(0.0f);
        blockHrtfL_.fill(0.0f); blockHrtfR_.fill(0.0f);
        roomOutputL_.fill(0.0f); roomOutputR_.fill(0.0f);
        resetRoomState();
    }

    void setEnabled(bool v) noexcept { enabled_.store(v, std::memory_order_relaxed); }
    void setSpace(float v) noexcept { space_.store(clamp01(v), std::memory_order_relaxed); }
    void setAmbience(float v) noexcept { amb_.store(clamp01(v), std::memory_order_relaxed); }
    void setAir(float v) noexcept { air_.store(clamp01(v), std::memory_order_relaxed); }
    void setBass(float v) noexcept { bass_.store(clamp01(v), std::memory_order_relaxed); }
    void setProfile(float v) noexcept { mix_.store(clamp01(v), std::memory_order_relaxed); }

    void process(const float* in, float* out, int frames) noexcept {
        if (!in || !out || frames <= 0) return;
        const bool enabled = enabled_.load(std::memory_order_relaxed);
        if (!enabled) {
            if (wasEnabled_) { reset(); wasEnabled_ = false; }
            std::memcpy(out, in, static_cast<std::size_t>(frames) * 2 * sizeof(float));
            return;
        }
        if (!wasEnabled_) { reset(); wasEnabled_ = true; }

        const float sp = space_.load(std::memory_order_relaxed);
        const float ambience = amb_.load(std::memory_order_relaxed);
        const float air = air_.load(std::memory_order_relaxed);
        const float bass = bass_.load(std::memory_order_relaxed);
        const float mix = mix_.load(std::memory_order_relaxed);
        const float separation = 22.0f + 84.0f * sp;
        if (std::fabs(separation - separation_) > 0.05f) {
            separation_ = separation;
            rebuild();
        }

        const float airAlpha = 1.0f - std::exp(-2.0f * kPi * 6500.0f / static_cast<float>(fs_));
        const float bassAlpha = 1.0f - std::exp(-2.0f * kPi * 120.0f / static_cast<float>(fs_));
        const float bassMix = 0.08f * bass;
        const float roomGain = 0.48f * std::sqrt(ambience);

        for (int i = 0; i < frames; ++i) {
            const float l = safe(in[2 * i]);
            const float r = safe(in[2 * i + 1]);
            histL_[histPos_] = l;
            histR_[histPos_] = r;
            float hrtfL = 0.0f;
            float hrtfR = 0.0f;
            int p = histPos_;
            for (int k = 0; k < activeHrirTaps_; ++k) {
                hrtfL += histL_[p] * hLL_[k] + histR_[p] * hRL_[k];
                hrtfR += histL_[p] * hLR_[k] + histR_[p] * hRR_[k];
                if (--p < 0) p = activeHrirTaps_ - 1;
            }
            if (++histPos_ >= activeHrirTaps_) histPos_ = 0;

            // Keep the high-frequency enhancer gentle; measured HRTFs provide the main pinna cues.
            airLpfL_ += airAlpha * (l - airLpfL_);
            airLpfR_ += airAlpha * (r - airLpfR_);
            float wetL = (1.0f - mix) * l + mix * hrtfL + 0.06f * air * (l - airLpfL_);
            float wetR = (1.0f - mix) * r + mix * hrtfR + 0.06f * air * (r - airLpfR_);

            // Low frequencies are gently centered instead of being widened or boosted.
            roomLpfL_ += bassAlpha * (l - roomLpfL_);
            roomLpfR_ += bassAlpha * (r - roomLpfR_);
            const float monoLow = 0.5f * (roomLpfL_ + roomLpfR_);
            wetL = (1.0f - bassMix) * wetL + bassMix * monoLow;
            wetR = (1.0f - bassMix) * wetR + bassMix * monoLow;

            blockInputL_[blockFill_] = l;
            blockInputR_[blockFill_] = r;
            blockHrtfL_[blockFill_] = wetL;
            blockHrtfR_[blockFill_] = wetR;
            ++blockFill_;
            if (blockFill_ == kBlock) {
                processRoomBlock();
                blockFill_ = 0;
                outputReadPos_ = 0;
                outputReady_ = true;
            }

            if (outputReady_ && outputReadPos_ < kBlock) {
                float ol = blockHrtfL_[outputReadPos_] + roomGain * roomOutputL_[outputReadPos_];
                float orr = blockHrtfR_[outputReadPos_] + roomGain * roomOutputR_[outputReadPos_];
                const float peak = std::max(std::fabs(ol), std::fabs(orr));
                const float target = peak > 0.97f ? 0.97f / peak : 1.0f;
                if (target < limiterGain_) limiterGain_ = target;
                else limiterGain_ += (1.0f - limiterGain_) * (1.0f - std::exp(-1.0f / (0.075f * static_cast<float>(fs_))));
                out[2 * i] = std::max(-0.97f, std::min(0.97f, ol * limiterGain_));
                out[2 * i + 1] = std::max(-0.97f, std::min(0.97f, orr * limiterGain_));
                ++outputReadPos_;
                if (outputReadPos_ >= kBlock) { outputReady_ = false; outputReadPos_ = kBlock; }
            } else {
                out[2 * i] = 0.0f;
                out[2 * i + 1] = 0.0f;
            }
        }
    }

private:
    void rebuild() noexcept {
        const float half = separation_ * 0.5f;
        directionFilter(half, 0, hLL_);
        directionFilter(half, 1, hLR_);
        directionFilter(-half, 0, hRL_);
        directionFilter(-half, 1, hRR_);
    }

    void directionFilter(float azimuth, int ear, std::array<float, kMaxHrirTaps>& out) noexcept {
        out.fill(0.0f);
        if (!gAssets.hrirReady || gAssets.hrirCount == 0) {
            out[0] = (azimuth >= 0.0f ? (ear == 0 ? 1.0f : 0.0f)
                                      : (ear == 1 ? 1.0f : 0.0f));
            return;
        }
        const float angle = normalizeAzimuth(azimuth);
        const int lo = static_cast<int>(std::floor(angle)) % 360;
        const int hi = (lo + 1) % 360;
        const float frac = angle - std::floor(angle);
        const int idx0 = gAssets.equator[lo];
        const int idx1 = gAssets.equator[hi];
        std::array<float, kHrirSourceTaps> raw{};
        for (int k = 0; k < kHrirSourceTaps; ++k) {
            const std::size_t off0 = (static_cast<std::size_t>(idx0) * 2 + ear) * kHrirSourceTaps + k;
            const std::size_t off1 = (static_cast<std::size_t>(idx1) * 2 + ear) * kHrirSourceTaps + k;
            const float c0 = static_cast<float>(gAssets.hrir[off0]) * kInv32767;
            const float c1 = static_cast<float>(gAssets.hrir[off1]) * kInv32767;
            raw[k] = c0 + frac * (c1 - c0);
        }
        const float inputRatePerOutput = 48000.0f / static_cast<float>(fs_);
        const float amplitudeScale = inputRatePerOutput;
        for (int k = 0; k < activeHrirTaps_; ++k) {
            const float sourcePos = static_cast<float>(k) * inputRatePerOutput;
            if (sourcePos >= static_cast<float>(kHrirSourceTaps - 1)) break;
            const int a = static_cast<int>(sourcePos);
            const int b = std::min(a + 1, kHrirSourceTaps - 1);
            const float f = sourcePos - static_cast<float>(a);
            out[k] = (raw[a] + f * (raw[b] - raw[a])) * amplitudeScale;
        }
    }

    void prepareRoomConvolver() noexcept {
        roomEnabled_ = false;
        roomParts_ = 0;
        roomTapsAtRate_ = 0;
        roomFilterSpectra_.clear();
        xHistory_.clear();
        // Cap the room response at 0.34 s at <=48 kHz; at 96 kHz the same tap budget
        // is 0.17 s, avoiding a fourfold CPU increase on high-rate source files.
        if (!gAssets.brirReady || fs_ < 22050 || fs_ > 96000) return;
        roomTapsAtRate_ = static_cast<int>(std::lround(static_cast<double>(gAssets.brirTaps) * std::min(fs_, 48000) / 48000.0));
        roomParts_ = (roomTapsAtRate_ + kBlock - 1) / kBlock;
        if (roomParts_ <= 0 || roomParts_ > kMaxRoomParts) { roomParts_ = 0; roomTapsAtRate_ = 0; return; }
        try {
            roomFilterSpectra_.assign(static_cast<std::size_t>(4) * roomParts_ * kFft, Complex{});
            xHistory_.assign(static_cast<std::size_t>(2) * roomParts_ * kFft, Complex{});
            std::array<Complex, kFft> work{};
            for (int source = 0; source < 2; ++source) {
                for (int ear = 0; ear < 2; ++ear) {
                    for (int part = 0; part < roomParts_; ++part) {
                        work.fill(Complex{});
                        for (int j = 0; j < kBlock; ++j) {
                            const int n = part * kBlock + j;
                            if (n >= roomTapsAtRate_) break;
                            const float sourcePos = static_cast<float>(n) * (48000.0f / static_cast<float>(fs_));
                            const int idx = static_cast<int>(sourcePos);
                            if (idx >= static_cast<int>(gAssets.brirTaps - 1)) continue;
                            const float f = sourcePos - static_cast<float>(idx);
                            const std::size_t base = (static_cast<std::size_t>(source) * 2 + ear) * gAssets.brirTaps;
                            const float c0 = gAssets.brir[base + idx];
                            const float c1 = gAssets.brir[base + idx + 1];
                            work[j].re = (c0 + f * (c1 - c0)) * (48000.0f / static_cast<float>(fs_));
                        }
                        fft(work, false);
                        const std::size_t dest = ((static_cast<std::size_t>(source) * 2 + ear) * roomParts_ + part) * kFft;
                        std::copy(work.begin(), work.end(), roomFilterSpectra_.begin() + static_cast<std::ptrdiff_t>(dest));
                    }
                }
            }
            roomEnabled_ = true;
        } catch (...) {
            roomFilterSpectra_.clear(); xHistory_.clear(); roomParts_ = 0; roomTapsAtRate_ = 0; roomEnabled_ = false;
        }
    }

    void resetRoomState() noexcept {
        std::fill(xHistory_.begin(), xHistory_.end(), Complex{});
        roomOverlapL_.fill(0.0f); roomOverlapR_.fill(0.0f);
        roomOutputL_.fill(0.0f); roomOutputR_.fill(0.0f);
        roomBlockCounter_ = 0;
    }

    void processRoomBlock() noexcept {
        if (!roomEnabled_ || roomParts_ <= 0) {
            roomOutputL_.fill(0.0f); roomOutputR_.fill(0.0f);
            return;
        }
        std::array<Complex, kFft> fftL{};
        std::array<Complex, kFft> fftR{};
        for (int n = 0; n < kBlock; ++n) {
            fftL[n].re = blockInputL_[n];
            fftR[n].re = blockInputR_[n];
        }
        const int slot = static_cast<int>(roomBlockCounter_ % static_cast<uint64_t>(roomParts_));
        fft(fftL, false); fft(fftR, false);
        const std::size_t histL = static_cast<std::size_t>(slot) * kFft;
        const std::size_t histR = (static_cast<std::size_t>(roomParts_) + slot) * kFft;
        std::copy(fftL.begin(), fftL.end(), xHistory_.begin() + static_cast<std::ptrdiff_t>(histL));
        std::copy(fftR.begin(), fftR.end(), xHistory_.begin() + static_cast<std::ptrdiff_t>(histR));

        std::array<Complex, kFft> sum{};
        for (int ear = 0; ear < 2; ++ear) {
            sum.fill(Complex{});
            for (int source = 0; source < 2; ++source) {
                const std::size_t hSource = static_cast<std::size_t>(source) * 2 + ear;
                const std::size_t xBase = static_cast<std::size_t>(source) * roomParts_ * kFft;
                for (int part = 0; part < roomParts_; ++part) {
                    int histSlot = slot - part;
                    if (histSlot < 0) histSlot += roomParts_;
                    const Complex* x = xHistory_.data() + xBase + static_cast<std::size_t>(histSlot) * kFft;
                    const Complex* h = roomFilterSpectra_.data() + (hSource * roomParts_ + part) * kFft;
                    for (int k = 0; k < kFft; ++k) sum[k] = add(sum[k], multiply(x[k], h[k]));
                }
            }
            fft(sum, true);
            auto& overlap = (ear == 0) ? roomOverlapL_ : roomOverlapR_;
            auto& output = (ear == 0) ? roomOutputL_ : roomOutputR_;
            for (int n = 0; n < kBlock; ++n) {
                output[n] = sum[n].re + overlap[n];
                overlap[n] = sum[n + kBlock].re;
            }
        }
        ++roomBlockCounter_;
    }

    int fs_ = 48000;
    int activeHrirTaps_ = kHrirSourceTaps;
    int histPos_ = 0;
    float separation_ = 0.0f;
    float limiterGain_ = 1.0f;
    float roomLpfL_ = 0.0f, roomLpfR_ = 0.0f, airLpfL_ = 0.0f, airLpfR_ = 0.0f;
    bool wasEnabled_ = true;
    std::array<float, kMaxHrirTaps> histL_{}, histR_{}, hLL_{}, hLR_{}, hRL_{}, hRR_{};
    std::atomic<bool> enabled_{true};
    std::atomic<float> space_{0.35f}, amb_{0.30f}, air_{0.30f}, bass_{0.30f}, mix_{0.75f};

    int blockFill_ = 0;
    int outputReadPos_ = kBlock;
    bool outputReady_ = false;
    std::array<float, kBlock> blockInputL_{}, blockInputR_{}, blockHrtfL_{}, blockHrtfR_{};
    std::array<float, kBlock> roomOutputL_{}, roomOutputR_{}, roomOverlapL_{}, roomOverlapR_{};

    bool roomEnabled_ = false;
    int roomTapsAtRate_ = 0;
    int roomParts_ = 0;
    uint64_t roomBlockCounter_ = 0;
    std::vector<Complex> roomFilterSpectra_;
    std::vector<Complex> xHistory_;
};
} // namespace ImmersionDSP

extern "C" {
int immersion_load_data(const uint8_t* hrir, std::size_t hrirSize,
                        const uint8_t* brir, std::size_t brirSize) {
    try { return ImmersionDSP::parseAssets(hrir, hrirSize, brir, brirSize) ? 1 : 0; }
    catch (...) { return 0; }
}
void* immersion_create() { try { return new ImmersionDSP::Engine(); } catch (...) { return nullptr; } }
void immersion_destroy(void* p) { delete static_cast<ImmersionDSP::Engine*>(p); }
void immersion_prepare(void* p, int sr) { if (p) static_cast<ImmersionDSP::Engine*>(p)->prepare(sr); }
void immersion_reset(void* p) { if (p) static_cast<ImmersionDSP::Engine*>(p)->reset(); }
void immersion_enabled(void* p, int v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setEnabled(v != 0); }
void immersion_space(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setSpace(v); }
void immersion_ambience(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setAmbience(v); }
void immersion_air(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setAir(v); }
void immersion_bass(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setBass(v); }
void immersion_profile(void* p, float v) { if (p) static_cast<ImmersionDSP::Engine*>(p)->setProfile(v); }
void immersion_process(void* p, const float* in, float* out, int n) { if (p) static_cast<ImmersionDSP::Engine*>(p)->process(in, out, n); }
}
