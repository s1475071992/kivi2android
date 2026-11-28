#include <jni.h>
#include <phonon.h>
#include <array>
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <memory>

#include "scan_dsp.h"
using namespace sonar;
using namespace scan;
struct Engine {
    IPLContext context=nullptr; IPLHRTF hrtf=nullptr; IPLBinauralEffect effect=nullptr;
    float snapshot[MAX_CELLS*8]{}, controls[8]{};
    float mono[BLOCK]{},left[BLOCK]{},right[BLOCK]{},signal[MAX_SLOT]{},slotL[MAX_SLOT]{},slotR[MAX_SLOT]{};
    int total=0,index=0,slot=960,offset=0,gapLeft=0,shown=-1;
    uint32_t serial=0;bool active=false,pending=false;float gain=0;
    int completed=0,audible=0;bool slotNonzero=false;
    ~Engine(){if(effect)iplBinauralEffectRelease(&effect);if(hrtf)iplHRTFRelease(&hrtf);if(context)iplContextRelease(&context);}
    bool init(const char* sofa){
        IPLContextSettings cs{};cs.version=STEAMAUDIO_VERSION;
        if(iplContextCreate(&cs,&context)!=IPL_STATUS_SUCCESS)return false;
        IPLAudioSettings as{};as.samplingRate=SR;as.frameSize=BLOCK;
        IPLHRTFSettings hs{};hs.type=sofa?IPL_HRTFTYPE_SOFA:IPL_HRTFTYPE_DEFAULT;hs.sofaFileName=sofa;hs.volume=1.f;
        if(iplHRTFCreate(context,&as,&hs,&hrtf)!=IPL_STATUS_SUCCESS)return false;
        IPLBinauralEffectSettings bs{};bs.hrtf=hrtf;
        return iplBinauralEffectCreate(context,&as,&bs,&effect)==IPL_STATUS_SUCCESS;
    }
    void prepare(){
        const float* q=snapshot+index*8;shown=int(q[7]);offset=0;slotNonzero=false;
        std::fill_n(slotL,slot,0.f);std::fill_n(slotR,slot,0.f);
        if(q[6]<=0||gain<=0)return;
        int n=length(controls[1],slot);wave(signal,n,int(controls[5]),q[5],q[3],q[4],controls[3],controls[4]);
        float amp=.75f*gain*q[6];
        if(controls[2]>.5f){
            iplBinauralEffectReset(effect);
            IPLBinauralEffectParams bp{};bp.direction=IPLVector3{q[0],q[1],-q[2]};bp.interpolation=IPL_HRTFINTERPOLATION_BILINEAR;bp.spatialBlend=1.f;bp.hrtf=hrtf;bp.peakDelays=nullptr;
            float* inChannels[]={mono};float* outChannels[]={left,right};IPLAudioBuffer in{1,BLOCK,inChannels},out{2,BLOCK,outChannels};
            for(int k=0;k<slot;k+=BLOCK){
                for(int j=0;j<BLOCK;j++)mono[j]=k+j<n?signal[k+j]*amp:0.f;
                iplBinauralEffectApply(effect,&bp,&in,&out);
                for(int j=0;j<BLOCK&&k+j<slot;j++){slotL[k+j]=left[j];slotR[k+j]=right[j];}
            }
        }else for(int j=0;j<n;j++)slotL[j]=slotR[j]=signal[j]*amp;
        // Keep the exact slot boundary; truncate only the residual tail with a short fade.
        for(int j=0;j<std::min(96,slot);j++){float f=float(j)/96.f;slotL[slot-1-j]*=f;slotR[slot-1-j]*=f;}
    }
    void begin(const float* q,int n,float volume,const float* p){
        std::copy_n(q,n*8,snapshot);std::copy_n(p,8,controls);total=n;index=0;gain=clamp01(volume);completed=0;audible=0;
        slot=frames(p[0]);active=true;serial++;prepare();
    }
    uint64_t render(const float* q,int n,float volume,const float* p,bool manual,jshort* pcm){
        pending=pending||manual;
        for(int j=0;j<BLOCK;j++){
            if(!active){
                if(gapLeft>0)gapLeft--;
                else if(n>0&&(pending||p[6]>.5f)){begin(q,n,volume,p);pending=false;}
            }
            float l=0,r=0;
            if(active){
                l=slotL[offset];r=slotR[offset];offset++;
                if(std::abs(l)*32767.f>=1.f||std::abs(r)*32767.f>=1.f)slotNonzero=true;
                if(offset==slot){
                    completed++;if(slotNonzero)audible++;
                    index++;
                    if(index==total){active=false;shown=-1;gapLeft=int(std::clamp(controls[7],0.f,2000.f)*48.f);}
                    else prepare();
                }
            }
            pcm[j*2]=jshort(std::clamp(l,-.8f,.8f)*32767.f);pcm[j*2+1]=jshort(std::clamp(r,-.8f,.8f)*32767.f);
        }
        return (uint64_t(serial)<<32)|(uint32_t(audible)<<18)|(uint32_t(completed)<<9)|uint32_t(shown+1);
    }
};
extern "C" JNIEXPORT jlong JNICALL Java_com_echoguide_audiolab_SteamNative_create(JNIEnv* env,jobject,jstring path){
    const char* sofa=path?env->GetStringUTFChars(path,nullptr):nullptr;if(path&&!sofa)return 0;
    auto e=std::make_unique<Engine>();bool ok=e->init(sofa);if(path)env->ReleaseStringUTFChars(path,sofa);return ok?reinterpret_cast<jlong>(e.release()):0;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_echoguide_audiolab_SteamNative_render(JNIEnv* env,jobject,jlong handle,jfloatArray positions,jint count,jfloat volume,jfloatArray controls,jboolean manual,jshortArray pcm){
    if(!handle||count<0||count>MAX_CELLS||env->GetArrayLength(positions)<count*8||env->GetArrayLength(controls)<8||env->GetArrayLength(pcm)<BLOCK*2)return 0;
    float q[MAX_CELLS*8]{},p[8]{};jshort samples[BLOCK*2]{};
    env->GetFloatArrayRegion(positions,0,count*8,q);env->GetFloatArrayRegion(controls,0,8,p);
    uint64_t result=reinterpret_cast<Engine*>(handle)->render(q,count,volume,p,manual,samples);env->SetShortArrayRegion(pcm,0,BLOCK*2,samples);return jlong(result);
}
extern "C" JNIEXPORT void JNICALL Java_com_echoguide_audiolab_SteamNative_destroy(JNIEnv*,jobject,jlong handle){delete reinterpret_cast<Engine*>(handle);}
