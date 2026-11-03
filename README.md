# Frame

Android camera app (Kotlin, CameraX) built around a reference overlay.

- Upload a reference image and set its opacity so you can line your subject up with it.
  Adjust moves and resizes the reference, Mirror flips it. The overlay is never saved into the photo.
- Eight filters. The live preview shows the filter and the saved photo has it applied.
- Flash (off, auto, on), timer (3s, 10s), grid (thirds, center), front and back camera.
- Tap to focus, pinch to zoom, volume buttons take the photo.
- Photos are saved to Pictures/Frame. Needs Android 10 or newer.

## Build the APK
Push this folder to GitHub. The Build APK action produces app-debug.apk under Actions > run > Artifacts.
Or open the folder in Android Studio and press Run.
