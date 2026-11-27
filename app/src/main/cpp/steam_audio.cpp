#include <jni.h>
#include <phonon.h>
#include <array>
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <memory>

#include "sonar_dsp.h"
using namespace sonar;
struct Voice {
    IPLVector3 direction{0,0,-1};
    int delay=0;
    float gain=0;
    float samples[PULSE]{};
};
struct Engine {
    IPLContext context=nullptr;
    IPLHRTF hrtf=nullptr;
    std::array<IPLBinauralEffect,MAX> effects{};
    std::array<Voice,MAX> voices{};
    std::array<uint32_t,MAX> rng{};
    float mono[BLOCK]{},left[BLOCK]{},right[BLOCK]{},mixL[BLOCK]{},mixR[BLOCK]{},tx[TX]{};
    int count=0,cursor=0;
    int64_t clock=0,start=0,busyEnd=0,nextAuto=0;
    uint32_t serial=0,mask=0,randomSeed=0x12345678u,txSeed=0x123abc98u;
    bool pending=false,wasAuto=false,spatial=true,reference=true;
    float txGain=0;
    ~Engine(){
        for(auto& effect:effects)if(effect)iplBinauralEffectRelease(&effect);
        if(hrtf)iplHRTFRelease(&hrtf);if(context)iplContextRelease(&context);
    }
    bool init(const char* sofa){
        IPLContextSettings cs{};cs.version=STEAMAUDIO_VERSION;
        if(iplContextCreate(&cs,&context)!=IPL_STATUS_SUCCESS)return false;
        IPLAudioSettings as{};as.samplingRate=SR;as.frameSize=BLOCK;
        IPLHRTFSettings hs{};hs.type=sofa?IPL_HRTFTYPE_SOFA:IPL_HRTFTYPE_DEFAULT;
        hs.sofaFileName=sofa;hs.volume=1.f;
        if(iplHRTFCreate(context,&as,&hs,&hrtf)!=IPL_STATUS_SUCCESS)return false;
        IPLBinauralEffectSettings bs{};bs.hrtf=hrtf;
        for(int i=0;i<MAX;++i){
            if(iplBinauralEffectCreate(context,&as,&bs,&effects[i])!=IPL_STATUS_SUCCESS)return false;
            rng[i]=0x9e3779b9u*(i+1);
        }
        return true;
    }
    void begin(const float* points,int total,float volume,const float* p){
        int indices[MAX]{};
        count=std::clamp(int(p[1]),1,total);
        choose(total,count,int(p[2]),cursor,randomSeed,indices);
        mask=0;serial++;start=clock;spatial=p[3]>.5f;reference=p[6]>.5f;
        float safeVol=clamp01(volume),norm=.65f*safeVol/std::sqrt(float(count));
        txGain=.25f*safeVol;
        int maxDelay=0;
        for(int i=0;i<total;++i){
            const float* q=points+5*i;
            float d=std::sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]);
            maxDelay=std::max(maxDelay,delayFrames(d,p[5]));
        }
        for(int i=0;i<count;++i){
            int index=indices[i];mask|=(uint32_t(1)<<index);
            const float* q=points+5*index;float x=q[0],y=q[1],z=q[2];
            float d=std::sqrt(x*x+y*y+z*z);auto& v=voices[i];
            v.direction=d>.0001f?IPLVector3{x/d,y/d,-z/d}:IPLVector3{0,0,-1};
            v.delay=delayFrames(d,p[5]);v.gain=norm*attenuation(d,p[4]>.5f);
            iplBinauralEffectReset(effects[i]);
            pulse(v.samples,PULSE,p[7],q[3],q[4],p[8],p[9],rng[index]);
        }
        if(reference)pulse(tx,TX,0,1,0,0,0,txSeed);
        busyEnd=start+maxDelay+PULSE+GUARD;
        nextAuto=start+periodFrames(p[0],maxDelay);
    }
    uint64_t render(const float* points,int total,float volume,const float* p,bool manual,jshort* pcm){
        pending=pending||manual;
        bool automatic=p[10]>.5f;
        if(automatic&&!wasAuto)nextAuto=clock;
        wasAuto=automatic;
        if(total>0&&clock>=busyEnd&&(pending||(automatic&&clock>=nextAuto))){
            begin(points,total,volume,p);pending=false;
        }
        std::fill_n(mixL,BLOCK,0.f);std::fill_n(mixR,BLOCK,0.f);
        if(clock<busyEnd){
            float* inChannels[]={mono};float* outChannels[]={left,right};
            IPLAudioBuffer in{1,BLOCK,inChannels},out{2,BLOCK,outChannels};
            for(int j=0;j<BLOCK;++j){
                int64_t n=clock+j-start;
                if(reference&&n>=0&&n<TX)mixL[j]=mixR[j]=tx[n]*txGain;
            }
            for(int i=0;i<count;++i){
                auto& v=voices[i];
                for(int j=0;j<BLOCK;++j){
                    int64_t n=clock+j-start-v.delay;
                    mono[j]=(n>=0&&n<PULSE)?v.samples[n]*v.gain:0.f;
                }
                if(spatial){
                    IPLBinauralEffectParams bp{};bp.direction=v.direction;
                    bp.interpolation=IPL_HRTFINTERPOLATION_BILINEAR;
                    bp.spatialBlend=1.f;bp.hrtf=hrtf;bp.peakDelays=nullptr;
                    iplBinauralEffectApply(effects[i],&bp,&in,&out);
                    for(int j=0;j<BLOCK;++j){mixL[j]+=left[j];mixR[j]+=right[j];}
                }else for(int j=0;j<BLOCK;++j){mixL[j]+=mono[j];mixR[j]+=mono[j];}
            }
        }
        for(int j=0;j<BLOCK;++j){
            pcm[2*j]=jshort(std::clamp(mixL[j],-.80f,.80f)*32767.f);
            pcm[2*j+1]=jshort(std::clamp(mixR[j],-.80f,.80f)*32767.f);
        }
        clock+=BLOCK;return (uint64_t(serial)<<32)|mask;
    }
};
extern "C" JNIEXPORT jlong JNICALL Java_com_echoguide_audiolab_SteamNative_create(JNIEnv* env,jobject,jstring path){
    const char* sofa=path?env->GetStringUTFChars(path,nullptr):nullptr;
    if(path&&!sofa)return 0;
    auto e=std::make_unique<Engine>();bool ok=e->init(sofa);
    if(path)env->ReleaseStringUTFChars(path,sofa);
    return ok?reinterpret_cast<jlong>(e.release()):0;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_echoguide_audiolab_SteamNative_render(JNIEnv* env,jobject,jlong handle,jfloatArray positions,jint count,jfloat volume,jfloatArray controls,jboolean manual,jshortArray pcm){
    if(!handle||count<0||count>MAX||env->GetArrayLength(positions)<count*5||env->GetArrayLength(controls)<11||env->GetArrayLength(pcm)<BLOCK*2)return 0;
    float xyz[MAX*5]{},p[11]{};jshort samples[BLOCK*2]{};
    env->GetFloatArrayRegion(positions,0,count*5,xyz);env->GetFloatArrayRegion(controls,0,11,p);
    uint64_t result=reinterpret_cast<Engine*>(handle)->render(xyz,count,volume,p,manual,samples);
    env->SetShortArrayRegion(pcm,0,BLOCK*2,samples);return jlong(result);
}
extern "C" JNIEXPORT void JNICALL Java_com_echoguide_audiolab_SteamNative_destroy(JNIEnv*,jobject,jlong handle){delete reinterpret_cast<Engine*>(handle);}
