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

## Stage 3: Studio (v4.0)

The Studio tab holds four sections.

- **Collage Studio**: 65 templates in four groups (classic grids, asymmetric, artistic, social), each shown as a
  miniature drawn by the same renderer that exports the picture. Open one and the layout sits over the live camera.
  Tap a frame, capture into it, retake, replace from the gallery, pan and zoom the photo, adjust it per frame.
  Layout tools: add, delete, move, resize (shared edges follow), split across, down and on both diagonals, merge,
  oval, heart and diamond frames, spacing, margin, corner radius, border, background colour, gradient, texture,
  shadow, text and stickers. A layout can be saved as a template.
- **Live Effects**: the ten ready-made effects plus your own saved effects on a live view.
- **Effect Playground**: up to four layers, each with adjustments and a region (split line with angle, offset,
  curve and feather; circle or oval; window; freehand brush with size, softness, opacity and eraser; colour range),
  undo and redo, hold-to-compare, presets, and a library of one-tap layers.
- **My Creations**: saved effects and layouts with favourites, collections, duplicate, rename, preview, import,
  export, share. "Use on camera" makes the main Camera tab run a saved effect, including in video.

Source layout: `studio/` holds the collage model (`CollageModel.kt`, `Templates.kt`, pure Kotlin), the renderer and
view, the custom effect model (`CustomEffect.kt`), the camera helper (`StudioCamera.kt`) and the fragments.
The custom effect shader is `assets/shaders/effect_custom.glsl`; its field layout is `assets/custom_schema.json`
and the ten example presets are `assets/presets.json`.
