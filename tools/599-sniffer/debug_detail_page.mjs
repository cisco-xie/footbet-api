import { chromium } from "playwright";

async function test() {
  const browser = await chromium.launch({ 
    headless: false,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
  });
  
  const context = await browser.newContext({
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    viewport: { width: 1400, height: 900 },
  });
  
  const page = await context.newPage();
  
  try {
    const url = `https://www.599.com/live/60_2944704/`;
    console.log(`访问详情页: ${url}`);
    
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 15000 });
    await page.waitForTimeout(1000);
    
    // 获取页面HTML结构
    const html = await page.content();
    console.log(`\n页面HTML长度: ${html.length}`);
    
    // 查找可能包含球队名称的元素
    const teamInfo = await page.evaluate(() => {
      const result = [];
      
      // 查找所有包含"队"或中文球队名称的元素
      const allElements = document.querySelectorAll("*");
      for (const el of allElements) {
        const text = el.textContent?.trim() || "";
        if (text.length > 2 && text.length < 30) {
          // 检查是否可能是球队名称
          if (text.includes('深圳') || text.includes('大连') || 
              text.includes('城') || text.includes('队') ||
              text.includes('FC') || text.includes('俱乐部')) {
            result.push({
              text,
              className: el.className,
              tagName: el.tagName
            });
          }
        }
      }
      
      return result;
    });
    
    console.log('\n=== 可能的球队名称元素 ===');
    for (const info of teamInfo) {
      console.log(`${info.tagName}.${info.className}: "${info.text}"`);
    }
    
    // 查找比分元素
    const scoreInfo = await page.evaluate(() => {
      const result = [];
      const allElements = document.querySelectorAll("*");
      for (const el of allElements) {
        const text = el.textContent?.trim() || "";
        if (/^\d+\s*[-:]\s*\d+$/.test(text)) {
          result.push({
            text,
            className: el.className,
            tagName: el.tagName
          });
        }
      }
      return result;
    });
    
    console.log('\n=== 可能的比分元素 ===');
    for (const info of scoreInfo) {
      console.log(`${info.tagName}.${info.className}: "${info.text}"`);
    }
    
  } catch (error) {
    console.error('Error:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }
}

test();