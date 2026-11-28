#include "../app/src/main/cpp/scan_dsp.h"
#include <cassert>
#include <iostream>
int main(){assert(scan::frames(20)==960);assert(scan::frames(10)==480);assert(scan::length(15,480)==480);assert(48*scan::frames(20)==46080);float a[960];for(int type=0;type<4;type++){scan::wave(a,720,type,0,1,0,0,0);float e=0;for(int j=0;j<720;j++){assert(std::isfinite(a[j]));e+=a[j]*a[j];}assert(std::abs(std::sqrt(e/720)-.23f)<.001f);assert(a[0]==0&&a[719]==0);}std::cout<<"Scan DSP tests passed\n";}
