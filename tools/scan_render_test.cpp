// Compile the production engine with actual desktop Steam Audio, not a mock.
#include "../app/src/main/cpp/steam_audio.cpp"
#include <cassert>
#include <iostream>
#include <vector>
static void test(int count,float ms,float dur,bool spatial,bool sparse){
    Engine e;assert(e.init(nullptr));
    std::vector<float> p(count*8,0.f);
    int expected=0;
    for(int i=0;i<count;i++){
        float az=(-55.f+110.f*(i%16)/15.f)*PI/180.f,el=(-35.f+70.f*(i/16)/15.f)*PI/180.f;
        p[i*8]=std::sin(az)*std::cos(el);p[i*8+1]=std::sin(el);p[i*8+2]=std::cos(az)*std::cos(el);
        p[i*8+3]=1.f;p[i*8+6]=sparse&&i%3==0?0.f:1.f;p[i*8+7]=float(i);expected+=p[i*8+6]>0;
    }
    float c[8]={ms,dur,spatial?1.f:0.f,0,0,0,0,0};
    int slot=frames(ms),samples=count*slot;
    jshort pcm[BLOCK*2];std::vector<int> peak(count,0);uint64_t stat=0;
    for(int i=0;i<samples+BLOCK;i+=BLOCK){
        stat=e.render(p.data(),count,.2f,c,i==0,pcm);
        for(int j=0;j<BLOCK&&i+j<samples;j++)peak[(i+j)/slot]=std::max({peak[(i+j)/slot],std::abs(int(pcm[j*2])),std::abs(int(pcm[j*2+1]))});
    }
    int heard=0;for(int i=0;i<count;i++){bool nonzero=peak[i]>0;assert(nonzero==(p[i*8+6]>0));heard+=nonzero;}
    assert(heard==expected);assert(((stat>>9)&511)==count);assert(((stat>>18)&511)==expected);assert((stat>>32)==1);
    std::cout<<"PCM test "<<count<<" cells "<<ms<<"ms HRTF="<<spatial<<" expected="<<expected<<" nonzero="<<heard<<" PASS"<<std::endl;
}
int main(){
    for(bool spatial:{false,true}){test(48,20,15,spatial,false);test(48,100,50,spatial,false);test(48,20,15,spatial,true);test(256,10,8,spatial,false);}
}
