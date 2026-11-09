//! JNI bridge for the Image tracer tool: `com.invictus.kodex.core.tracer.NativeTracer`.
//!
//! The pure-Rust entry points ([`trace_rgba`], [`xml::svg_to_vector_xml`]) hold
//! all the logic and are unit-tested on the host; the `Java_…` functions only
//! marshal arguments and turn errors into Java exceptions.

pub mod params;
pub mod xml;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;
use std::sync::Mutex;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use vtracer::{CancelToken, Clustering, ColorImage, Session};

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
    check_pixels(&rgba, width, height)?;
    let img = ColorImage { pixels: rgba, width, height };
    params::config_for(quality, colors)
        .build()
        .map_err(|e| e.to_string())?
        .to_svg(&img)
        .map_err(|e| e.to_string())
}

fn check_pixels(rgba: &[u8], width: usize, height: usize) -> Result<(), String> {
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
    Ok(())
}

/// One picture, traced many times. The clustering (the expensive stage) is cached, so a render
/// that only changes the palette cap (Colors) skips it; the output is byte-identical to
/// [`trace_rgba`] with the same arguments.
///
/// Pixel-exact quality bypasses the cache: its segmentation is huge, and keeping a copy of it
/// (the cached path clones it per render) would cost far more memory than it saves time.
///
/// [`TraceSession::cancel`] aborts the render in flight, so a slider or chip moved mid-trace
/// does not leave a stale trace burning CPU. Renders on one session are serialized.
pub struct TraceSession {
    session: Mutex<Session>,
    /// Token of the render in flight (or the last one); replaced at the start of every render.
    cancel: Mutex<CancelToken>,
}

impl TraceSession {
    pub fn new(rgba: Vec<u8>, width: usize, height: usize) -> Result<Self, String> {
        check_pixels(&rgba, width, height)?;
        Ok(Self {
            session: Mutex::new(Session::new(ColorImage { pixels: rgba, width, height })),
            cancel: Mutex::new(CancelToken::new()),
        })
    }

    /// Ask the render in flight to stop; it then fails with "conversion cancelled".
    pub fn cancel(&self) {
        self.cancel.lock().unwrap_or_else(|e| e.into_inner()).cancel();
    }

    pub fn render(&self, quality: i32, colors: i32) -> Result<String, String> {
        let cfg = params::config_for(quality, colors);
        // Waits for a previous (cancelled) render to let go before starting.
        let mut session = self.session.lock().unwrap_or_else(|e| e.into_inner());
        let token = CancelToken::new();
        *self.cancel.lock().unwrap_or_else(|e| e.into_inner()) = token.clone();

        let pipeline = cfg.build().map_err(|e| e.to_string())?;
        let result = if cfg.clustering == Clustering::Exact {
            pipeline.run_with_progress(session.image(), &token, &mut |_| {})
        } else {
            session.render_with_progress(&cfg, &token, &mut |_| {})
        };
        let doc = result.map_err(|e| e.to_string())?;
        Ok(pipeline.writer.write(&doc))
    }
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

// ---- session API: one native handle per picture ----------------------------------------------
// The Kotlin side (`TraceSession`) serializes close() against every other call, so a handle is
// never used after it is freed.

fn session_ref<'a>(handle: jlong) -> Option<&'a TraceSession> {
    if handle == 0 {
        None
    } else {
        // SAFETY: non-zero handles come from `sessionOpen` and stay valid until `sessionClose`.
        Some(unsafe { &*(handle as *const TraceSession) })
    }
}

/// `external fun sessionOpen(rgba: ByteArray, width: Int, height: Int): Long`
#[no_mangle]
pub extern "system" fn Java_com_invictus_kodex_core_tracer_NativeTracer_sessionOpen<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    rgba: JByteArray<'l>,
    width: jint,
    height: jint,
) -> jlong {
    let bytes = match env.convert_byte_array(&rgba) {
        Ok(b) => b,
        Err(e) => {
            let _ = throw(&mut env, &format!("could not read pixels: {e}"));
            return 0;
        }
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        TraceSession::new(bytes, width.max(0) as usize, height.max(0) as usize)
    }))
    .unwrap_or_else(|p| Err(panic_message(p)));
    match result {
        Ok(session) => Box::into_raw(Box::new(session)) as jlong,
        Err(msg) => {
            let _ = throw(&mut env, &msg);
            0
        }
    }
}

/// `external fun sessionRender(handle: Long, quality: Int, colors: Int): String`
#[no_mangle]
pub extern "system" fn Java_com_invictus_kodex_core_tracer_NativeTracer_sessionRender<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    quality: jint,
    colors: jint,
) -> jstring {
    let Some(session) = session_ref(handle) else {
        return throw(&mut env, "tracer session is closed");
    };
    let result = catch_unwind(AssertUnwindSafe(|| session.render(quality, colors)))
        .unwrap_or_else(|p| Err(panic_message(p)));
    finish(&mut env, result)
}

/// `external fun sessionCancel(handle: Long)`
#[no_mangle]
pub extern "system" fn Java_com_invictus_kodex_core_tracer_NativeTracer_sessionCancel<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(session) = session_ref(handle) {
        session.cancel();
    }
}

/// `external fun sessionClose(handle: Long)`
#[no_mangle]
pub extern "system" fn Java_com_invictus_kodex_core_tracer_NativeTracer_sessionClose<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: the handle came from `Box::into_raw` in `sessionOpen` and is closed once.
        drop(unsafe { Box::from_raw(handle as *mut TraceSession) });
    }
}
