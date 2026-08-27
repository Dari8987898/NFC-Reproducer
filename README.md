# NFC Reproducer

A Kotlin Android app for reading NFC tags, saving their NDEF payloads, and writing compatible payloads to a blank tag.

## Requirements

* Android Studio Hedgehog (2023.1.1) or newer
* Android SDK 34 and JDK 17
* A physical NFC-capable Android phone (NFC is not available in most emulators)

## Setup

1. Open this directory in Android Studio
2. Allow Gradle to sync dependencies
3. Connect an NFC-enabled Android device
4. Run the `app` configuration on the device
5. Enable NFC when prompted

## Usage

### Scanning Tags
Hold an NFC tag against the back of your phone. The app will display:
- Tag UID (ID)
- Technology types detected
- NDEF records and content

### Saving Tags
Use **Save scanned tag** to retain a tag locally in the app's storage.

### Reproducing Tags
1. Tap **Write** on any saved tag
2. Hold an unlocked, writable NDEF tag to the phone
3. The app validates writability and capacity before writing
4. Status messages report success or failures

## Important Limitations

Android applications cannot duplicate:
- A tag's immutable UID
- Secure elements, keys, counters
- Arbitrary proprietary memory

MIFARE Classic support depends on device hardware and sector keys; this app identifies MIFARE Classic but only reproduces an NDEF message when the target exposes standard NDEF writing. It is not a payment/access-card cloner. **Only use tags and systems you own or are authorized to test.**

Tag data is stored in the app's private SharedPreferences and is never uploaded. The NFC permission and `NDEF_DISCOVERED`/`TECH_DISCOVERED` filters are declared in `AndroidManifest.xml`.

## Project Structure

```
├── app/
│   ├── src/main/
│   │   ├── java/com/example/nfcreproducer/
│   │   │   ├── MainActivity.kt      # Main NFC scanner activity
│   │   │   └── TagInfo.kt            # Data model for tags
│   │   ├── res/
│   │   │   ├── layout/               # UI layouts
│   │   │   ├── values/               # Strings, colors, themes
│   │   │   └── xml/                  # NFC tech filters
│   │   └── AndroidManifest.xml       # App manifest with NFC permissions
│   └── build.gradle.kts              # App build configuration
├── build.gradle.kts                  # Project build configuration
├── settings.gradle.kts               # Gradle settings
└── README.md                         # This file
```

