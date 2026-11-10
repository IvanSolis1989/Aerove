use rquickjs::function::Rest;
use rquickjs::{Ctx, Function, Object, Result, Value};

const LEVELS: [&str; 6] = ["log", "info", "warn", "error", "debug", "trace"];

/// Scripts written for other clients log while they work. Without a `console`
/// the first such call aborts the whole profile, so provide one.
/// Arguments may contain credentials; never inspect or forward them.
pub fn install(ctx: &Ctx<'_>) -> Result<()> {
    let console = Object::new(ctx.clone())?;
    for level in LEVELS {
        let discard = Function::new(ctx.clone(), |_: Rest<Value>| {})?;
        console.set(level, discard)?;
    }
    ctx.globals().set("console", console)
}
