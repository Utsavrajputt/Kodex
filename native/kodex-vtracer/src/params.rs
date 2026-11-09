//! Maps the two Image tracer sliders onto a VTracer [`Config`].

use vtracer::{Config, Preset};

/// Highest quality step: pixel-exact output.
pub const QUALITY_MAX: i32 = 5;

/// `quality` is 1..=5 (1 = fewest details, smoothest; 5 = pixel-exact).
/// `colors` is the palette size cap; `0` (or less) means "All colors", no cap.
///
/// Quality 5 only stays pixel-exact with `colors == 0`; capping the palette
/// deliberately trades exactness for a smaller, simpler vector.
pub fn config_for(quality: i32, colors: i32) -> Config {
    let mut c = match quality.clamp(1, QUALITY_MAX) {
        1 => Config {
            color_precision: 5,
            layer_difference: 48,
            filter_speckle: 12,
            ..Config::default()
        },
        2 => Config {
            color_precision: 6,
            layer_difference: 32,
            filter_speckle: 8,
            ..Config::default()
        },
        3 => Config::default(),
        4 => Config {
            color_precision: 8,
            layer_difference: 4,
            filter_speckle: 1,
            length_threshold: 3.5,
            ..Config::default()
        },
        _ => Config::from_preset(Preset::Exact),
    };
    if colors > 0 {
        c.max_colors = Some(colors as usize);
    }
    c
}
