# Image Studio (Android)

AI image generation app for Android, written in Kotlin with Jetpack Compose.

## Features
- **Text to image** with prompt, 16 style presets, aspect ratio, negative prompt, seed, model picker and 1-4 images per run
- **Image to image** ("Vary"): pick a photo or a past result and set how much it should change
- **Inpainting** ("Edit area"): paint over part of an image with your finger and describe what goes there
- **Gallery**: every result saved on the phone with its prompt and settings; favorites, share, save to Pictures, copy prompt, reuse seed, remix
- **On-device generation (default)**: Stable Diffusion 1.5 running offline on the phone via Google's MediaPipe Image Generator, with live preview, step control and cancel
- **Pluggable backends**: on-device, Stability AI, OpenAI (gpt-image-1), and an offline Demo backend

## Architecture
```
ui/ (Compose)            Create · Gallery · Settings, single-activity, bottom nav
  CreateViewModel        builds a GenerationRequest, saves results to the gallery
backend/
  ImageBackend           interface: capabilities + suspend generate(request, onProgress)
  OnDeviceBackend        MediaPipe Image Generator, SD 1.5, 512x512
  StabilityBackend       REST v2beta (core / ultra / SD 3.5, edit/inpaint)
  OpenAiBackend          /v1/images/generations and /edits
  DemoBackend            offline procedural images
data/
  SettingsRepository     DataStore: chosen backend + API keys (excluded from backups)
  GalleryRepository      PNGs in app storage + JSON index
  ModelManager           downloads / imports / deletes the on-device model
AppContainer             manual DI; register new backends here
```
The UI reads `BackendCapabilities` and hides controls a backend doesn't support,
so adding a provider (RunPod/ComfyUI, Automatic1111, on-device) means writing one class.

## Build
Requires Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.
```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # backend tests against a mock server
```
Pushing to GitHub runs `.github/workflows/build.yml`, which uploads an installable APK as a build artifact.

## On-device model
The app needs Stable Diffusion 1.5 converted to MediaPipe format (~2 GB). It can't ship inside the APK.
1. On a computer run `bash tools/make_ondevice_model.sh`. It downloads SD 1.5 and Google's converter and makes `sd15.zip`.
   For a **custom model**, pass any SD 1.5-based checkpoint file or URL (`.ckpt` or `.safetensors`, e.g. from Civitai):
   `bash tools/make_ondevice_model.sh dreamshaper_8.safetensors "DreamShaper 8"`. SDXL / SD3 / Flux models are not supported on-device.
2. Get it onto the phone: copy the zip over and tap **Settings > On-device models > Import zip from phone**, or host it at an https link and use **Download**.
   (Developers can also `adb push bins/. /data/local/tmp/image_generator/bins/`.)

You can install several models side by side and switch between them on the Create screen.

Limits: 512x512, text-to-image only (Vary and Edit area need a cloud backend), recent phone with 8 GB+ RAM.
Note: Google marks the MediaPipe Image Generator as deprecated and no longer maintained. It still works, and the
backend is isolated in `OnDeviceBackend.kt` so it can be swapped (e.g. for a Qualcomm QNN or ONNX runtime) later.

## Using it
For cloud generation, open Settings, paste a Stability AI key (platform.stability.ai) or OpenAI key, pick that backend, then generate.
Keys are stored only on the phone and are sent only to their own provider.
