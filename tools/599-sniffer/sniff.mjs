import { chromium } from "playwright";

const url =
  process.argv[2] ||
  "https://www.599.com/live/629_2961705/bifen-4745612.html";

const TARGET_ID = "4745612";

const KEYWORDS = [
  "wss://",
  "ws://",
  "/api",
  "api/",
  "timeline",
  "anim",
  "event",
  "incident",
  "socket",
  "push",
  "live",
  "bifen",
];

function hit(s) {
  const x = String(s || "").toLowerCase();
  return KEYWORDS.some((k) => x.includes(k));
}

function isInterestingUrl(u) {
  const x = String(u || "");
  if (hit(x)) return true;
  if (x.includes("fb-p.599.com/footballapi")) return true;
  if (x.includes("matchId=" + TARGET_ID)) return true;
  if (x.includes("thirdId=" + TARGET_ID)) return true;
  return false;
}

function safeJson(obj, maxLen = 4000) {
  try {
    const s = JSON.stringify(obj);
    return s.length > maxLen ? s.slice(0, maxLen) + "...(truncated)" : s;
  } catch {
    return "";
  }
}

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  userAgent:
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
  viewport: { width: 1400, height: 900 },
});

let logAllUntil = 0;

await context.addInitScript(() => {
  const log = (...args) => {
    try {
      // eslint-disable-next-line no-console
      console.log(...args);
    } catch {}
  };

  // Observe decoded payloads without knowing decoder entrypoint.
  try {
    const origJsonParse = JSON.parse;
    let lastEventSig = "";
    JSON.parse = function (text, reviver) {
      const out = origJsonParse.call(this, text, reviver);
      try {
        if (typeof text === "string" && text.length > 300) {
          const h = text[0];
          if (h === "{" || h === "[") {
            if (/(timeline|event|incid|corner|yellow|red|goal|substit|var|score)/i.test(text)) {
              log("[JSON.parse]", "len=", text.length, "head=", text.slice(0, 160));
            }
          }
        }

        // Identify the decoded event array shape and print callsite stack.
        if (Array.isArray(out) && out.length > 0) {
          const a0 = out[0];
          const isEventArr =
            a0 &&
            typeof a0 === "object" &&
            ("msgId" in a0) &&
            ("time" in a0) &&
            ("msgText" in a0) &&
            ("code" in a0);
          if (isEventArr) {
            let maxMsgId = -1;
            let maxTime = "";
            for (let i = 0; i < out.length; i++) {
              const it = out[i];
              const id = Number(it?.msgId);
              if (Number.isFinite(id) && id > maxMsgId) maxMsgId = id;
              const t = String(it?.time || "");
              if (t && t > maxTime) maxTime = t;
            }
            const sig = `${out.length}|${maxMsgId}|${maxTime}`;
            if (sig !== lastEventSig) {
              lastEventSig = sig;
              log("[EVENT ARRAY]", "len=", out.length, "maxMsgId=", maxMsgId, "maxTime=", maxTime);
              try {
                const sample = out.slice(0, 3).map((x) => ({
                  msgId: x?.msgId,
                  time: x?.time,
                  code: x?.code,
                  msgPlace: x?.msgPlace,
                  msgText: String(x?.msgText || "").slice(0, 80),
                  homeScore: x?.homeScore,
                  awayScore: x?.awayScore,
                }));
                log("[EVENT SAMPLE]", JSON.stringify(sample));
              } catch {}
              try {
                const st = String(new Error().stack || "");
                // keep it short
                log("[EVENT STACK]", st.split("\n").slice(0, 8).join("\n"));
              } catch {}
            }
          }
        }
      } catch {}
      return out;
    };
  } catch {}

  // Hook WebAssembly instantiation to see module/exports.
  const wrapInstance = (instance, tag) => {
    try {
      const names = Object.keys(instance?.exports || {});
      log("[WASM INSTANCE]", tag, "exports=", names.slice(0, 80));
    } catch {}
    return instance;
  };

  const origInstantiateStreaming = WebAssembly.instantiateStreaming?.bind(WebAssembly);
  if (origInstantiateStreaming) {
    WebAssembly.instantiateStreaming = async function (respPromise, importObject) {
      const resp = await respPromise;
      try {
        log("[WASM STREAM]", resp.url || "");
      } catch {}
      const out = await origInstantiateStreaming(resp, importObject);
      if (out && out.instance) wrapInstance(out.instance, "streaming");
      return out;
    };
  }

  const origInstantiate = WebAssembly.instantiate?.bind(WebAssembly);
  if (origInstantiate) {
    WebAssembly.instantiate = async function (source, importObject) {
      const out = await origInstantiate(source, importObject);
      if (out && out.instance) wrapInstance(out.instance, "instantiate");
      return out;
    };
  }

  // Hook fetch: log interesting responses and optionally decode.
  const origFetch = window.fetch?.bind(window);
  if (origFetch) {
    window.fetch = async function (...args) {
      const res = await origFetch(...args);
      try {
        const url = String(res.url || "");
        if (/footballapi|decode-j8\.wasm|webrtc\.13322\.net/i.test(url)) {
          log("[FETCH]", res.status, url);
        }
      } catch {}

      // Try to observe blob payloads that are likely decoded by wasm.
      try {
        const url = String(res.url || "");
        const ct = String(res.headers.get("content-type") || "");
        const interesting =
          /footballapi/i.test(url) && /json/i.test(ct);
        if (interesting) {
          const clone = res.clone();
          clone
            .json()
            .then((j) => {
              const data = j && j.data;
              if (typeof data === "string" && data.length > 200) {
                log("[BLOB JSON]", url, "len=", data.length, "head=", data.slice(0, 40));

                // Best-effort: try common global decoder names.
                const candidates = [
                  "decode",
                  "decodeJ8",
                  "decode_j8",
                  "decodeJ8Data",
                  "decodeData",
                  "wasmDecode",
                  "decrypt",
                  "decodeWasm",
                ];
                for (const name of candidates) {
                  const fn = window[name];
                  if (typeof fn === "function") {
                    try {
                      const out = fn(data);
                      const dump =
                        out && typeof out === "object"
                          ? Object.keys(out).slice(0, 40)
                          : typeof out;
                      log("[DECODE TRY]", name, "ok keys=", dump);
                      break;
                    } catch (e) {
                      log("[DECODE TRY]", name, "fail", String(e?.message || e));
                    }
                  }
                }
              }
            })
            .catch(() => {});
        }
      } catch {}

      return res;
    };
  }

  // Hook XHR (many sites use axios/XHR instead of fetch).
  try {
    const OrigXHR = window.XMLHttpRequest;
    if (OrigXHR) {
      function WrappedXHR() {
        const xhr = new OrigXHR();
        let _url = "";
        const origOpen = xhr.open;
        xhr.open = function (method, url, ...rest) {
          _url = String(url || "");
          return origOpen.call(this, method, url, ...rest);
        };
        xhr.addEventListener("loadend", () => {
          try {
            if (!_url) return;
            if (!/footballapi/i.test(_url)) return;
            const ct = String(xhr.getResponseHeader("content-type") || "");
            const txt =
              typeof xhr.responseText === "string" ? xhr.responseText : "";
            if (!txt) return;
            if (/json/i.test(ct) && txt.includes("\"data\":\"") && txt.length > 500) {
              const head = txt.slice(0, 120);
              log("[XHR JSON]", _url, "len=", txt.length, "head=", head);
            }
          } catch {}
        });
        return xhr;
      }
      // @ts-ignore
      window.XMLHttpRequest = WrappedXHR;
    }
  } catch {}
});

