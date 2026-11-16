# Para Vario: offline Android audio variometer

Current state: **Phase 1**. Working audio vario in a foreground service, with a flight screen, a tone test screen and the Phase 0 sensor diagnostics.

## Altitude sources

The app checks the phone on start and picks the best source it has:

| Mode | Used when | Accuracy |
|---|---|---|
| Barometer + motion sensors | the phone has a barometer, accelerometer and rotation vector | High. Fused Kalman filter, climb tone from +0.2 m/s. |
| Barometer only | barometer but no motion sensors | Good, with about 1.5 s more lag. |
| GPS + motion sensors | no barometer | **Low.** GPS altitude at about 1 Hz corrects the accelerometer. Climb tone from +0.5 m/s. |

The filter and the tone engine are the same in every mode; only the altitude measurement and its noise setting change.

The app is fully offline. It never declares the `INTERNET` permission and has no analytics, ads or network libraries.

## Build

You don't need Android Studio, because GitHub Actions builds the APK:

1. Push this folder to a GitHub repository.
2. Open **Actions → Android debug build → latest run → Artifacts → app-debug** and download it.

To build locally you need JDK 17 and the Android SDK (`ANDROID_HOME` set, platform 36):

```
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Install

Enable USB debugging on the phone, then run:

```
adb install -r app-debug.apk
```

## Permissions

| Phase | Permission | Why |
|---|---|---|
| 0 | none | Sensor access needs no permission. |
| 1+ | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `WAKE_LOCK` | Keep sensors, filter and audio running with the screen off, behind a persistent notification. |
| 1+ | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS ground speed and GPS altitude. On phones without a barometer, GPS is the altitude source, so the vario cannot start without it. Later: QNH auto-calibration and IGC positions. |
| 5 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Opens the system dialog that exempts the app from battery optimisation. |

## Phase 1: how to test

1. Open **Test tones** and move the slider. Climb gives beeps that get higher and faster, strong sink gives a continuous low tone, and the band in between is silent.
2. Go outside (GPS mode needs a sky view), press **START VARIO**, allow location and notifications, and hold the phone still for 2 seconds while it calibrates.
3. Raise and lower the phone, or use stairs or a lift. On a barometer phone the beeps should follow within a fraction of a second. In GPS mode expect a slower, rougher reading.
4. Turn the screen off. The sound must continue.
5. Media volume controls the loudness.

A double high pip every 3 seconds is the warning tone: a sensor stopped or, in GPS mode, the GPS fix was lost. The screen shows a red banner with the reason.

## Phase 0: how to test

1. Open the app. The report lists the phone model, every barometer (name, vendor, advertised rate, resolution, reporting mode, FIFO) and the motion sensors.
2. Put the phone flat on a table, away from fans, AC and open windows.
3. Press **Start 10 s test** and don't touch the phone. The app drops 1 s of warm-up, then records 10 s at the fastest rate the phone allows.
4. Press **Copy report** and paste the result.
5. Also run this over USB:

```
adb shell dumpsys sensorservice | grep -i -A3 press
```

### Reading the result

- **Actual rate**: 20 Hz or more is good. 8–20 Hz is usable but the baro-only vario will lag more. Below 8 Hz is poor.
- **Altitude noise (detrended)**: 0.15 m or less is good. Up to 0.40 m is usable with more damping. Above that, the phone is very noisy or the air around it was moving.
- **Drift**: should be near 0 m/s. A large drift usually means a draught or the phone warming up.
- **Distinct values / smallest step**: shows how finely the sensor quantises pressure. Many repeated values mean the chip or driver is holding readings.

## Full testing plan (all phases)

1. **Bench**: raise and lower the phone by 1 m and check that the beeps start and stop correctly.
2. **Stairs or lift**: one floor is about 3 m. Altitude change should be accurate to ±1 m.
3. **Car on a mountain road**: compare the logged altitude profile with GPS altitude and known elevations. Keep the windows closed, because airflow corrupts barometer readings.
4. **Log replay**: record raw sensors to a file, then replay them through the filter in unit tests to tune parameters.
5. **First flights**: use the app only as a **backup** alongside a proven vario.

## Safety

This app is a flight aid, not a certified instrument. Keep the phone in a closed cockpit pouch, out of direct airflow, because ram-air pressure corrupts barometer readings.
