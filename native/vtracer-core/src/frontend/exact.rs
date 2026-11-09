//! Lossless, pixel-exact segmentation.
//!
//! [`ExactFrontend`] does no clustering, merging or speckle filtering: every
//! 4-connected run of pixels sharing an identical RGBA value becomes its own
//! region, painted with that exact color. Flattening those regions gives back
//! the source image bit for bit, so — paired with the pixel fitter and the
//! mosaic compositor (see `Preset::Exact`) — the resulting SVG rasterizes
//! 1:1 to the input.
//!
//! Fully transparent pixels (`a == 0`) belong to no region and are left
//! uncovered. Partially transparent pixels keep their alpha in the paint; the
//! SVG writer turns it into `fill-opacity` when its `alpha` flag is set.
//!
//! The price of exactness is size: a smooth gradient or a photograph yields a
//! region per distinct color run, so expect large SVGs on such input.

use visioncortex::{BinaryImage, Color, ColorImage, PointI32};

use crate::error::Error;
use crate::ir::{Layer, Paint, RegionMask, Segmentation};
use crate::progress::{CancelToken, Ctx, Phase};

use super::Frontend;

const UNLABELED: u32 = u32::MAX;

/// Pixel-exact frontend: one region per 4-connected same-color component.
#[derive(Debug, Clone, Copy, Default)]
pub struct ExactFrontend;

impl ExactFrontend {
    fn run(&self, img: &ColorImage, ctx: &mut Ctx) -> Result<Segmentation, Error> {
        let (w, h) = (img.width, img.height);
        if w == 0 || h == 0 {
            return Err(Error::EmptyImage);
        }
        debug_assert_eq!(img.pixels.len(), w * h * 4);

        let px = |i: usize| -> [u8; 4] {
            [
                img.pixels[i * 4],
                img.pixels[i * 4 + 1],
                img.pixels[i * 4 + 2],
                img.pixels[i * 4 + 3],
            ]
        };

        // Pass 1: flood-fill every component. `members` holds all pixel
        // indices grouped by region; `regions` records each group's slice and
        // bounding box.
        struct Region {
            start: usize,
            end: usize,
            color: [u8; 4],
            min_x: usize,
            min_y: usize,
            max_x: usize,
            max_y: usize,
        }

        let mut labels = vec![UNLABELED; w * h];
        let mut members: Vec<u32> = Vec::with_capacity(w * h);
        let mut regions: Vec<Region> = Vec::new();

        ctx.report(Phase::Segment, 0.0);
        for y in 0..h {
            ctx.check()?;
            for x in 0..w {
                let seed = y * w + x;
                if labels[seed] != UNLABELED {
                    continue;
                }
                let color = px(seed);
                if color[3] == 0 {
                    continue; // transparent: uncovered
                }

                let id = regions.len() as u32;
                let start = members.len();
                let (mut min_x, mut min_y, mut max_x, mut max_y) = (x, y, x, y);

                // Members double as the DFS stack: `cursor` walks what has
                // been discovered, appending unseen same-color neighbours.
                labels[seed] = id;
                members.push(seed as u32);
                let mut cursor = start;
                while cursor < members.len() {
                    let p = members[cursor] as usize;
                    cursor += 1;
                    let (px_, py_) = (p % w, p / w);
                    min_x = min_x.min(px_);
                    max_x = max_x.max(px_);
                    min_y = min_y.min(py_);
                    max_y = max_y.max(py_);

                    let mut visit = |n: usize| {
                        if labels[n] == UNLABELED && px(n) == color {
                            labels[n] = id;
                            members.push(n as u32);
                        }
                    };
                    if px_ > 0 {
                        visit(p - 1);
                    }
                    if px_ + 1 < w {
                        visit(p + 1);
                    }
                    if py_ > 0 {
                        visit(p - w);
                    }
                    if py_ + 1 < h {
                        visit(p + w);
                    }
                }

                regions.push(Region {
                    start,
                    end: members.len(),
                    color,
                    min_x,
                    min_y,
                    max_x,
                    max_y,
                });
            }
            ctx.report(Phase::Segment, 0.5 * (y + 1) as f32 / h as f32);
        }

        // Pass 2: materialize each region's mask.
        let mut seg = Segmentation::new(w as u32, h as u32);
        seg.layers.reserve(regions.len());
        let total = regions.len().max(1);
        for (n, r) in regions.iter().enumerate() {
            if n % 4096 == 0 {
                ctx.check()?;
                ctx.report(Phase::Segment, 0.5 + 0.5 * n as f32 / total as f32);
            }
            let (bw, bh) = (r.max_x - r.min_x + 1, r.max_y - r.min_y + 1);
            let mut image = BinaryImage::new_w_h(bw, bh);
            for &p in &members[r.start..r.end] {
                let p = p as usize;
                image.set_pixel(p % w - r.min_x, p / w - r.min_y, true);
            }
            seg.layers.push(Layer {
                paint: Paint::Solid(Color::new_rgba(
                    r.color[0], r.color[1], r.color[2], r.color[3],
                )),
                mask: RegionMask::new(
                    image,
                    PointI32 {
                        x: r.min_x as i32,
                        y: r.min_y as i32,
                    },
                ),
            });
        }
        ctx.report(Phase::Segment, 1.0);
        Ok(seg)
    }
}

impl Frontend for ExactFrontend {
    fn segment(&self, img: &ColorImage) -> Result<Segmentation, Error> {
        let cancel = CancelToken::new();
        let mut sink = |_| {};
        let mut ctx = Ctx::new(&cancel, &mut sink);
        self.run(img, &mut ctx)
    }

    fn segment_with(&self, img: &ColorImage, ctx: &mut Ctx) -> Result<Segmentation, Error> {
        self.run(img, ctx)
    }
}
