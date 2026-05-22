import { chromium } from "playwright";

async function test() {
  const browser = await chromium.launch({ 
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
  });
  
  const context = await browser.newContext({
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    viewport: { width: 1400, height: 900 },
  });
  
  // 测试深圳新鹏城和大连英博的赛事
  const testMatches = [
    { matchId: '60_2944704', homeTeam: '深圳新鹏城', awayTeam: '大连英博', expectedBifenId: '4786145' }
  ];
  
  for (const testMatch of testMatches) {
    const page = await context.newPage();
    try {
      const url = `https://www.599.com/live/${testMatch.matchId}/`;
      console.log(`Testing: ${testMatch.homeTeam} vs ${testMatch.awayTeam}`);
      console.log(`URL: ${url}`);
      
      await page.goto(url, { waitUntil: "domcontentloaded", timeout: 20000 });
      await page.waitForTimeout(1000);
      
      const bifenId = await page.evaluate(() => {
        const links = document.querySelectorAll("a");
        for (const link of links) {
          const href = link.getAttribute("href");
          if (href && href.includes("bifen-")) {
            const match = href.match(/bifen-(\d+)\.html/);
            if (match) {
              return match[1];
            }
          }
        }
        return null;
      });
      
      const detailUrl = bifenId 
        ? `https://www.599.com/live/${testMatch.matchId}/bifen-${bifenId}.html`
        : `https://www.599.com/live/${testMatch.matchId}/`;
      
      console.log(`Found bifenId: ${bifenId}`);
      console.log(`Expected bifenId: ${testMatch.expectedBifenId}`);
      console.log(`Generated URL: ${detailUrl}`);
      console.log(`Expected URL: https://www.599.com/live/${testMatch.matchId}/bifen-${testMatch.expectedBifenId}.html`);
      console.log(`Match: ${bifenId === testMatch.expectedBifenId ? '✓ CORRECT' : '✗ INCORRECT'}`);
      console.log('');
      
    } catch (error) {
      console.error(`Error: ${error.message}`);
    } finally {
      await page.close();
    }
  }
  
  await context.close();
  await browser.close();
}

test();