//! SVG → Android VectorDrawable XML.
//!
//! Reads the SVG that VTracer's own writer produces: a root `<svg width height>`
//! holding `<path d fill [fill-opacity]/>` elements, optionally inside
//! `<g fill [fill-opacity]>` groups. Android's `pathData` uses the same syntax as
//! SVG's `d`, so the data is copied verbatim.

/// The XML plus a few numbers the UI uses to warn about heavy vectors.
pub struct VectorXml {
    pub xml: String,
    pub paths: usize,
    pub path_data_chars: usize,
}

pub fn svg_to_vector_xml(svg: &str) -> Result<VectorXml, String> {
    let mut width = None;
    let mut height = None;
    let mut group_fill: Option<String> = None;
    let mut group_opacity: Option<String> = None;
    let mut body = String::with_capacity(svg.len() + svg.len() / 4);
    let mut paths = 0usize;
    let mut path_data_chars = 0usize;

    let mut rest = svg;
    while let Some(start) = rest.find('<') {
        let after = &rest[start + 1..];
        let Some(end) = after.find('>') else { break };
        let tag = &after[..end];
        rest = &after[end + 1..];

        if tag.starts_with('!') || tag.starts_with('?') {
            continue;
        }
        if tag.starts_with("svg") {
            width = attr(tag, "width").and_then(parse_len);
            height = attr(tag, "height").and_then(parse_len);
            if width.is_none() || height.is_none() {
                if let Some(vb) = attr(tag, "viewBox") {
                    let n: Vec<f64> = vb
                        .split(|c: char| c == ',' || c.is_whitespace())
                        .filter(|s| !s.is_empty())
                        .filter_map(|s| s.parse().ok())
                        .collect();
                    if n.len() == 4 {
                        width = width.or(Some(n[2]));
                        height = height.or(Some(n[3]));
                    }
                }
            }
        } else if tag.starts_with("g ") || tag == "g" {
            group_fill = attr(tag, "fill").map(str::to_owned);
            group_opacity = attr(tag, "fill-opacity").map(str::to_owned);
        } else if tag.starts_with("/g") {
            group_fill = None;
            group_opacity = None;
        } else if tag.starts_with("path") {
            let Some(d) = attr(tag, "d") else { continue };
            let fill = attr(tag, "fill")
                .map(str::to_owned)
                .or_else(|| group_fill.clone())
                .unwrap_or_else(|| "#000000".to_owned());
            let opacity = attr(tag, "fill-opacity")
                .map(str::to_owned)
                .or_else(|| group_opacity.clone());
            body.push_str("    <path android:fillColor=\"");
            body.push_str(&fill);
            body.push('"');
            if let Some(o) = opacity {
                body.push_str(" android:fillAlpha=\"");
                body.push_str(&o);
                body.push('"');
            }
            body.push_str(" android:pathData=\"");
            body.push_str(d);
            body.push_str("\"/>\n");
            paths += 1;
            path_data_chars += d.len();
        }
    }

    let (w, h) = match (width, height) {
        (Some(w), Some(h)) if w > 0.0 && h > 0.0 => (w, h),
        _ => return Err("SVG has no usable width/height".into()),
    };
    let (w, h) = (num(w), num(h));
    let mut xml = String::with_capacity(body.len() + 400);
    xml.push_str("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
    xml.push_str("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
    xml.push_str(&format!("    android:width=\"{w}dp\"\n    android:height=\"{h}dp\"\n"));
    xml.push_str(&format!("    android:viewportWidth=\"{w}\"\n    android:viewportHeight=\"{h}\">\n"));
    xml.push_str(&body);
    xml.push_str("</vector>\n");
    Ok(VectorXml { xml, paths, path_data_chars })
}

/// Value of ` name="…"` inside a tag (the leading space keeps `fill` from
/// matching `fill-opacity`).
fn attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let key = format!(" {name}=\"");
    let from = tag.find(&key)? + key.len();
    let len = tag[from..].find('"')?;
    Some(&tag[from..from + len])
}

fn parse_len(s: &str) -> Option<f64> {
    s.trim_end_matches(|c: char| c.is_alphabetic() || c == '%').trim().parse().ok()
}

fn num(v: f64) -> String {
    if v.fract() == 0.0 { format!("{}", v as i64) } else { format!("{v}") }
}
