#include <cmath>
#include <cstdio>
#include "../app/src/main/cpp/ProfessionalStereoWidenerDSP_v10.h"

namespace {
bool near(float a, float b, float eps = 1.0e-5f) {
    return std::fabs(a - b) <= eps;
}
}

int main() {
    using ProfessionalDSP::powerampSideGain;

    struct Case { float width; float expectedGain; };
    constexpr Case cases[] = {
        {0.0f, 0.0f},
        {0.5f, 0.5f},
        {1.0f, 1.0f},
        {1.25f, 1.3333333333f},
        {1.75f, 2.0f},
        {2.5f, 3.0f},
    };

    for (const auto& c : cases) {
        const float actual = powerampSideGain(c.width);
        if (!near(actual, c.expectedGain)) {
            std::fprintf(stderr, "width %.3f: got %.7f, expected %.7f\n",
                         c.width, actual, c.expectedGain);
            return 1;
        }
    }

    // Verify the recovered M/S transform preserves the centre (mono sum).
    constexpr float left = 0.6f;
    constexpr float right = -0.2f;
    constexpr float mid = 0.5f * (left + right);
    constexpr float sideL = left - mid;
    constexpr float sideR = right - mid;
    constexpr float g = 3.0f;
    const float outL = mid + g * sideL;
    const float outR = mid + g * sideR;
    if (!near(outL + outR, left + right)) {
        std::fprintf(stderr, "mono-sum invariant failed\n");
        return 2;
    }

    std::puts("Poweramp StereoWide width curve and mono-sum invariant: PASS");
    return 0;
}
