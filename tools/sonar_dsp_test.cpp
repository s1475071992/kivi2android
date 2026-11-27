#include "../app/src/main/cpp/sonar_dsp.h"
#include <cassert>
#include <iostream>
int main(){
    using namespace sonar;
    assert(delayFrames(0,3)==0);
    assert(std::abs(delayFrames(1,1)-280)<=1);
    assert(std::abs(delayFrames(2,3)-3*delayFrames(2,1))<=1);
    assert(doppler(0)==1 && doppler(4)>1 && doppler(-4)<1);
    assert(attenuation(1,true)>attenuation(4,true)&&attenuation(4,false)==1);
    assert(brightGain(1,1)>brightGain(0,1)&&brightGain(0,0)==1);
    assert(roughMod(0,1)==0 && roughMod(10,1)>roughMod(1,1)&&roughMod(10,0)==0);
    assert(periodFrames(12,9000)>=9000+PULSE+GUARD);
    int result[MAX],cursor=0;uint32_t seed=123;
    choose(4,2,0,cursor,seed,result);assert(result[0]==0&&result[1]==1);
    choose(4,2,0,cursor,seed,result);assert(result[0]==2&&result[1]==3);
    choose(4,2,2,cursor,seed,result);assert(result[0]==0&&result[1]==1);
    choose(4,4,1,cursor,seed,result);bool seen[4]={};for(int i=0;i<4;++i){assert(result[i]>=0&&result[i]<4&&!seen[result[i]]);seen[result[i]]=true;}
    float a[PULSE],b[PULSE];pulse(a,PULSE,0,1,0,1,1,seed);pulse(b,PULSE,10,0,15,1,1,seed);
    for(float* p:{a,b}){float energy=0;for(int i=0;i<PULSE;++i){assert(std::isfinite(p[i]));energy+=p[i]*p[i];}assert(std::abs(std::sqrt(energy/PULSE)-.23f)<.001f);assert(p[0]==0&&p[PULSE-1]==0);}
    std::cout<<"Sonar DSP tests passed\n";
}
