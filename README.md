# Frame

Android camera app (Kotlin, CameraX). Stage 1 of the studio.

Camera: photo and video, aspect ratios 4:3, 16:9, 1:1 and full, timer 3/5/10 s, burst,
grids (thirds, square, golden, center), exposure, pinch zoom, tap to focus, flash and torch,
video quality and frame rate, pause and resume, reference image overlay with opacity, photo filters.

Gallery: photos and videos, grid and list, multi-select, favorites, share, delete, sort, full-screen viewer.

Build: push to GitHub and run the Build APK action, or open in Android Studio.

## Stage 2: Effects and video compatibility

Ten GPU effects (CRT, Receipt Paper, Depth Map, Blob Tracking, Datamosh, 1-Bit Dither, Night Vision,
Thermal Noise, Glyph Grid, Liquid Screen). Each has intensity, scale, contrast, brightness and its own controls.

- Camera: Looks > Effects. Applies to the viewfinder, to recorded video and to photos.
- Effects tab: try the effects on a photo from the phone and save the result.
- The effect list, parameters and ranges live in `assets/effects.json`; shaders in `assets/shaders/`.
- Video options (resolution x frame rate) are built from the hardware for the lens active at the current zoom.

Limits: see the release notes in the chat. Effects record up to 1080p 30 fps. Depth Map is an estimate
(no depth sensor or ML model). Photos taken with an effect use the viewfinder resolution.
