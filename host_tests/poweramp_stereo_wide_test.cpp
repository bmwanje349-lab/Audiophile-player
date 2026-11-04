#include "ProfessionalStereoWidenerDSP_v10.h"
#include <cmath>
#include <cstdio>

using ProfessionalDSP::StereoWidenerDSP;

static bool near(float a, float b, float eps = 1.0e-6f) {
    return std::fabs(a - b) <= eps;
}

int main() {
    StereoWidenerDSP dsp;
    dsp.prepare(48000.0);
    dsp.setPowerampCompatible(true);

    // Input L=1, R=0 gives M=0.5 and S=0.5.
    // Recovered Poweramp map: g = 1 + 2*(sliderPercent/100).
    const float percentages[] = {0.0f, 25.0f, 50.0f, 75.0f, 100.0f};
    const float gains[] = {1.0f, 1.5f, 2.0f, 2.5f, 3.0f};

    for (int i = 0; i < 5; ++i) {
        dsp.reset();
        dsp.setPowerampWidthPercent(percentages[i]);
        float left = 1.0f;
        float right = 0.0f;
        dsp.process(left, right);
        const float expectedL = 0.5f + 0.5f * gains[i];
        const float expectedR = 0.5f - 0.5f * gains[i];
        if (!near(left, expectedL) || !near(right, expectedR)) {
            std::fprintf(stderr,
                "FAIL percent=%.1f g=%.1f got=(%.8f, %.8f) expected=(%.8f, %.8f)\n",
                percentages[i], gains[i], left, right, expectedL, expectedR);
            return 1;
        }
    }

    // Disabled mode must be a bit-transparent bypass.
    dsp.setEnabled(false);
    float left = 0.25f, right = -0.125f;
    dsp.process(left, right);
    if (!near(left, 0.25f) || !near(right, -0.125f)) {
        std::fprintf(stderr, "FAIL disabled bypass changed samples\n");
        return 2;
    }

    std::puts("PASS: Poweramp M/S equations, 0/25/50/75/100% mapping, and bypass");
    return 0;
}
