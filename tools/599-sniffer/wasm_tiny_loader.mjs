import vm from "node:vm";
import { createRequire } from "node:module";

const DEFAULT_TINY_JS_URL = "https://www.599.com/js/wasm_exec_tiny.js";

function assertOk(cond, msg) {
  if (!cond) throw new Error(msg);
}

export async function loadWasmTinyRuntime({ tinyJsUrl = DEFAULT_TINY_JS_URL } = {}) {
  const res = await fetch(tinyJsUrl, { redirect: "follow" });
  assertOk(res.ok, `failed to fetch tiny runtime: ${res.status} ${tinyJsUrl}`);
  const code = await res.text();

  // Execute in current global to match Node environment.
  const baseRequire = createRequire(import.meta.url);
  if (!globalThis.require) globalThis.require = baseRequire;
  if (!globalThis.window) globalThis.window = globalThis;
  if (!globalThis.self) globalThis.self = globalThis;
  if (!globalThis.global) globalThis.global = globalThis;
  if (!globalThis.module) globalThis.module = { exports: {} };
  if (!globalThis.exports) globalThis.exports = globalThis.module.exports;

  vm.runInThisContext(code, { filename: "wasm_exec_tiny.js" });

  const Go = globalThis.Go;
  assertOk(typeof Go === "function", "tiny runtime did not define global Go");
  return { ctx: globalThis, Go };
}

export async function runTinyWasm({
  wasmUrl,
  tinyJsUrl,
  args = [],
} = {}) {
  assertOk(wasmUrl, "wasmUrl required");

  const { ctx, Go } = await loadWasmTinyRuntime({ tinyJsUrl });
  const go = new Go();
  if (Array.isArray(args) && args.length) {
    go.argv = ["node", ...args];
  }

  const wasmRes = await fetch(wasmUrl, { redirect: "follow" });
  assertOk(wasmRes.ok, `failed to fetch wasm: ${wasmRes.status} ${wasmUrl}`);
  const wasmBytes = await wasmRes.arrayBuffer();
  const { instance } = await WebAssembly.instantiate(wasmBytes, go.importObject);
  await go.run(instance);

  return { ctx };
}

