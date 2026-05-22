import { chromium } from "playwright";

const DEFAULT_WASM_URL = "https://webrtc.13322.net/front/webrtc/decode-j8.wasm";
const DEFAULT_TINY_JS_URL = "https://www.599.com/js/wasm_exec_tiny.js";

function pickDecoder(g) {
  const candidates = [
    "decode",
    "decodeJ8",
    "decode_j8",
    "decodeJ8Data",
    "decodeData",
    "wasmDecode",
    "decodeWasm",
    "decrypt",
  ];
  for (const n of candidates) {
    if (typeof g[n] === "function") return n;
  }
  for (const [k, v] of Object.entries(g)) {
    if (typeof v === "function" && /^decode/i.test(k)) return k;
  }
  return null;
}

async function main() {
  const webaslUrl = process.argv[2];
  if (!webaslUrl) {
    console.error('usage: node ./decode_webasl_pw.mjs "<full webasl url with st/sign>"');
    process.exit(2);
  }

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({
    userAgent:
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
  });
  const page = await ctx.newPage();

  const out = await page.evaluate(
    async ({ tinyJsUrl, wasmUrl, webaslUrl }) => {
      // wasm_exec_tiny.js contains Node fallbacks that reference process.exit
      // if it thinks it's in Node; provide a safe stub.
      if (!globalThis.process) {
        globalThis.process = { exit: () => {}, argv: [], env: {} };
      } else if (typeof globalThis.process.exit !== "function") {
        globalThis.process.exit = () => {};
      }

      const loadScript = (src) =>
        new Promise((resolve, reject) => {
          const s = document.createElement("script");
          s.src = src;
          s.onload = () => resolve();
          s.onerror = (e) => reject(e);
          document.head.appendChild(s);
        });

      await loadScript(tinyJsUrl);

      // boot go/wasm runtime
      const go = new globalThis.Go();
      const wasmRes = await fetch(wasmUrl);
      const wasmBytes = await wasmRes.arrayBuffer();
      const { instance } = await WebAssembly.instantiate(wasmBytes, go.importObject);
      // run registers decoder functions on global; it typically never resolves.
      go.run(instance);
      let decName = null;
      for (let i = 0; i < 40; i++) {
        decName = (() => {
          const g = globalThis;
          const candidates = [
            "decode",
            "decodeJ8",
            "decode_j8",
            "decodeJ8Data",
            "decodeData",
            "wasmDecode",
            "decodeWasm",
            "decrypt",
          ];
          for (const n of candidates) if (typeof g[n] === "function") return n;
          for (const k of Object.keys(g)) {
            if (/^decode/i.test(k) && typeof g[k] === "function") return k;
          }
          return null;
        })();
        if (decName) break;
        await new Promise((r) => setTimeout(r, 50));
      }

      const j = await (await fetch(webaslUrl)).json();
      const blob = j?.data;
      if (typeof blob !== "string") return { error: "no blob data" };

      if (!decName) return { error: "decoder not found" };

      let decoded = globalThis[decName](blob);
      if (typeof decoded === "string") {
        try {
          decoded = JSON.parse(decoded);
        } catch {}
      }

      const findEventArray = (x) => {
        if (!x) return null;
        if (Array.isArray(x) && x.length) {
          const a0 = x[0];
          if (
            a0 &&
            typeof a0 === "object" &&
            "msgId" in a0 &&
            "time" in a0 &&
            "msgText" in a0 &&
            "code" in a0
          )
            return x;
        }
        if (Array.isArray(x)) {
          for (const it of x) {
            const r = findEventArray(it);
            if (r) return r;
          }
        } else if (typeof x === "object") {
          for (const v of Object.values(x)) {
            const r = findEventArray(v);
            if (r) return r;
          }
        }
        return null;
      };

      const events = findEventArray(decoded);
      return {
        decName,
        events: events || decoded,
      };
    },
    { tinyJsUrl: DEFAULT_TINY_JS_URL, wasmUrl: DEFAULT_WASM_URL, webaslUrl }
  );

  await browser.close();

  if (out?.error) {
    console.error(out.error);
    process.exit(1);
  }
  console.log(JSON.stringify(out.events));
}

await main();

