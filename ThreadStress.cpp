#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <thread>
#include <vector>
#include "../android/src/main/cpp/PremiumVocalRemoverDSP.h"

int main() {
    ProfessionalDSP::PremiumVocalRemoverDSP d;
    d.prepare(48000.0);
    d.setNeuralStemMode(true);
    d.setDepth(0.8f);
    constexpr std::size_t N = 256;
    std::vector<float> l(N), r(N), v(N), vr(N), ol(N), orr(N);
    for (std::size_t i=0;i<N;++i) {
        l[i]=0.1f*std::sin(0.01f*static_cast<float>(i));
        r[i]=0.1f*std::cos(0.013f*static_cast<float>(i));
        v[i]=0.02f*std::sin(0.021f*static_cast<float>(i));
        vr[i]=v[i];
    }
    std::atomic<bool> go{true};
    std::thread writer([&]{
        for (int i=0;i<100000;++i) {
            const float x = static_cast<float>(i % 1000) / 999.0f;
            d.setDepth(x);
            d.setVocalFocus(1.0f-x);
            d.setTransientProtection(x);
            d.setVocalStemGainDb(-6.0f+12.0f*x);
            d.setDryWet(x);
            d.setOutputGainDb(-3.0f+6.0f*x);
            d.setOutputCeilingDb(-3.0f+2.0f*x);
            d.setNeuralStemMode((i & 1) != 0);
        }
        go.store(false, std::memory_order_release);
    });
    while (go.load(std::memory_order_acquire)) {
        d.processBlock(l.data(),r.data(),v.data(),vr.data(),ol.data(),orr.data(),N);
    }
    writer.join();
    return 0;
}
