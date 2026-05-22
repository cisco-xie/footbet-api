import { chromium } from "playwright";

async function test() {
  const browser = await chromium.launch({ 
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  
  const context = await browser.newContext({
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    viewport: { width: 1400, height: 900 },
  });
  
  const page = await context.newPage();
  
  try {
    const url = 'https://www.599.com/live/60_2944704/';
    console.log(`Loading: ${url}`);
    
    await page.goto(url, { waitUntil: "networkidle", timeout: 30000 });
    await page.waitForTimeout(3000);
    
    const bifenId = await page.evaluate(() => {
      const links = document.querySelectorAll("a");
      console.log('Found links:', links.length);
      for (const link of links) {
        const href = link.getAttribute("href");
        if (href && href.includes("bifen-")) {
          console.log('Found bifen link:', href);
          const match = href.match(/bifen-(\d+)\.html/);
          if (match) {
            return match[1];
          }
        }
      }
      return null;
    });
    
    console.log('bifenId:', bifenId);
    console.log('Expected: 4786145');
    
    if (bifenId) {
      const detailUrl = `https://www.599.com/live/60_2944704/bifen-${bifenId}.html`;
      console.log('Generated URL:', detailUrl);
      console.log('Expected URL: https://www.599.com/live/60_2944704/bifen-4786145.html');
    }
    
  } catch (error) {
    console.error('Error:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }
}

test();