context.on("websocket", (ws) => {
  const wsUrl = ws.url();
  console.log("\n[WS OPEN]", wsUrl);
  let recv = 0;
  let sent = 0;
  ws.on("framereceived", (frame) => {
    const payload = frame.payload;
    if (Date.now() < logAllUntil || isInterestingUrl(wsUrl) || hit(payload)) {
      console.log("\n[WS RECV]", wsUrl);
      console.log(payload.slice(0, 4000));
      recv++;
    }
  });
  ws.on("framesent", (frame) => {
    const payload = frame.payload;
    if (Date.now() < logAllUntil || isInterestingUrl(wsUrl) || hit(payload)) {
      console.log("\n[WS SEND]", wsUrl);
      console.log(payload.slice(0, 2000));
      sent++;
    }
  });
});

const page = await context.newPage();

page.on("console", (msg) => {
  try {
    console.log("\n[PAGE]", msg.type(), msg.text());
  } catch {}
});

page.on("request", (req) => {
  const u = req.url();
  if (!(Date.now() < logAllUntil || isInterestingUrl(u))) return;
  const mt = req.method();
  const rt = req.resourceType();
  if (rt === "xhr" || rt === "fetch") {
    console.log("\n[HTTP REQ]", mt, u);
    const postData = req.postData();
    if (postData) console.log("[HTTP REQ BODY]", postData.slice(0, 2000));
  }
});

