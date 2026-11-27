# EchoGuide 3D Audio Lab

Android prototype for testing whether a listener can recognize simple geometric layouts from multiple simultaneous virtual sound-source positions.

## What is included

- Five layouts: horizontal line, vertical line, ring, rectangle plane, and circular depression.
- Adjustable source count, forward distance, shape size, vertical angle, pit depth, and master volume.
- Live 2D projection of the generated 3D source coordinates.
- Simultaneous stereo rendering of all virtual sources through Android AudioTrack.
- A simple binaural approximation using equal-power panning, interaural time difference (ITD), and a subtle generic elevation cue.
- GitHub Actions builds a Debug APK and uploads it as an artifact.

## Audio limitation

This is a proof of concept, not a measured or personalized HRTF renderer. Horizontal left/right cues are approximated; front/back and elevation localization may be weak or ambiguous on ordinary headphones. Every source uses the same broadband texture so the experiment focuses on spatial distribution rather than different sound identities. A later version should use measured HRTF/HRIR data and listener testing.

## Build

The GitHub Actions workflow runs:

```bash
gradle testDebugUnitTest assembleDebug --no-daemon
```

After a successful workflow, download the `echoguide-debug-apk` artifact and install `app-debug.apk` on an Android 8.0 / API 26 or later device.

## Local development

Open this project in a recent stable Android Studio, allow Gradle sync, and run the `app` configuration on a device.

## Safety

This is an audio research prototype only. Test while seated in a safe environment. It is not a mobility aid and must not be used to guide independent walking or replace a white cane, guide dog, or orientation-and-mobility training.
