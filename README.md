# Para Vario: offline Android audio variometer

Current state: working audio vario in a foreground service, with a flight screen, a tone test screen and sensor diagnostics.

## How the app measures height change

The app checks the phone on start and picks the best source it has:

| Mode | Used when | What you get |
|---|---|---|
| Barometer + motion sensors | the phone has a barometer, accelerometer and rotation vector | High accuracy. Fused Kalman filter, climb tone from +0.2 m/s, reacts in well under a second. |
| Barometer only | barometer but no motion sensors | Good, with about 1.5 s more lag. |
| GPS height | no barometer | Tells you whether you are gaining or losing height, about 3–5 s after it happens. The number is approximate. |

### GPS mode (phones without a barometer)

- Only GPS altitude is used. No accelerometer, gyro or compass, so the result is the same in any position: in a pocket, upside down, swinging in a harness. No calibration is needed.
- Two filters run on the same GPS fixes. A fast one catches strong changes early; a slow one confirms weak but steady ones.
- The thresholds grow with the measured GPS noise. Poor reception gives silence, not wrong tones. Sudden GPS altitude jumps are ignored.
- Limits: it needs open sky, it cannot react faster than GPS allows (usually one fix per second), and slow changes below about 0.4 m/s are not detected. In simulation with realistic GPS errors the direction was right more than 99% of the time in flight, with a 4 s average delay.

Sounds in GPS mode: beeps while gaining height (higher and faster in stronger climbs), slow low tones while losing height, a continuous low tone in strong sink, silence when level or unsure. Two buttons on the flight screen:

- **Loss sound**: all height loss / strong sink only (below −2 m/s) / off. A paraglider sinks about 1 m/s in normal glide, so "all" sounds through most of a flight; many pilots prefer "strong sink only".
- **Sensitivity**: low / normal / high. Lower it if you hear tones while standing still; raise it to catch weaker lift.

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
| 1+ | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS ground speed and GPS altitude. On phones without a barometer, GPS altitude is the only source, so the vario cannot start without it. Later: QNH auto-calibration and IGC positions. |
| 5 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Opens the system dialog that exempts the app from battery optimisation. |

## How to test

1. Open **Test tones** and move the slider to learn the sounds.
2. Go outside under open sky, press **START VARIO** and allow location and notifications. Wait until the "waiting for GPS" banner goes away.
3. Stand still for a minute: it should stay silent ("LEVEL").
4. Change height for real:
   - phone without barometer: walk up and down a steep slope or outdoor stairs for at least 10 seconds each way, or drive a hilly road. Lifting the phone by hand does nothing, because GPS cannot see one metre.
   - phone with barometer: raising and lowering the phone by a metre is enough.
5. Put the phone in a pocket in any position and repeat. Turn the screen off; the sound must continue.
6. Media volume controls the loudness.

A double high pip every 3 seconds is the warning tone: a sensor stopped or the GPS fix was lost. The screen shows a red banner with the reason. The line under the speed shows GPS fixes per second, altitude noise and reported accuracy, which helps when reporting a problem.

## Sensor diagnostics

1. Open **Diagnostics**. The report lists the phone model, every barometer (name, vendor, advertised rate, resolution, reporting mode, FIFO) and the motion sensors.
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
