#include <cmath>
#include <cstdint>
#include <iostream>
#include <limits>
#include "../../app/src/main/cpp/ProfessionalStereoWidenerDSP_v10.h"

int main() {
    ProfessionalDSP::StereoWidenerDSP dsp;
    dsp.prepare(48000.0);
    dsp.setEnabled(true);
    dsp.setWidth(1.25f);
    dsp.setDryWet(1.0f);
    dsp.setOutputGainDb(0.0f);
    dsp.setOutputCeilingDb(-1.0f);
    dsp.setLimiterSafetyMarginDb(0.25f);
    dsp.setAutoLevel(true);

    const std::size_t n = 48000;
    double maxAbs = 0.0;
    double energy = 0.0;
    const float fs = 48000.0f;
    for (std::size_t i = 0; i < n; ++i) {
        const float phase = 2.0f * ProfessionalDSP::kPi * 997.0f * static_cast<float>(i) / fs;
        float l = 0.9f * std::sin(phase);
        float r = 0.9f * std::sin(phase + 0.2f);
        dsp.process(l, r);
        if (!std::isfinite(l) || !std::isfinite(r)) return 2;
        maxAbs = std::max(maxAbs, static_cast<double>(std::max(std::fabs(l), std::fabs(r))));
        energy += static_cast<double>(l) * l + static_cast<double>(r) * r;
    }

    const std::size_t latency = dsp.latencySamples();
    std::size_t tail = 0;
    for (std::size_t i = 0; i < latency; ++i) {
        float l = 0.0f, r = 0.0f;
        dsp.process(l, r);
        if (!std::isfinite(l) || !std::isfinite(r)) return 3;
        ++tail;
    }

    const auto m = dsp.getMeters();
    std::cout << "latency=" << latency
              << " tail=" << tail
              << " maxAbs=" << maxAbs
              << " energy=" << energy
              << " mono=" << m.monoCompatibility
              << " grDb=" << m.gainReductionDb
              << " peakDbTP=" << m.outputPeakDbTP << "\n";
    return 0;
}
