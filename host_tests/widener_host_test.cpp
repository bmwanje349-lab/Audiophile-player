#include "ProfessionalStereoWidenerDSP_v10.h"
#include <cmath>
#include <cstdio>
#include <random>
#include <algorithm>

using namespace ProfessionalDSP;

namespace {

void configureForHaasRegression(StereoWidenerDSP& engine,
                                float delayMs,
                                float mix) {
    engine.prepare(44100.0);
    engine.setEnabled(true);
    engine.setWidth(1.0f);
    engine.setDryWet(1.0f);
    engine.setOutputGainDb(0.0f);
    engine.setOutputCeilingDb(0.0f);
    engine.setLimiterSafetyMarginDb(0.0f);
    engine.setAutoLevel(false);
    engine.setHaasDelayMs(delayMs);
    engine.setHaasMix(mix);
}

bool runHaasRegression() {
    constexpr double fs = 44100.0;
    constexpr int frames = 44100 * 3;
    constexpr int measureFrom = 44100 * 1;

    StereoWidenerDSP reference;
    StereoWidenerDSP zeroDelayFullMix;
    StereoWidenerDSP delayedZeroMix;
    StereoWidenerDSP delayedFullMix;
    configureForHaasRegression(reference, 0.0f, 0.0f);
    configureForHaasRegression(zeroDelayFullMix, 0.0f, 1.0f);
    configureForHaasRegression(delayedZeroMix, 8.0f, 0.0f);
    configureForHaasRegression(delayedFullMix, 8.0f, 1.0f);

    double referenceEnergy = 0.0;
    double zeroDelayDifference = 0.0;
    double zeroMixDifference = 0.0;
    double activeDifference = 0.0;
    double activeSideEnergy = 0.0;
    std::size_t measured = 0;

    for (int i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / fs;

        // A strictly mono, center-heavy signal deliberately exercises the
        // case the old side-only Haas stage could not affect.
        const float mono =
            0.16f * std::sin(2.0 * 3.141592653589793 * 220.0 * t) +
            0.10f * std::sin(2.0 * 3.141592653589793 * 437.0 * t + 0.23) +
            0.06f * std::sin(2.0 * 3.141592653589793 * 1331.0 * t + 0.71);

        float refL = mono, refR = mono;
        float zeroDL = mono, zeroDR = mono;
        float zeroML = mono, zeroMR = mono;
        float activeL = mono, activeR = mono;

        reference.process(refL, refR);
        zeroDelayFullMix.process(zeroDL, zeroDR);
        delayedZeroMix.process(zeroML, zeroMR);
        delayedFullMix.process(activeL, activeR);

        if (i < measureFrom) continue;

        referenceEnergy += 0.5 * (refL * refL + refR * refR);
        zeroDelayDifference +=
            0.5 * ((zeroDL - refL) * (zeroDL - refL) +
                   (zeroDR - refR) * (zeroDR - refR));
        zeroMixDifference +=
            0.5 * ((zeroML - refL) * (zeroML - refL) +
                   (zeroMR - refR) * (zeroMR - refR));
        activeDifference +=
            0.5 * ((activeL - refL) * (activeL - refL) +
                   (activeR - refR) * (activeR - refR));
        const double side = 0.5 * (activeL - activeR);
        activeSideEnergy += side * side;
        ++measured;
    }

    const double refRms =
        std::sqrt(referenceEnergy / std::max<std::size_t>(1, measured));
    const double zeroDelayRms =
        std::sqrt(zeroDelayDifference / std::max<std::size_t>(1, measured));
    const double zeroMixRms =
        std::sqrt(zeroMixDifference / std::max<std::size_t>(1, measured));
    const double activeDiffRms =
        std::sqrt(activeDifference / std::max<std::size_t>(1, measured));
    const double sideRms =
        std::sqrt(activeSideEnergy / std::max<std::size_t>(1, measured));

    std::printf(
        "Haas mono regression: ref RMS=%.6f, no-delay diff=%.8f, "
        "zero-mix diff=%.8f, active diff=%.6f (%.1f%%), side RMS=%.6f (%.1f%%)\n",
        refRms, zeroDelayRms, zeroMixRms, activeDiffRms,
        100.0 * activeDiffRms / std::max(1.0e-12, refRms),
        sideRms, 100.0 * sideRms / std::max(1.0e-12, refRms));

    const bool neutralAtZeroDelay = zeroDelayRms <= refRms * 0.001;
    const bool neutralAtZeroMix = zeroMixRms <= refRms * 0.001;
    const bool audibleAtNonzeroControls =
        activeDiffRms >= refRms * 0.025 &&
        sideRms >= refRms * 0.02;

    if (!neutralAtZeroDelay) {
        std::fprintf(stderr, "FAIL: Haas must remain neutral at zero delay.\n");
        return false;
    }
    if (!neutralAtZeroMix) {
        std::fprintf(stderr, "FAIL: Haas must remain neutral at zero mix.\n");
        return false;
    }
    if (!audibleAtNonzeroControls) {
        std::fprintf(stderr,
                     "FAIL: Haas controls did not create a measurable stereo cue "
                     "from centered mono input.\n");
        return false;
    }
    return true;
}

} // namespace

int main() {
    const double fs = 44100.0;
    for (float width : {1.0f, 1.5f, 2.0f, 2.5f}) {
        StereoWidenerDSP d;
        d.prepare(fs);
        d.setWidth(width);
        d.setOutputCeilingDb(-0.5f);
        d.setAutoLevel(true);
        std::mt19937 g(1);
        std::normal_distribution<float> n(0.0f, 0.12f);
        double sLL = 0.0, sRR = 0.0, sLR = 0.0, sM = 0.0, sIn = 0.0;
        const int nFrames = static_cast<int>(fs * 8.0);
        float lp = 0.0f;
        for (int i = 0; i < nFrames; ++i) {
            const float x = n(g);
            lp = 0.7f * lp + 0.3f * x;
            float left = lp + 0.01f * n(g);
            float right = lp + 0.01f * n(g);
            const float inL = left, inR = right;
            d.process(left, right);
            if (i > fs * 3.0) {
                sLL += left * left;
                sRR += right * right;
                sLR += left * right;
                const double mid = 0.5 * (left + right);
                sM += mid * mid;
                sIn += 0.25 * (inL + inR) * (inL + inR);
            }
        }
        std::printf("width %.1f  corr(L,R)=%.3f  monoLevel change=%.2f dB\n",
                    width, sLR / std::sqrt(sLL * sRR),
                    10.0 * std::log10(sM / sIn));
    }

    return runHaasRegression() ? 0 : 1;
}
