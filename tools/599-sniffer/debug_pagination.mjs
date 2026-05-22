import { chromium } from "playwright";

async function testPagination() {
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
    console.log('正在访问列表页...');
    await page.goto("https://www.599.com/live/", { waitUntil: "domcontentloaded", timeout: 30000 });
    await page.waitForTimeout(3000);

    console.log('\n=== 初始状态 ===');
    let initialCount = await page.evaluate(() => document.querySelectorAll("div.match").length);
    console.log(`初始赛事数量: ${initialCount}`);

    // 查找"加载更多"按钮
    const loadMoreBtn = await page.evaluate(() => {
      const allElements = Array.from(document.querySelectorAll("button, div, span, a"));
      for (const el of allElements) {
        if (el.textContent?.includes("加载更多")) {
          return {
            found: true,
            text: el.textContent.trim(),
            tagName: el.tagName,
            className: el.className,
            offsetParent: el.offsetParent !== null,
            visible: el.offsetWidth > 0 && el.offsetHeight > 0
          };
        }
      }
      return { found: false };
    });
    console.log('\n=== 加载更多按钮 ===');
    console.log(JSON.stringify(loadMoreBtn, null, 2));

    // 尝试滚动到底部
    console.log('\n=== 滚动到页面底部 ===');
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(1000);

    // 再次检查按钮
    const loadMoreBtnAfterScroll = await page.evaluate(() => {
      const allElements = Array.from(document.querySelectorAll("button, div, span, a"));
      for (const el of allElements) {
        if (el.textContent?.includes("加载更多")) {
          return {
            found: true,
            text: el.textContent.trim(),
            tagName: el.tagName,
            className: el.className,
            offsetParent: el.offsetParent !== null,
            visible: el.offsetWidth > 0 && el.offsetHeight > 0,
            rect: el.getBoundingClientRect()
          };
        }
      }
      return { found: false };
    });
    console.log('\n=== 滚动后加载更多按钮 ===');
    console.log(JSON.stringify(loadMoreBtnAfterScroll, null, 2));

    // 尝试点击
    console.log('\n=== 尝试点击加载更多按钮 ===');
    let clicked = await page.evaluate(() => {
      const allElements = Array.from(document.querySelectorAll("button, div, span, a"));
      for (const el of allElements) {
        if (el.textContent?.includes("加载更多") && el.offsetParent !== null) {
          el.click();
          return true;
        }
      }
      return false;
    });
    console.log(`点击结果: ${clicked}`);

    await page.waitForTimeout(2000);

    console.log('\n=== 点击后状态 ===');
    let countAfterClick = await page.evaluate(() => document.querySelectorAll("div.match").length);
    console.log(`点击后赛事数量: ${countAfterClick}`);

    // 继续尝试多次点击
    console.log('\n=== 继续加载更多... ===');
    for (let i = 0; i < 30; i++) {
      const prevCount = await page.evaluate(() => document.querySelectorAll("div.match").length);

      // 滚动到底部
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await page.waitForTimeout(500);

      const btnClicked = await page.evaluate(() => {
        const allElements = Array.from(document.querySelectorAll("button, div, span, a"));
        for (const el of allElements) {
          if (el.textContent?.includes("加载更多") && el.offsetParent !== null) {
            el.click();
            return true;
          }
        }
        return false;
      });

      await page.waitForTimeout(1500);

      const currentCount = await page.evaluate(() => document.querySelectorAll("div.match").length);

      console.log(`第${i + 1}次: 之前=${prevCount}, 之后=${currentCount}, 按钮点击=${btnClicked}`);

      if (currentCount === prevCount && !btnClicked) {
        console.log('没有更多数据可以加载');
        break;
      }
    }

    console.log('\n=== 最终状态 ===');
    const finalCount = await page.evaluate(() => document.querySelectorAll("div.match").length);
    console.log(`最终赛事数量: ${finalCount}`);

  } catch (error) {
    console.error('Error:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }
}

testPagination();