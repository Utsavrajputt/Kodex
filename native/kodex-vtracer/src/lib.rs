//! JNI bridge for the Image tracer tool: `com.invictus.kodex.core.tracer.NativeTracer`.
//!
//! The pure-Rust entry points ([`trace_rgba`], [`xml::svg_to_vector_xml`]) hold
//! all the logic and are unit-tested on the host; the `Java_…` functions only
//! marshal arguments and turn errors into Java exceptions.

pub mod params;
pub mod xml;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use vtracer::ColorImage;

/// Trace straight (non-premultiplied) RGBA pixels into an SVG document.
///
/// `quality` is 1..=5 and `colors` the palette cap (0 = all); see [`params`].
pub fn trace_rgba(
    rgba: Vec<u8>,
    width: usize,
    height: usize,
    quality: i32,
    colors: i32,
) -> Result<String, String> {
    if width == 0 || height == 0 {
        return Err("image is empty".into());
    }
    if rgba.len() != width * height * 4 {
        return Err(format!(
            "expected {} RGBA bytes for {width}x{height}, got {}",
            width * height * 4,
            rgba.len()
        ));
    }
    let img = ColorImage { pixels: rgba, width, height };
    params::config_for(quality, colors)
        .build()
        .map_err(|e| e.to_string())?
        .to_svg(&img)
        .map_err(|e| e.to_string())
}

fn throw(env: &mut JNIEnv, msg: &str) -> jstring {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
    null_mut()
}

fn panic_message(p: Box<dyn std::any::Any + Send>) -> String {
    p.downcast_ref::<&str>()
        .map(|s| s.to_string())
        .or_else(|| p.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "native tracer panicked".into())
}

fn finish(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(text) => match env.new_string(text) {
            Ok(s) => s.into_raw(),
            Err(e) => throw(env, &format!("could not return result: {e}")),
        },
        Err(msg) => throw(env, &msg),
    }
}

/// `external fun trace(rgba: ByteArray, width: Int, height: Int, quality: Int, colors: Int): String`
#[no_mangle]
pub extern "system" fn Java_com_invictus_kodex_core_tracer_NativeTracer_trace<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    rgba: JByteArray<'l>,
    width: jint,
    height: jint,
    quality: jint,
    colors: jint,
) -> jstring {
    let bytes = match env.convert_byte_array(&rgba) {
        Ok(b) => b,
        Err(e) => return throw(&mut env, &format!("could not read pixels: {e}")),
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        trace_rgba(bytes, width.max(0) as usize, height.max(0) as usize, quality, colors)
    }))
    .unwrap_or_else(|p| Err(panic_message(p)));
    finish(&mut env, result)
}

/// `external fun toVectorXml(svg: String): String`
#[no_mangle]
pub extern "system" fn Java_com_invictus_kodex_core_tracer_NativeTracer_toVectorXml<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    svg: JString<'l>,
) -> jstring {
    let text: String = match env.get_string(&svg) {
        Ok(s) => s.into(),
        Err(e) => return throw(&mut env, &format!("could not read SVG: {e}")),
    };
    let result = catch_unwind(AssertUnwindSafe(|| xml::svg_to_vector_xml(&text).map(|v| v.xml)))
        .unwrap_or_else(|p| Err(panic_message(p)));
    finish(&mut env, result)
}
