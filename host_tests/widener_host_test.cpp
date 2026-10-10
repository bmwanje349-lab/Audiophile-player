#include "ProfessionalStereoWidenerDSP_v10.h"
#include <cstdio>
#include <random>
#include <cmath>
using namespace std; using namespace ProfessionalDSP;
int main(){
  const double fs=44100;
  for(float w: {1.0f,1.5f,2.0f,2.5f}){
    StereoWidenerDSP d; d.prepare(fs); d.setWidth(w); d.setOutputCeilingDb(-0.5f); d.setAutoLevel(true);
    mt19937 g(1); normal_distribution<float> n(0,0.12f);
    double sLL=0,sRR=0,sLR=0,sM=0,sIn=0; int N=static_cast<int>(fs*8); float lp=0;
    for(int i=0;i<N;i++){
      float x=n(g); lp=0.7f*lp+0.3f*x;
      float L=lp+0.01f*n(g), R=lp+0.01f*n(g);
      float iL=L,iR=R; d.process(L,R);
      if(i>fs*3){ sLL+=L*L; sRR+=R*R; sLR+=L*R; double m=0.5*(L+R); sM+=m*m; sIn+=0.25*(iL+iR)*(iL+iR); }
    }
    printf("width %.1f  corr(L,R)=%.3f  monoLevel change=%.2f dB\\n",w,sLR/sqrt(sLL*sRR),10*log10(sM/sIn));
  }
}