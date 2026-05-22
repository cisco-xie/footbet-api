import { chromium } from "playwright";

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
  viewport: { width: 1400, height: 900 },
});

const page = await context.newPage();

try {
  await page.goto("https://www.599.com/live/", { waitUntil: "networkidle", timeout: 60000 });
  await page.waitForTimeout(5000);

  const links = await page.evaluate(() => {
    const result = [];
    document.querySelectorAll("a").forEach((link) => {
      const href = link.getAttribute("href");
      if (href && href.includes("/live/")) {
        result.push({
          href,
          text: link.textContent?.trim() || ""
        });
      }
    });
    return result;
  });

  console.log("Found links with /live/:");
  console.log(JSON.stringify(links, null, 2));

} catch (error) {
  console.error("Error:", error);
} finally {
  await browser.close();
}