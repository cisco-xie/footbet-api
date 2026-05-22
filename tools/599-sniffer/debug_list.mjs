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
  
  const page = await context.newPage();
  
  try {
    console.log('Navigating to live page...');
    await page.goto("https://www.599.com/live/", { waitUntil: "domcontentloaded", timeout: 30000 });
    console.log('Page loaded');
    
    await page.waitForTimeout(3000);
    
    const matchLinks = await page.evaluate(() => {
      const result = [];
      const links = document.querySelectorAll("a");
      
      for (const link of links) {
        const href = link.getAttribute("href");
        if (href && href.includes("/live/")) {
          result.push(href);
        }
      }
      
      return result;
    });
    
    console.log(`\nFound ${matchLinks.length} links containing /live/:`);
    const bifenLinks = matchLinks.filter(l => l.includes('bifen-'));
    console.log(`Found ${bifenLinks.length} links containing bifen-:`);
    for (const link of bifenLinks) {
      console.log(link);
    }
    
    const matchDivs = await page.evaluate(() => {
      const result = [];
      const divs = document.querySelectorAll("div.match");
      
      for (const div of divs) {
        const attrs = {};
        for (const attr of div.attributes) {
          attrs[attr.name] = attr.value;
        }
        result.push({
          attrs,
          hasBifen: div.innerHTML.includes('bifen')
        });
      }
      
      return result;
    });
    
    console.log(`\nFound ${matchDivs.length} match divs:`);
    for (let i = 0; i < Math.min(3, matchDivs.length); i++) {
      console.log(`Match ${i + 1} attributes:`, JSON.stringify(matchDivs[i].attrs));
      console.log(`Has bifen in HTML:`, matchDivs[i].hasBifen);
    }
    
  } catch (error) {
    console.error('Error:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }
}

test();