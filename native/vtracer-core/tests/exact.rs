//! Pixel-exact mode: trace → SVG → rasterize 1:1 → compare with the source.
//!
//! `Preset::Exact` promises that the SVG renders back to the input image with
//! zero error. These tests hold it to that on opaque noise (the worst case for
//! region count), anti-aliased shapes, and images with transparency.

use resvg::{tiny_skia, usvg};
use vtracer::{ColorImage, Config, Preset};

/// Tiny deterministic xorshift so the tests need no `rand` dependency.
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
}

fn rasterize(svg: &str, w: u32, h: u32) -> Vec<u8> {
    let tree = usvg::Tree::from_str(svg, &usvg::Options::default()).expect("parse svg");
    let mut pixmap = tiny_skia::Pixmap::new(w, h).expect("alloc pixmap");
    resvg::render(&tree, tiny_skia::Transform::identity(), &mut pixmap.as_mut());
    pixmap.data().to_vec()
}

/// Source RGBA → premultiplied RGBA, the layout `tiny_skia` renders into.
fn premultiply(img: &ColorImage) -> Vec<u8> {
    let mut out = Vec::with_capacity(img.pixels.len());
    for p in img.pixels.chunks_exact(4) {
        let a = p[3] as u32;
        for &c in &p[..3] {
            out.push(((c as u32 * a + 127) / 255) as u8);
        }
        out.push(p[3]);
    }
    out
}

fn max_diff(a: &[u8], b: &[u8]) -> u8 {
    a.iter().zip(b).map(|(x, y)| x.abs_diff(*y)).max().unwrap_or(0)
}

fn trace(img: &ColorImage) -> String {
    Config::from_preset(Preset::Exact)
        .build()
        .expect("build")
        .to_svg(img)
        .expect("trace")
}

/// Smooth gradient plus anti-aliased disc edges plus a few noisy pixels.
fn illustration(w: usize, h: usize) -> ColorImage {
    let mut rng = Rng(0x9E37_79B9_7F4A_7C15);
    let mut pixels = Vec::with_capacity(w * h * 4);
    for y in 0..h {
        for x in 0..w {
            let (dx, dy) = (x as f64 - 24.0, y as f64 - 20.0);
            let d = (dx * dx + dy * dy).sqrt();
            let mut c = [
                (x * 255 / w) as u8,
                (y * 255 / h) as u8,
                ((x + y) * 255 / (w + h)) as u8,
            ];
            if d < 14.0 {
                // soft edge: blend toward red over the last 2px
                let t = ((14.0 - d) / 2.0).clamp(0.0, 1.0);
                c[0] = (c[0] as f64 * (1.0 - t) + 220.0 * t) as u8;
                c[1] = (c[1] as f64 * (1.0 - t) + 30.0 * t) as u8;
                c[2] = (c[2] as f64 * (1.0 - t) + 40.0 * t) as u8;
            }
            if rng.next() % 23 == 0 {
                c = [rng.next() as u8, rng.next() as u8, rng.next() as u8];
            }
            pixels.extend_from_slice(&[c[0], c[1], c[2], 255]);
        }
    }
    ColorImage { pixels, width: w, height: h }
}

#[test]
fn opaque_image_round_trips_exactly() {
    let img = illustration(48, 40);
    let svg = trace(&img);
    let out = rasterize(&svg, 48, 40);
    assert_eq!(max_diff(&out, &premultiply(&img)), 0, "pixel mismatch");
}

#[test]
fn pure_noise_round_trips_exactly() {
    // Every pixel (almost) its own region: checks pinch points and
    // diagonal-only adjacency in the boundary graph.
    let mut rng = Rng(42);
    let (w, h) = (32usize, 32usize);
    let mut pixels = Vec::new();
    for _ in 0..w * h {
        let v = rng.next();
        pixels.extend_from_slice(&[(v & 3) as u8 * 80, ((v >> 2) & 3) as u8 * 80, 0, 255]);
    }
    let img = ColorImage { pixels, width: w, height: h };
    let out = rasterize(&trace(&img), w as u32, h as u32);
    assert_eq!(max_diff(&out, &premultiply(&img)), 0);
}

#[test]
fn transparency_and_partial_alpha_are_preserved() {
    let (w, h) = (24usize, 24usize);
    let mut pixels = Vec::new();
    for y in 0..h {
        for x in 0..w {
            let a = match (x / 6 + y / 6) % 4 {
                0 => 0u8,   // fully transparent
                1 => 255,   // opaque
                2 => 128,   // half
                _ => 51,    // faint
            };
            pixels.extend_from_slice(&[200, (x * 10) as u8, 40, a]);
        }
    }
    let img = ColorImage { pixels, width: w, height: h };
    let svg = trace(&img);
    assert!(svg.contains("fill-opacity"), "partial alpha must reach the SVG");
    let out = rasterize(&svg, w as u32, h as u32);
    // 8-bit premultiplied rounding may differ by one level.
    assert!(max_diff(&out, &premultiply(&img)) <= 1);
}

#[test]
fn exact_does_not_change_other_presets() {
    // Opaque output stays free of fill-opacity for every non-exact preset.
    let img = illustration(32, 32);
    for preset in [Preset::Bw, Preset::Poster, Preset::Photo] {
        let svg = Config::from_preset(preset).build().unwrap().to_svg(&img).unwrap();
        assert!(!svg.contains("fill-opacity"), "{preset:?} leaked alpha output");
    }
}

#[test]
fn exact_preset_parses() {
    assert_eq!("exact".parse::<Preset>().unwrap(), Preset::Exact);
    assert_eq!(
        "exact".parse::<vtracer::Clustering>().unwrap(),
        vtracer::Clustering::Exact
    );
}
