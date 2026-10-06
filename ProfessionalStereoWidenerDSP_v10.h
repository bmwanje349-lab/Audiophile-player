#pragma once
#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace ProfessionalDSP {
inline float clampf(float x,float lo,float hi) noexcept{return std::max(lo,std::min(hi,x));}
inline float safeSample(float x) noexcept{return std::isfinite(x)?x:0.0f;}
inline float dbToGain(float db) noexcept{return std::pow(10.0f,db*0.05f);}
inline float gainToDb(float g) noexcept{return 20.0f*std::log10(std::max(g,1.0e-12f));}
inline float smoothstep(float a,float b,float x) noexcept{if(a==b)return x>=b?1.0f:0.0f;float t=clampf((x-a)/(b-a),0,1);return t*t*(3-2*t);}
constexpr float kTwoPi=6.28318530717958647692f;
class SmoothedValue{float a_=0.99f,current_=0,target_=0;public:void prepare(double fs,float ms,float init){float tau=std::max(1e-6f,ms*0.001f);a_=std::exp(-1.0f/(tau*std::max(1.0f,(float)fs)));current_=target_=init;}void setTarget(float v){target_=v;}void setImmediate(float v){current_=target_=v;}float process(){current_=target_+a_*(current_-target_);return current_;}float current()const{return current_;}};
class StereoTransientDetector{float fast_=0,slow_=0,fa_=0.99f,sa_=0.999f;bool primed_=false;public:void prepare(double fs){float f=std::max(1.0f,(float)fs);fa_=std::exp(-1.0f/(0.0025f*f));sa_=std::exp(-1.0f/(0.03f*f));reset();}void reset(){fast_=slow_=0;primed_=false;}float process(float l,float r){l=safeSample(l);r=safeSample(r);float e=0.5f*(l*l+r*r);if(!primed_){fast_=slow_=e;primed_=true;return 0;}fast_=e+fa_*(fast_-e);slow_=e+sa_*(slow_-e);return clampf((fast_/(slow_+1e-12f)-1)*1.75f,0,1);}};
class StereoLinkedTruePeakLimiter{public:static constexpr float kDefaultSafetyMarginDb=1.7f;void prepare(double fs){fs_=std::max(1.0f,(float)fs);reset();}void reset(){gain_=1;peak_=0;ceilingDb_=-1;}void setCeilingDb(float db){ceilingDb_=clampf(db,-12,0);}void setSafetyMarginDb(float){}void process(float& l,float& r){l=safeSample(l);r=safeSample(r);float ceil=dbToGain(ceilingDb_);float p=std::max(std::fabs(l),std::fabs(r));if(p>ceil&&p>1e-12f){float g=ceil/p;gain_=std::min(gain_,g);l*=g;r*=g;}else{gain_=std::min(1.0f,gain_+0.0005f);}peak_=std::max(peak_*0.999f,p*gain_);}float getOutputTruePeak()const{return peak_;}float getGainReductionDb()const{return std::max(0.0f,-gainToDb(gain_));}std::size_t latencySamples()const{return 0;}private:float fs_=48000,ceilingDb_=-1,gain_=1,peak_=0;};
}
