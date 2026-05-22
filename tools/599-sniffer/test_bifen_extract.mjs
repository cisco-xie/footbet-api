import { chromium } from "playwright";

async function testBifenId(matchId) {
  const browser = await chromium.launch({ 
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
  });
  
  const context = await browser.newContext({
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    viewport: { width: 1400, height: 900 },
  });
  
  const page = await context.newPage();
  
  try {
    const url = `https://www.599.com/live/${matchId}/`;
    console.log(`访问详情页: ${url}`);
    
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 15000 });
    await page.waitForTimeout(1000);
    
    // 获取页面中的所有链接
    const links = await page.evaluate(() => {
      const result = [];
      document.querySelectorAll("a").forEach(link => {
        const href = link.getAttribute("href");
        if (href) {
          result.push(href);
        }
      });
      return result;
    });
    
    console.log(`\n找到 ${links.length} 个链接`);
    
    // 筛选包含bifen的链接
    const bifenLinks = links.filter(link => link.includes("bifen"));
    console.log(`\n包含"bifen"的链接:`);
    for (const link of bifenLinks) {
      console.log(link);
    }
    
    // 尝试提取bifenId
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
    
    console.log(`\n提取的bifenId: ${bifenId}`);
    console.log(`生成的detailUrl: https://www.599.com/live/${matchId}/bifen-${bifenId}.html`);
    
    return bifenId;
    
  } catch (error) {
    console.error('Error:', error.message);
    return null;
  } finally {
    await context.close();
    await browser.close();
  }
}

// 测试用户提到的赛事
testBifenId("60_2944704");