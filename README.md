# Android Decibel Meter

A sound level meter for Android phones, written in Kotlin with Jetpack Compose. It opens straight into a live reading and measures the way a proper meter does, with A, C and Z weighting, Fast and Slow response, Leq, Lmax, Lmin and peak, plus a NIOSH noise dose against the 85 dBA, 8-hour limit.

With Keep Measuring When I Leave the App turned on, it keeps going behind a notification with the screen off, and a session can be saved as a per-second CSV log to Downloads/Decibel. Taps on the glass reach the mic as thumps, so the moment around each touch is left out of the figures. When another app takes the mic, that stretch is logged as a gap rather than as silence.

It also says when a reading can't be trusted. OVER lights when the input clips, MIC BUSY when Android is feeding it silence, and GAP when part of the log was left out.

## Accuracy

A phone mic is not a calibrated instrument. The default calibration assumes the mic meets the sensitivity target in Android's compatibility definition (94 dB SPL at 1 kHz reading -36 dBFS on the unprocessed path), and there is no telling how close a given phone comes. If you have a reference meter or calibrator, set the offset under Calibrate. Phone mics also start to distort somewhere around 110 to 120 dB, so readings up there are not worth much.

It only reads the phone's built-in mic, since the calibration belongs to that mic, and it refuses a headset or USB one.

## Building

It needs Android 12 or newer and JDK 21.

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key, as it is meant for sideloading. The checks are strict and all of them have to pass:

```sh
./gradlew detektMain detektTest lint testDebugUnitTest
```

## Privacy

It has no internet permission, so nothing it hears or logs leaves the phone unless you share a saved CSV yourself.

## Licence

The app is under the [MIT licence](LICENSE). Chivo and Chivo Mono are under the SIL Open Font License, in `OFL-Chivo.txt`.
