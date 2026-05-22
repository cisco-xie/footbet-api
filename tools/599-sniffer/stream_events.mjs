import { chromium } from "playwright";

const url =
  process.argv[2] || "https://www.599.com/live/25_2916037/bifen-4737731.html";

const durationMs = Number(process.env.DURATION_MS || 10 * 60_000);
const heartbeatMs = Number(process.env.HEARTBEAT_MS || 10_000);
const stallReloadMs = Number(process.env.STALL_RELOAD_MS || 20_000);

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  userAgent:
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
  viewport: { width: 1400, height: 900 },
});

await context.addInitScript(() => {
  const emit = (payload) => {
    try {
      if (typeof globalThis.__emitEvents === "function") {
        globalThis.__emitEvents(payload);
      } else {
        (globalThis.__eventQueue ||= []).push(payload);
      }
    } catch {}
  };

  try {
    const orig = JSON.parse;
    let lastSig = "";
    let lastMaxMsgId = -1;
    JSON.parse = function (text, reviver) {
      const out = orig.call(this, text, reviver);
      try {
        if (Array.isArray(out) && out.length) {
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
            for (let i = 0; i < out.length; i++) {
              const id = Number(out[i]?.msgId);
              if (Number.isFinite(id) && id > maxMsgId) maxMsgId = id;
            }
            const sig = `${out.length}|${maxMsgId}`;
            if (sig !== lastSig) {
              lastSig = sig;
              const newOnes = [];
              for (let i = 0; i < out.length; i++) {
                const it = out[i];
                const id = Number(it?.msgId);
                if (Number.isFinite(id) && id > lastMaxMsgId) {
                  newOnes.push({
                    msgId: it?.msgId,
                    time: it?.time,
                    code: it?.code,
                    msgPlace: it?.msgPlace,
                    msgText: String(it?.msgText || "").slice(0, 120),
                    homeScore: it?.homeScore,
                    awayScore: it?.awayScore,
                    state: it?.state,
                    showId: it?.showId,
                    enNum: it?.enNum,
                  });
                }
              }
              if (Number.isFinite(maxMsgId) && maxMsgId > lastMaxMsgId) lastMaxMsgId = maxMsgId;

              emit({ type: "snapshot", ts: Date.now(), len: out.length, maxMsgId });
              if (newOnes.length) {
                // sort by msgId asc for stable downstream processing
                newOnes.sort((a, b) => Number(a.msgId) - Number(b.msgId));
                emit({ type: "delta", ts: Date.now(), count: newOnes.length, events: newOnes });
              }
            }
          }
        }
      } catch {}
      return out;
    };
  } catch {}
});

const page = await context.newPage();

let lastEmitAt = Date.now();

await page.exposeFunction("__emitEvents", (payload) => {
  lastEmitAt = Date.now();
  process.stdout.write(JSON.stringify(payload) + "\n");
});

// flush queued emits if any
await page.addInitScript(() => {
  try {
    if (Array.isArray(globalThis.__eventQueue) && typeof globalThis.__emitEvents === "function") {
      for (const p of globalThis.__eventQueue) globalThis.__emitEvents(p);
      globalThis.__eventQueue = [];
    }
  } catch {}
});

async function clickAnimTab() {
  try {
    await page.waitForTimeout(1500);
    await page.evaluate(() => {
      const t = "动画直播";
      const nodes = Array.from(document.querySelectorAll("a,button,li,div,span"));
      const exact = nodes.filter((el) => (el.textContent || "").trim() === t);
      for (const el of exact) {
        const p = el.parentElement;
        const ctx = (p ? p.textContent : "") || "";
        if (ctx.includes("数据分析") && ctx.includes("欧洲指数")) {
          try { el.click(); return true; } catch {}
        }
      }
      for (const el of exact) {
        try { el.click(); return true; } catch {}
      }
      const fuzzy = nodes.filter((el) => (el.textContent || "").includes(t));
      for (const el of fuzzy.slice(0, 5)) {
        try { el.click(); return true; } catch {}
      }
      return false;
    });
  } catch {}
}

async function openOnce() {
  await page.goto(url, { waitUntil: "domcontentloaded", timeout: 60000 });
  await clickAnimTab();
}

await openOnce();

const startedAt = Date.now();
let lastHeartbeatAt = 0;
while (Date.now() - startedAt < durationMs) {
  const now = Date.now();
  if (heartbeatMs > 0 && now - lastHeartbeatAt >= heartbeatMs) {
    lastHeartbeatAt = now;
    process.stdout.write(JSON.stringify({ type: "heartbeat", ts: now }) + "\n");
  }
  if (stallReloadMs > 0 && now - lastEmitAt >= stallReloadMs) {
    lastEmitAt = now;
    process.stdout.write(JSON.stringify({ type: "reload", ts: now }) + "\n");
    try {
      await page.reload({ waitUntil: "domcontentloaded", timeout: 60000 });
      await clickAnimTab();
    } catch {
      try {
        await openOnce();
      } catch {}
    }
  }
  await page.waitForTimeout(250);
}
await browser.close();

