# UI previews

These images render the application's actual shared Compose source with Paparazzi, using a fixed **English** question and sample answer. They are **UI previews**, not photographs of a phone and not model-quality or device-performance evidence. No model inference is performed while capturing them.

From the repository root, with **JDK 21** and Android SDK 36 installed:

```sh
./gradlew -p tools/ui-preview --no-daemon :preview:recordPaparazziDebug
python3 tools/ui-preview/copy-images.py
```

The isolated renderer pins Paparazzi 2.0.0-alpha04, AGP 8.13.2, Kotlin 2.3.0 and the same Compose BOM/CommonMark versions as the app. Product APK builds continue to use their own root configuration, JDK 17, dependency locks and checksum verification. HTML unit-test reports are disabled here to avoid the alpha renderer's Gradle HTML-report compatibility issue; JUnit XML and the Paparazzi image report remain available.

Four Pixel 5 layout previews are captured: light chat, dark chat, models, and settings. The English locale and system-bar theme are fixed. The conversation and loaded-model state are fixtures. Images are copied without cropping or compositing.