page.on("response", async (res) => {
  const u = res.url();
  if (!(Date.now() < logAllUntil || isInterestingUrl(u))) return;
  const req = res.request();
  const rt = req.resourceType();
  if (rt !== "xhr" && rt !== "fetch") return;
  console.log("\n[HTTP RES]", res.status(), u);
  const ct = (res.headers()["content-type"] || "").toLowerCase();
  try {
    if (ct.includes("application/json")) {
      const j = await res.json();
      console.log("[HTTP RES JSON]", safeJson(j));
    } else {
      const txt = await res.text();
      console.log("[HTTP RES TEXT]", txt.slice(0, 4000));
    }
  } catch (e) {
    console.log("[HTTP RES READ ERROR]", String(e?.message || e));
  }
});

await page.goto(url, { waitUntil: "domcontentloaded", timeout: 60000 });

try {
  await page.waitForTimeout(2000);
  const before = page.url();
  const info = await page.evaluate(() => {
    const texts = ["动画直播", "动画"];

    // Prefer the tab item "动画直播" inside a tab bar like:
    // "数据分析 方案 动画直播 欧洲指数 ..."
    const tabCandidates = Array.from(
      document.querySelectorAll("a,button,li,div,span")
    ).filter((el) => (el.textContent || "").trim() === "动画直播");
    for (const el of tabCandidates) {
      const p = el.parentElement;
      const ctx = (p ? p.textContent : "") || "";
      if (ctx.includes("数据分析") && ctx.includes("欧洲指数")) {
        try {
          el.click();
          return { clicked: true, picks: [{ tag: el.tagName, text: "动画直播(tab)", href: (el.closest("a")?.getAttribute("href") || ""), score: 999 }] };
        } catch {}
      }
    }

    const nodes = Array.from(
      document.querySelectorAll("a,button,li,div,span")
    );
    const scored = nodes
      .map((el) => {
        const t = (el.textContent || "").trim();
        if (!t) return null;
        const hit = texts.find((x) => t.includes(x));
        if (!hit) return null;
        const a = el.closest("a");
        const href = a ? a.getAttribute("href") || "" : "";
        // avoid clicking match links like /live/xxx/bifen-yyy.html
        const isMatchLink =
          href.includes("/live/") && href.includes("bifen-") && href.endsWith(".html");
        const rect = el.getBoundingClientRect();
        const visible = rect.width > 0 && rect.height > 0;
        const score =
          (hit === "动画直播" ? 100 : 10) +
          (isMatchLink ? -1000 : 0) +
          (visible ? 5 : -5) +
          Math.min(50, t.length);
        return { el, score };
      })
      .filter(Boolean)
      .sort((a, b) => b.score - a.score);

    const picks = scored.slice(0, 10).map((x) => {
      const el = x.el;
      const a = el.closest("a");
      const href = a ? a.getAttribute("href") || "" : "";
      const t = (el.textContent || "").trim().slice(0, 60);
      return { tag: el.tagName, text: t, href, score: x.score };
    });

    for (const item of scored.slice(0, 10)) {
      try {
        item.el.click();
        return { clicked: true, picks };
      } catch {}
    }
    return { clicked: false, picks };
  });

  if (info?.picks?.length) {
    console.log("\n[ANIM CANDIDATES]", JSON.stringify(info.picks));
  }

  if (info?.clicked) {
    logAllUntil = Date.now() + 12000;
    await page.waitForTimeout(1500);
    const after = page.url();
    if (after !== before) {
      await page.goto(before, { waitUntil: "domcontentloaded", timeout: 60000 });
    }

    const embeds = await page.evaluate(() => {
      const iframes = Array.from(document.querySelectorAll("iframe"))
        .map((x) => x.getAttribute("src") || "")
        .filter(Boolean);
      const scripts = Array.from(document.querySelectorAll("script[src]"))
        .map((x) => x.getAttribute("src") || "")
        .filter(Boolean);
      return { iframes, scripts };
    });
    if (embeds?.iframes?.length) {
      console.log("\n[IFRAME SRCS]", JSON.stringify(embeds.iframes.slice(0, 20)));
    }
    if (embeds?.scripts?.length) {
      console.log("\n[SCRIPTS ALL]", JSON.stringify(embeds.scripts.slice(0, 30)));
      const hot = embeds.scripts.filter((s) =>
        /webrtc|anim|wasm|live|wasm_exec|gojs|decode-j8/i.test(s)
      );
      if (hot.length) console.log("\n[SCRIPT SRCS]", JSON.stringify(hot.slice(0, 20)));
    }
  }
} catch {}

await page.waitForTimeout(20000);

await browser.close();

