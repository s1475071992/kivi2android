#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
namespace sonar {
constexpr int SR=48000, BLOCK=512, PULSE=2400, TX=384, GUARD=3360, MAX=32;
constexpr float C=343.f, PI=3.14159265358979323846f, BASE=600.f;
inline float clamp01(float x){return std::clamp(x,0.f,1.f);}
inline int delayFrames(float d,float scale){return int(std::lround(2.f*std::max(0.f,d)*std::max(0.f,scale)*SR/C));}
inline float doppler(float v){v=std::clamp(v,-100.f,100.f);return (C+v)/(C-v);}
inline float attenuation(float d,bool on){return on?1.f/std::max(1.f,d):1.f;}
inline float brightGain(float incidence,float strength){return 1.f-.70f*clamp01(strength)*(1.f-clamp01(incidence));}
inline float roughMod(float mm,float strength){
    // Wavelength-scale scatter proxy, not a recovered material property.
    const float k=4.f*PI*std::max(0.f,mm)*.001f/(C/4000.f);
    return .85f*clamp01(strength)*(1.f-std::exp(-k*k));
}
inline int periodFrames(float rate,int maxDelay){
    int desired=int(std::ceil(SR/std::clamp(rate,.5f,12.f)));
    int safe=std::max(desired,maxDelay+PULSE+GUARD);
    return ((safe+BLOCK-1)/BLOCK)*BLOCK;
}
inline uint32_t nextRand(uint32_t& x){x^=x<<13;x^=x>>17;x^=x<<5;return x;}
inline float noise(uint32_t& r){return float(nextRand(r)>>8)/8388607.5f-1.f;}
inline void choose(int count,int active,int mode,int& cursor,uint32_t& seed,int* result){
    if(count<=0)return;
    active=std::clamp(active,1,count);
    if(mode==1){
        int order[MAX];for(int i=0;i<count;++i)order[i]=i;
        for(int i=0;i<active;++i){int j=i+int(nextRand(seed)%uint32_t(count-i));std::swap(order[i],order[j]);result[i]=order[i];}
    }else{
        for(int i=0;i<active;++i)result[i]=((mode==2?0:cursor)+i)%count;
        if(mode!=2)cursor=(cursor+active)%count;
    }
}
// Fixed-length, RMS-normalized broad-band sonar texture; frequency shift only from radial motion.
inline void pulse(float* out,int length,float velocity,float incidence,float mm,float brightness,float roughness,uint32_t& seed){
    const float omega=2.f*PI*BASE*doppler(velocity)/SR;
    const float hf=brightGain(incidence,brightness), mod=roughMod(mm,roughness);
    float low=0.f, energy=0.f;
    for(int j=0;j<length;++j){
        float t=omega*j;
        float x=.60f*std::sin(t)+.25f*std::sin(2*t)+.18f*std::sin(3*t)+.13f*std::sin(5*t)+.10f*std::sin(7*t)+.08f*std::sin(9*t)+.32f*noise(seed);
        low+=.23f*(x-low);
        x=low+hf*(x-low);
        float ramp=clamp01(float(std::min(j,length-1-j))/192.f);
        x*=.5f-.5f*std::cos(PI*ramp);
        x*=1.f+mod*std::sin(2.f*PI*70.f*j/SR);
        out[j]=x;energy+=x*x;
    }
    float scale=.23f/std::sqrt(std::max(energy/length,1e-8f));
    for(int j=0;j<length;++j)out[j]*=scale;
}
}
