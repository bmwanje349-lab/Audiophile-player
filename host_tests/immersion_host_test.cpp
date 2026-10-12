#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

extern "C" {
int immersion_load_data(const uint8_t*, std::size_t, const uint8_t*, std::size_t);
void* immersion_create();
void immersion_destroy(void*);
void immersion_prepare(void*, int);
void immersion_reset(void*);
void immersion_enabled(void*, int);
void immersion_space(void*, float);
void immersion_ambience(void*, float);
void immersion_air(void*, float);
void immersion_bass(void*, float);
void immersion_profile(void*, float);
void immersion_process(void*, const float*, float*, int);
}

static std::vector<uint8_t> read(const char* path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return {};
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(file)), {});
}

int main(int argc, char** argv) {
    const char* hrirPath = argc > 1 ? argv[1] : "app/src/main/assets/immersion/hrir_d1_48k_256.bin";
    const char* brirPath = argc > 2 ? argv[2] : "app/src/main/assets/immersion/brir_d1_48k_16384.bin";
    auto hrir = read(hrirPath);
    auto brir = read(brirPath);
    if (hrir.empty() || brir.empty() ||
        !immersion_load_data(hrir.data(), hrir.size(), brir.data(), brir.size())) {
        std::fprintf(stderr, "Failed to load validated SADIE II HRIR/BRIR assets\n");
        return 10;
    }

    void* engine = immersion_create();
    if (!engine) return 1;
    constexpr int frames = 4096;
    std::array<float, frames * 2> input{}, output{}, roomOff{}, roomOn{};
    for (int i = 0; i < frames; ++i) {
        input[2 * i] = 0.28f * std::sin(2.0f * 3.14159265358979323846f * 440.0f * i / 48000.0f);
        input[2 * i + 1] = 0.24f * std::sin(2.0f * 3.14159265358979323846f * 660.0f * i / 48000.0f);
    }

    immersion_prepare(engine, 48000);
    immersion_enabled(engine, 1);
    immersion_space(engine, 0.70f);
    immersion_ambience(engine, 0.70f);
    immersion_air(engine, 0.25f);
    immersion_bass(engine, 0.30f);
    immersion_profile(engine, 0.80f);
    immersion_process(engine, input.data(), output.data(), frames);
    double energy = 0.0, difference = 0.0;
    for (std::size_t i = 0; i < output.size(); ++i) {
        if (!std::isfinite(output[i])) { std::fprintf(stderr, "Non-finite sample at %zu\n", i); return 12; }
        if (std::fabs(output[i]) > 0.971f) { std::fprintf(stderr, "Output ceiling failed: %g\n", output[i]); return 13; }
        energy += static_cast<double>(output[i]) * output[i];
        const double d = static_cast<double>(output[i]) - input[i];
        difference += d * d;
    }
    if (energy < 1e-3 || difference < 1e-3) {
        std::fprintf(stderr, "Measured HRTF output is silent/unchanged: energy=%g diff=%g\n", energy, difference);
        return 14;
    }

    immersion_reset(engine);
    immersion_ambience(engine, 0.0f);
    immersion_process(engine, input.data(), roomOff.data(), frames);
    immersion_reset(engine);
    immersion_ambience(engine, 1.0f);
    immersion_process(engine, input.data(), roomOn.data(), frames);
    double roomDifference = 0.0;
    for (std::size_t i = 0; i < roomOn.size(); ++i) {
        if (!std::isfinite(roomOn[i])) { std::fprintf(stderr, "Non-finite room-convolver sample\n"); return 15; }
        const double d = static_cast<double>(roomOn[i]) - roomOff[i];
        roomDifference += d * d;
    }
    if (roomDifference < 1e-8) {
        std::fprintf(stderr, "Measured BRIR control did not change output: %g\n", roomDifference);
        return 16;
    }

    immersion_prepare(engine, 44100);
    std::array<float, frames * 2> at441{};
    immersion_process(engine, input.data(), at441.data(), frames);
    for (float sample : at441) {
        if (!std::isfinite(sample)) { std::fprintf(stderr, "44.1 kHz processing produced non-finite output\n"); return 17; }
    }

    immersion_reset(engine);
    immersion_enabled(engine, 0);
    output.fill(0.0f);
    immersion_process(engine, input.data(), output.data(), frames);
    if (std::memcmp(input.data(), output.data(), input.size() * sizeof(float)) != 0) {
        std::fprintf(stderr, "Disabled engine is not bit-exact pass-through\n");
        immersion_destroy(engine);
        return 18;
    }
    immersion_destroy(engine);
    std::puts("SADIE II measured HRIR + partitioned BRIR regression tests passed");
    return 0;
}
