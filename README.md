# Health Connect Viewer

> **Note:** this app was developed with AI coding agents (Google Antigravity, Claude Code, and GLM and DeepSeek models through the Pi coding agent). I directed the work, reviewed the changes and tested the app on my own phone.

Android app that reads your data from Health Connect and puts it on charts. It never writes anything back and has no network access. The point is to see what each app and wearable actually syncs to Health Connect, and how much the sources agree with each other.

It reads steps, heart rate, SpO2, sleep sessions and HRV (RMSSD), and has four screens:

- **Viewer**: one source over time, with total or average and the trend against the previous period.
- **Comparison**: several sources on the same axis, a correlation scatter plot, and a confusion matrix for the sleep stages of two devices.
- **Mixing**: merges several sources into a single series. For steps it uses Health Connect's own deduplicated aggregate.
- **Diagnostics**: what Health Connect really contains: how many records and readings there are for each data type and each app that wrote them.

When a value is estimated rather than measured (steps spread over the day from a daily total, or REM stages inferred) the app marks it as such on the chart.

There's also an early **Analysis** screen meant to count overnight SpO2 desaturations. It's a first draft and not reliable yet, so take it as a possible future feature. It is not a medical tool in any case.

## Requirements

Android 8.0 or later, with Health Connect installed. On Android 14 and later it's part of the system.

## Building

```sh
git clone https://github.com/emilio96/HealthConnectViewer.git
cd HealthConnectViewer
./gradlew assembleDebug
```

Or open the folder in Android Studio. The data logic (merging, binning, sleep stages) has no Android dependencies and is covered by unit tests: `./gradlew testDebugUnitTest`.

## License

MIT, see [`LICENSE`](LICENSE).
