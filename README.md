# RecordAudio1 — Real-Time Audio Denoising & Keyword Alert (Android)

An Android app that records audio from the microphone, removes noise in real time, plays the cleaned audio back, and streams it to **Google Cloud Speech-to-Text** for live transcription. If it hears an alert keyword (**"help"**, **"danger"** or **"emergency"**), the phone vibrates and shows a warning — useful as a hearing-assistance aid.

## Features

- **Live mic capture & playback** — 44.1 kHz, 16-bit mono PCM
- **Three switchable denoising methods**
  - Simple low-pass filter (4 kHz cutoff)
  - 4th-order Butterworth low-pass filter (cascaded biquads)
  - Wavelet denoising (Haar wavelet packet transform via [JWave](https://github.com/cscheiblich/JWave), MAD-based soft thresholding)
- **Live speech-to-text** with interim and final transcripts (Google Cloud Speech, `en-US`)
- **Keyword detection** — vibration + on-screen alert
- Optional saving of original and processed audio (`SAVE_AUDIO_FILES` build flag)

## Tech stack

Kotlin · Android SDK (min 24, target 34) · View Binding · Coroutines · Google Cloud Speech (gRPC) · JWave

## Getting started

### Requirements
- Android Studio (Koala or newer), JDK 17
- An Android device or emulator with a microphone
- A Google Cloud project with the **Cloud Speech-to-Text API** enabled

### 1. Clone
```bash
git clone https://github.com/imarovaab/hearing-aid.git
cd hearing-aid
```

### 2. Add Google Cloud credentials
The credentials file is **not** included in this repository.

1. In the Google Cloud Console, create a service account with access to Speech-to-Text and download its JSON key.
2. Save it as:
   ```
   app/src/main/assets/hearingaid-457009-fc8015c92b62.json
   ```
   (or put it under any name in `app/src/main/assets/` and update the file name in `MainActivity.initializeSpeechClient()`).

> ⚠️ Embedding a service-account key in an APK is fine for personal testing, but anyone with the APK can extract it. For a public release, use a backend that issues short-lived tokens.

### 3. Build & run
Open the project in Android Studio and press **Run**, or:
```bash
./gradlew installDebug
```
Grant the microphone permission when prompted.

## Usage
1. Choose a denoising method (Low-pass, Butterworth, or Wavelet).
2. Tap **Start** — you will hear the processed audio and see the live transcript.
3. Say "help", "danger" or "emergency" to trigger the vibration alert.
4. Tap **Stop** to end the session.

## Project structure
```
app/
├── libs/JWave.jar                         # wavelet library
└── src/main/
    ├── java/com/example/recordaudio1/
    │   └── MainActivity.kt                # audio capture, filters, speech-to-text
    ├── res/layout/activity_main.xml       # UI
    └── assets/                            # place your credentials JSON here (git-ignored)
```

## License
Add a license of your choice (e.g. MIT) in a `LICENSE` file.
