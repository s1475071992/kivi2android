#include <jni.h>
#include <phonon.h>
#include <array>
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <memory>

constexpr int N=512, MAX=32;
struct Engine {
    IPLContext context=nullptr;
    IPLHRTF hrtf=nullptr;
    std::array<IPLBinauralEffect,MAX> effects{};
    std::array<uint32_t,MAX> rng{};
    std::array<float,MAX> smoothedGain{};
    int previousCount=0;
    float mono[N]{}, left[N]{}, right[N]{}, mixL[N]{}, mixR[N]{};
    float phase=0;
    ~Engine() {
        for(auto& effect:effects) if(effect) iplBinauralEffectRelease(&effect);
        if(hrtf) iplHRTFRelease(&hrtf);
        if(context) iplContextRelease(&context);
    }
    bool init(const char* sofa) {
        IPLContextSettings cs{}; cs.version=STEAMAUDIO_VERSION;
        if(iplContextCreate(&cs,&context)!=IPL_STATUS_SUCCESS) return false;
        IPLAudioSettings as{}; as.samplingRate=48000; as.frameSize=N;
        IPLHRTFSettings hs{}; hs.type=sofa?IPL_HRTFTYPE_SOFA:IPL_HRTFTYPE_DEFAULT;
        hs.sofaFileName=sofa; hs.volume=1.0f;
        if(iplHRTFCreate(context,&as,&hs,&hrtf)!=IPL_STATUS_SUCCESS) return false;
        IPLBinauralEffectSettings bs{}; bs.hrtf=hrtf;
        for(int i=0;i<MAX;++i) {
            if(iplBinauralEffectCreate(context,&as,&bs,&effects[i])!=IPL_STATUS_SUCCESS) return false;
            rng[i]=0x9e3779b9u*(i+1);
        }
        return true;
    }
    void render(const float* xyz,int count,float volume,jshort* pcm) {
        count=std::clamp(count,0,MAX);
        std::fill_n(mixL,N,0.f); std::fill_n(mixR,N,0.f);
        float* inChannels[]={mono}; float* outChannels[]={left,right};
        IPLAudioBuffer in{1,N,inChannels}, out{2,N,outChannels};
        for(int i=count;i<previousCount;++i) {
            iplBinauralEffectReset(effects[i]); smoothedGain[i]=0.f;
        }
        previousCount=count;
        for(int i=0;i<count;++i) {
            float x=xyz[3*i], y=xyz[3*i+1], z=xyz[3*i+2];
            float d=std::sqrt(x*x+y*y+z*z);
            IPLBinauralEffectParams p{};
            // App uses +Z forward; Steam Audio uses -Z forward.
            p.direction=d>0.0001f?IPLVector3{x/d,y/d,-z/d}:IPLVector3{0,0,-1};
            p.interpolation=IPL_HRTFINTERPOLATION_BILINEAR;
            p.spatialBlend=1.f; p.hrtf=hrtf; p.peakDelays=nullptr;
            float target=std::clamp(volume,0.f,1.f)*0.24f/std::sqrt(float(count))/(1.f+0.25f*d);
            float g=smoothedGain[i];
            for(int j=0;j<N;++j) {
                uint32_t& r=rng[i]; r^=r<<13; r^=r>>17; r^=r<<5;
                float noise=float(r>>8)/8388607.5f-1.f;
                float envelope=0.5f+0.5f*std::cos(phase+float(j)*6.2831853f*2.f/48000.f);
                g += (target-g)*0.004f;
                mono[j]=noise*envelope*g;
            }
            smoothedGain[i]=g;
            iplBinauralEffectApply(effects[i],&p,&in,&out);
            for(int j=0;j<N;++j) {mixL[j]+=left[j];mixR[j]+=right[j];}
        }
        phase=std::fmod(phase+N*6.2831853f*2.f/48000.f,6.2831853f);
        for(int j=0;j<N;++j) {
            pcm[2*j]=jshort(std::clamp(mixL[j],-0.85f,0.85f)*32767.f);
            pcm[2*j+1]=jshort(std::clamp(mixR[j],-0.85f,0.85f)*32767.f);
        }
    }
};
extern "C" JNIEXPORT jlong JNICALL Java_com_echoguide_audiolab_SteamNative_create(JNIEnv* env,jobject,jstring path) {
    const char* sofa=path?env->GetStringUTFChars(path,nullptr):nullptr;
    auto e=std::make_unique<Engine>();
    bool ok=e->init(sofa);
    if(path&&sofa)env->ReleaseStringUTFChars(path,sofa);
    return ok?reinterpret_cast<jlong>(e.release()):0;
}
extern "C" JNIEXPORT void JNICALL Java_com_echoguide_audiolab_SteamNative_render(JNIEnv* env,jobject,jlong handle,jfloatArray positions,jint count,jfloat volume,jshortArray pcm) {
    if(!handle||count<0||count>MAX||env->GetArrayLength(positions)<count*3||env->GetArrayLength(pcm)<N*2)return;
    float xyz[MAX*3]{}; jshort samples[N*2]{};
    env->GetFloatArrayRegion(positions,0,count*3,xyz);
    reinterpret_cast<Engine*>(handle)->render(xyz,count,volume,samples);
    env->SetShortArrayRegion(pcm,0,N*2,samples);
}
extern "C" JNIEXPORT void JNICALL Java_com_echoguide_audiolab_SteamNative_destroy(JNIEnv*,jobject,jlong handle) {delete reinterpret_cast<Engine*>(handle);}
