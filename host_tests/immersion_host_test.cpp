#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>

extern "C" {
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

namespace {
constexpr int kFrames = 1024;
constexpr float kPi = 3.14159265358979323846f;

bool allFinite(const std::array<float, kFrames * 2>& samples) {
    for (float sample : samples) if (!std::isfinite(sample)) return false;
    return true;
}
}

int main() {
    void* engine = immersion_create();
    if (!engine) {
        std::fprintf(stderr, "immersion_create returned null\n");
        return 1;
    }

    std::array<float, kFrames * 2> input{};
    std::array<float, kFrames * 2> output{};
    for (int i = 0; i < kFrames; ++i) {
        input[2 * i] = 0.35f * std::sin(2.0f * kPi * 440.0f * i / 48000.0f);
        input[2 * i + 1] = 0.25f * std::sin(2.0f * kPi * 660.0f * i / 48000.0f);
    }

    immersion_prepare(engine, 48000);
    immersion_enabled(engine, 1);
    immersion_space(engine, 0.65f);
    immersion_ambience(engine, 0.30f);
    immersion_air(engine, 0.25f);
    immersion_bass(engine, 0.30f);
    immersion_profile(engine, 0.60f);
    immersion_process(engine, input.data(), output.data(), kFrames);

    if (!allFinite(output)) {
        std::fprintf(stderr, "immersion output contains NaN/Inf\n");
        immersion_destroy(engine);
        return 2;
    }

    double energy = 0.0;
    double difference = 0.0;
    for (std::size_t i = 0; i < output.size(); ++i) {
        energy += static_cast<double>(output[i]) * output[i];
        const double d = static_cast<double>(output[i]) - input[i];
        difference += d * d;
    }
    if (energy < 1e-5) {
        std::fprintf(stderr, "immersion output is unexpectedly silent\n");
        immersion_destroy(engine);
        return 3;
    }
    if (difference < 1e-9) {
        std::fprintf(stderr, "enabled immersion did not change the test signal\n");
        immersion_destroy(engine);
        return 4;
    }

    // Bypass must be a bit-exact dry pass-through.
    immersion_reset(engine);
    immersion_enabled(engine, 0);
    output.fill(0.0f);
    immersion_process(engine, input.data(), output.data(), kFrames);
    if (std::memcmp(input.data(), output.data(), input.size() * sizeof(float)) != 0) {
        std::fprintf(stderr, "disabled immersion is not bit-exact pass-through\n");
        immersion_destroy(engine);
        return 5;
    }

    immersion_destroy(engine);
    std::puts("Immersion native regression tests passed");
    return 0;
}
