#pragma once
#include "sonar_dsp.h"
namespace scan {
constexpr int MAX_CELLS=256, MAX_SLOT=9600;
inline int frames(float ms){return int(std::lround(std::clamp(ms,10.f,200.f)*48.f));}
inline int length(float ms,int slot){return std::clamp(int(std::lround(ms*48.f)),48,slot);}
inline void wave(float* out,int n,int type,float velocity,float incidence,float rough,float brightness,float strength){
    uint32_t seed=0x349241ab; // same texture for every cell
    if(type==0){sonar::pulse(out,n,velocity,incidence,rough,brightness,strength,seed);return;}
    float e=0,low=0,phase=0;
    const float hf=sonar::brightGain(incidence,brightness),mod=sonar::roughMod(rough,strength);
    for(int j=0;j<n;j++){
        float t=float(j)/sonar::SR,u=float(j)/n;
        phase+=2*sonar::PI*(350.f+6500.f*u)*sonar::doppler(velocity)/sonar::SR;
        float x=type==1?sonar::noise(seed):type==2?sonar::noise(seed)*std::exp(-6*u):.8f*std::sin(phase)+.2f*sonar::noise(seed);
        low+=.23f*(x-low);x=low+hf*(x-low);
        float ramp=sonar::clamp01(float(std::min(j,n-1-j))/std::min(96.f,n*.2f));
        x*=.5f-.5f*std::cos(sonar::PI*ramp);x*=1+mod*std::sin(2*sonar::PI*70*t);out[j]=x;e+=x*x;
    }
    float gain=.23f/std::sqrt(std::max(e/n,1e-8f));for(int i=0;i<n;i++)out[i]*=gain;
}
}
