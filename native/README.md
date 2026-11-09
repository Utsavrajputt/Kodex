# Native image tracer

The Rust core behind **Tools → Image tracer**.

| Crate | What it is |
|---|---|
| `vtracer-core` | The [VTracer](https://github.com/visioncortex/vtracer) core crate, vendored, with the added pixel-exact `Preset::Exact` (lossless regions, exact pixel outlines, `fill-opacity` for partial alpha). |
| `kodex-vtracer` | JNI wrapper, built as `libkodex_vtracer.so`. Maps the Quality / Colors sliders to a VTracer config and converts the SVG to an Android vector XML. |

## Slider mapping

| Quality | Behaviour |
|---|---|
| 1 Draft | coarse colors, strong speckle filtering |
| 2 Low | simplified |
| 3 Balanced | VTracer defaults |
| 4 High | fine layers, small speckles kept |
| 5 Pixel-exact | lossless; SVG renders 1:1 back to the source |

**Colors** caps the palette (2 … 128); **All** keeps every color. Quality 5 is only exact with Colors = All.

## Build

CI does this automatically (`.github/scripts/build-native.sh`, run before Gradle in every workflow).
Locally you need Rust, the Android targets, `cargo-ndk` and an NDK:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked
./gradlew buildNative        # writes app/src/main/jniLibs/<abi>/libkodex_vtracer.so
```

Without the library the app still runs; the tool just reports that the native tracer is missing.

## Tests (host)

```bash
cd native && cargo test --release
```

Covers pixel-exact round trips (SVG rasterized with resvg == source), the slider mapping and the
SVG → Android XML converter (the XML's path data must draw the same picture as the SVG).
