import { runTinyWasm } from "./wasm_tiny_loader.mjs";

const DEFAULT_WASM_URL = "https://webrtc.13322.net/front/webrtc/decode-j8.wasm";

function pickDecoder(ctx) {
  const g = ctx.globalThis || ctx;
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
    if (typeof g[n] === "function") return { name: n, fn: g[n] };
  }
  // last resort: any function that decodes a string -> string/object
  for (const [k, v] of Object.entries(g)) {
    if (typeof v === "function" && /^decode/i.test(k)) return { name: k, fn: v };
  }
  return null;
}

function findEventArray(decoded) {
  if (!decoded) return null;
  const out = [];
  const visit = (x) => {
    if (!x) return;
    if (Array.isArray(x) && x.length) {
      const a0 = x[0];
      if (
        a0 &&
        typeof a0 === "object" &&
        "msgId" in a0 &&
        "time" in a0 &&
        "msgText" in a0 &&
        "code" in a0
      ) {
        return x;
      }
    }
    if (Array.isArray(x)) {
      for (const it of x) {
        const r = visit(it);
        if (r) return r;
      }
    } else if (typeof x === "object") {
      for (const v of Object.values(x)) {
        const r = visit(v);
        if (r) return r;
      }
    }
    return null;
  };
  return visit(decoded) || (out.length ? out[0] : null);
}

async function main() {
  const webaslUrl = process.argv[2];
  if (!webaslUrl) {
    console.error('usage: node ./decode_webasl.mjs "<full webasl url with st/sign>"');
    process.exit(2);
  }

  // 1) fetch blob
  const res = await fetch(webaslUrl, { redirect: "follow" });
  if (!res.ok) {
    console.error("webasl fetch failed:", res.status, webaslUrl);
    process.exit(1);
  }
  const j = await res.json();
  const blob = j?.data;
  if (typeof blob !== "string" || blob.length < 50) {
    console.error("unexpected webasl response (no blob data)");
    process.exit(1);
  }

  // 2) boot wasm decoder runtime
  const { ctx } = await runTinyWasm({ wasmUrl: DEFAULT_WASM_URL });

  // 3) call decoder
  const dec = pickDecoder(ctx);
  if (!dec) {
    console.error("decoder function not found on wasm runtime global");
    process.exit(1);
  }

  let decoded;
  try {
    decoded = dec.fn(blob);
  } catch (e) {
    console.error("decode failed:", dec.name, String(e?.message || e));
    process.exit(1);
  }

  // 4) normalize
  let parsed = decoded;
  if (typeof decoded === "string") {
    try {
      parsed = JSON.parse(decoded);
    } catch {
      // some decoders emit multiple JSON strings; try best-effort split
      const iObj = decoded.indexOf("{");
      const iArr = decoded.indexOf("[");
      const i = iArr === -1 ? iObj : iObj === -1 ? iArr : Math.min(iObj, iArr);
      if (i >= 0) {
        parsed = JSON.parse(decoded.slice(i));
      }
    }
  }

  const events = findEventArray(parsed);
  if (!events) {
    // fallback: if parsed is an array already
    if (Array.isArray(parsed)) {
      console.log(JSON.stringify(parsed));
      return;
    }
    console.log(JSON.stringify(parsed));
    return;
  }

  console.log(JSON.stringify(events));
}

await main();

