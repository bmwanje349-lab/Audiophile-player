#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstring>
#include "HrtfPresets.h"
namespace ImmersionDSP {
class Engine {
public:
 static constexpr int T=ImmersionHrtf::kTaps,A=ImmersionHrtf::kAngles;
 void prepare(int sr) noexcept { reset(); fs_=std::max(8000,sr); rebuild(); }
 void reset() noexcept { histL_.fill(0);histR_.fill(0);pos_=0;roomL_=roomR_=prevL_=prevR_=bassL_=bassR_=0; }
 void setEnabled(bool v) noexcept{enabled_.store(v);}
 void setSpace(float v) noexcept{space_.store(clamp(v));}
 void setAmbience(float v) noexcept{amb_.store(clamp(v));}
 void setAir(float v) noexcept{air_.store(clamp(v));}
 void setBass(float v) noexcept{bass_.store(clamp(v));}
 void setProfile(float v) noexcept{profileTarget_.store(clamp(v));}
 void process(const float* in,float* out,int n) noexcept {
  if(!in||!out||n<=0)return;
  if(!enabled_.load()){std::memcpy(out,in,(size_t)n*2*sizeof(float));return;}
  const float sp=space_.load(),am=amb_.load(),air=air_.load(),ba=bass_.load(),pr=profileTarget_.load();
  const float sep=18.f+72.f*sp;
  if(std::fabs(sep-separation_)>.05f||std::fabs(pr-profile_)>.005f){separation_=sep;profile_=pr;rebuild();}
  for(int i=0;i<n;++i){
   float L=safe(in[2*i]),R=safe(in[2*i+1]);histL_[pos_]=L;histR_[pos_]=R;
   float wl=0,wr=0;
   for(int k=0;k<T;++k){int p=pos_-k;if(p<0)p+=T;wl+=histL_[p]*hLL_[k]+histR_[p]*hRL_[k];wr+=histL_[p]*hLR_[k]+histR_[p]*hRR_[k];}
   float w=.45f+.55f*sp,oL=(1-.22f*w)*L+.22f*w*wl,oR=(1-.22f*w)*R+.22f*w*wr;
   float mid=.5f*(L+R);roomL_=.985f*roomL_+.015f*mid;roomR_=.983f*roomR_+.017f*mid;
   float rm=.035f*am;oL+=rm*(roomL_-roomR_);oR+=rm*(roomR_-roomL_);
   float hpL=L-prevL_,hpR=R-prevR_;prevL_=L;prevR_=R;oL+=.035f*air*hpL;oR+=.035f*air*hpR;
   bassL_+=.018f*(L-bassL_);bassR_+=.018f*(R-bassR_);float bm=.12f*ba,low=.5f*(bassL_+bassR_);oL=(1-bm)*oL+bm*low;oR=(1-bm)*oR+bm*low;
   out[2*i]=clamp(oL,-1,1);out[2*i+1]=clamp(oR,-1,1);if(++pos_>=T)pos_=0;
  }
 }
private:
 static float clamp(float x,float lo=0,float hi=1){return std::max(lo,std::min(hi,x));}
 static float safe(float x){return std::isfinite(x)?x:0;}
 void interp(const int16_t*t,float az,int ear,float*d){float a=std::fmod(az+360.f,360.f)/60.f;int i0=(int)std::floor(a)%A,i1=(i0+1)%A;float f=a-std::floor(a);const int16_t*p0=t+(i0*2+ear)*T,*p1=t+(i1*2+ear)*T;for(int k=0;k<T;++k)d[k]=((1-f)*p0[k]+f*p1[k])/32767.f;}
 void rebuild(){float dLL[T],dLR[T],dRL[T],dRR[T],hLL[T],hLR[T],hRL[T],hRR[T];float half=separation_*.5f;interp(ImmersionHrtf::kD1,half,0,dLL);interp(ImmersionHrtf::kD1,half,1,dLR);interp(ImmersionHrtf::kD1,-half,0,dRL);interp(ImmersionHrtf::kD1,-half,1,dRR);interp(ImmersionHrtf::kH10,half,0,hLL);interp(ImmersionHrtf::kH10,half,1,hLR);interp(ImmersionHrtf::kH10,-half,0,hRL);interp(ImmersionHrtf::kH10,-half,1,hRR);for(int k=0;k<T;++k){hLL_[k]=(1-profile_)*dLL[k]+profile_*hLL[k];hLR_[k]=(1-profile_)*dLR[k]+profile_*hLR[k];hRL_[k]=(1-profile_)*dRL[k]+profile_*hRL[k];hRR_[k]=(1-profile_)*dRR[k]+profile_*hRR[k];}}
 int fs_=48000,pos_=0;float separation_=45,profile_=.5,roomL_=0,roomR_=0,prevL_=0,prevR_=0,bassL_=0,bassR_=0;
 std::array<float,T>histL_{},histR_{},hLL_{},hLR_{},hRL_{},hRR_{};
 std::atomic<bool>enabled_{true};std::atomic<float>space_{.35f},amb_{.30f},air_{.30f},bass_{.30f},profileTarget_{.5f};
};
}
extern "C" {
void* immersion_create(){return new ImmersionDSP::Engine();}
void immersion_destroy(void*p){delete static_cast<ImmersionDSP::Engine*>(p);}
void immersion_prepare(void*p,int sr){if(p)static_cast<ImmersionDSP::Engine*>(p)->prepare(sr);}
void immersion_reset(void*p){if(p)static_cast<ImmersionDSP::Engine*>(p)->reset();}
void immersion_enabled(void*p,int v){if(p)static_cast<ImmersionDSP::Engine*>(p)->setEnabled(v!=0);}
void immersion_space(void*p,float v){if(p)static_cast<ImmersionDSP::Engine*>(p)->setSpace(v);}
void immersion_ambience(void*p,float v){if(p)static_cast<ImmersionDSP::Engine*>(p)->setAmbience(v);}
void immersion_air(void*p,float v){if(p)static_cast<ImmersionDSP::Engine*>(p)->setAir(v);}
void immersion_bass(void*p,float v){if(p)static_cast<ImmersionDSP::Engine*>(p)->setBass(v);}
void immersion_profile(void*p,float v){if(p)static_cast<ImmersionDSP::Engine*>(p)->setProfile(v);}
void immersion_process(void*p,const float*in,float*out,int n){if(p)static_cast<ImmersionDSP::Engine*>(p)->process(in,out,n);}
}