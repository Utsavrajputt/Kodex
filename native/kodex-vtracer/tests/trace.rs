//! Host-side checks of the slider mapping and the SVG → Android XML converter.

use kodex_vtracer::{trace_rgba, xml::svg_to_vector_xml, TraceSession};
use resvg::{tiny_skia, usvg};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
}

/// Gradient + soft-edged disc + sprinkled noise, fully opaque.
fn picture(w: usize, h: usize) -> Vec<u8> {
    let mut rng = Rng(0x2545_F491_4F6C_DD1D);
    let mut px = Vec::with_capacity(w * h * 4);
    for y in 0..h {
        for x in 0..w {
            let (dx, dy) = (x as f64 - 22.0, y as f64 - 20.0);
            let t = ((12.0 - (dx * dx + dy * dy).sqrt()) / 2.0).clamp(0.0, 1.0);
            let mut c = [
                ((x * 255 / w) as f64 * (1.0 - t) + 230.0 * t) as u8,
                ((y * 255 / h) as f64 * (1.0 - t) + 40.0 * t) as u8,
                (((x + y) * 255 / (w + h)) as f64 * (1.0 - t) + 60.0 * t) as u8,
            ];
            if rng.next() % 19 == 0 {
                c = [rng.next() as u8, rng.next() as u8, rng.next() as u8];
            }
            px.extend_from_slice(&[c[0], c[1], c[2], 255]);
        }
    }
    px
}

fn rasterize(svg: &str, w: u32, h: u32) -> Vec<u8> {
    let tree = usvg::Tree::from_str(svg, &usvg::Options::default()).expect("parse svg");
    let mut pm = tiny_skia::Pixmap::new(w, h).unwrap();
    resvg::render(&tree, tiny_skia::Transform::identity(), &mut pm.as_mut());
    pm.data().to_vec()
}

fn max_diff(a: &[u8], b: &[u8]) -> u8 {
    a.iter().zip(b).map(|(x, y)| x.abs_diff(*y)).max().unwrap_or(0)
}

#[test]
fn top_quality_with_all_colors_is_pixel_exact() {
    let (w, h) = (48, 40);
    let src = picture(w, h);
    let svg = trace_rgba(src.clone(), w, h, 5, 0).unwrap();
    assert_eq!(max_diff(&rasterize(&svg, w as u32, h as u32), &src), 0);
}

#[test]
fn every_quality_step_traces() {
    let (w, h) = (48, 40);
    let src = picture(w, h);
    for q in 1..=5 {
        let svg = trace_rgba(src.clone(), w, h, q, 0).unwrap();
        assert!(svg.contains("<svg") && svg.contains("<path"), "quality {q}");
    }
}

#[test]
fn out_of_range_quality_is_clamped() {
    let src = picture(16, 16);
    assert!(trace_rgba(src.clone(), 16, 16, -3, 0).is_ok());
    assert!(trace_rgba(src, 16, 16, 99, 0).is_ok());
}

#[test]
fn color_cap_limits_the_palette() {
    let (w, h) = (48, 40);
    let svg = trace_rgba(picture(w, h), w, h, 3, 4).unwrap();
    let mut fills: Vec<&str> = svg.match_indices("fill=\"#").map(|(i, _)| &svg[i + 6..i + 13]).collect();
    fills.sort();
    fills.dedup();
    assert!(fills.len() <= 4, "got {} colors: {fills:?}", fills.len());
}

#[test]
fn bad_input_is_an_error_not_a_panic() {
    assert!(trace_rgba(vec![0; 10], 4, 4, 3, 0).is_err());
    assert!(trace_rgba(vec![], 0, 0, 3, 0).is_err());
}

/// The XML's pathData must draw the same picture as the SVG it came from.
fn xml_as_svg(xml: &str, w: u32, h: u32) -> String {
    let mut out = format!("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{w}\" height=\"{h}\">\n");
    for line in xml.lines().filter(|l| l.contains("<path")) {
        let get = |name: &str| {
            let key = format!("android:{name}=\"");
            line.find(&key).map(|i| {
                let from = i + key.len();
                &line[from..from + line[from..].find('"').unwrap()]
            })
        };
        out.push_str(&format!(
            "<path d=\"{}\" fill=\"{}\" fill-opacity=\"{}\"/>\n",
            get("pathData").unwrap(),
            get("fillColor").unwrap(),
            get("fillAlpha").unwrap_or("1"),
        ));
    }
    out.push_str("</svg>");
    out
}

#[test]
fn vector_xml_draws_the_same_picture() {
    let (w, h) = (48usize, 40usize);
    for q in [3, 5] {
        let svg = trace_rgba(picture(w, h), w, h, q, 0).unwrap();
        let v = svg_to_vector_xml(&svg).unwrap();
        assert!(v.xml.starts_with("<?xml") && v.xml.contains("android:viewportWidth=\"48\""));
        assert!(v.paths > 0 && v.path_data_chars > 0);
        let a = rasterize(&svg, w as u32, h as u32);
        let b = rasterize(&xml_as_svg(&v.xml, w as u32, h as u32), w as u32, h as u32);
        assert_eq!(max_diff(&a, &b), 0, "quality {q}: XML differs from SVG");
    }
}

#[test]
fn vector_xml_keeps_alpha_and_groups() {
    let svg = r##"<svg xmlns="http://www.w3.org/2000/svg" width="10" height="6">
<g fill="#112233" fill-opacity="0.5">
<path d="M0,0h5v6h-5Z"/>
</g>
<path d="M5,0h5v6h-5Z" fill="#445566"/>
</svg>"##;
    let v = svg_to_vector_xml(svg).unwrap();
    assert_eq!(v.paths, 2);
    assert!(v.xml.contains("android:fillColor=\"#112233\" android:fillAlpha=\"0.5\""));
    assert!(v.xml.contains("android:fillColor=\"#445566\" android:pathData=\"M5,0h5v6h-5Z\""));
    assert!(!v.xml.contains("fillAlpha=\"0.5\" android:pathData=\"M5"));
}

#[test]
fn vector_xml_rejects_non_svg() {
    assert!(svg_to_vector_xml("hello").is_err());
}

/// A session must give exactly what the one-shot call gives, whatever order the settings come
/// in; that is what lets a Colors-only change reuse the cached clustering.
#[test]
fn session_matches_one_shot_in_any_order() {
    let (w, h) = (48usize, 40usize);
    let src = picture(w, h);
    let session = TraceSession::new(src.clone(), w, h).unwrap();
    for (q, c) in [(3, 0), (3, 8), (3, 2), (4, 16), (2, 0), (5, 0), (5, 8), (3, 8)] {
        let cached = session.render(q, c).unwrap();
        let fresh = trace_rgba(src.clone(), w, h, q, c).unwrap();
        assert_eq!(cached, fresh, "quality {q}, colors {c}");
    }
}

#[test]
fn session_rejects_bad_input_and_survives_a_stale_cancel() {
    assert!(TraceSession::new(vec![0; 10], 4, 4).is_err());
    assert!(TraceSession::new(vec![], 0, 0).is_err());
    let (w, h) = (24usize, 20usize);
    let session = TraceSession::new(picture(w, h), w, h).unwrap();
    // A cancel with no render in flight must not poison the next render.
    session.cancel();
    assert!(session.render(3, 0).is_ok());
}
