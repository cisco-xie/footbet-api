import { chromium } from "playwright";

async function testPaginationFix() {
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

    // 滚动到页面底部多次
    console.log('\n=== 开始滚动和加载 ===');
    for (let i = 0; i < 30; i++) {
      const prevCount = await page.evaluate(() => document.querySelectorAll("div.match").length);

      // 滚动到页面底部
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await page.waitForTimeout(500);

      // 使用Playwright的locator来查找并点击按钮
      try {
        const loadMoreBtn = page.locator('text=加载更多').first();
        const isVisible = await loadMoreBtn.isVisible().catch(() => false);

        if (isVisible) {
          await loadMoreBtn.click({ timeout: 2000 });
          console.log(`第${i + 1}次: 点击了加载更多按钮`);
        } else {
          console.log(`第${i + 1}次: 按钮不可见`);
          break;
        }
      } catch (e) {
        console.log(`第${i + 1}次: 查找按钮失败 - ${e.message}`);
      }

      await page.waitForTimeout(2000);

      const currentCount = await page.evaluate(() => document.querySelectorAll("div.match").length);
      console.log(`第${i + 1}次: 之前=${prevCount}, 之后=${currentCount}`);

      if (currentCount === prevCount) {
        // 再等一会儿看看
        await page.waitForTimeout(3000);
        const newCount = await page.evaluate(() => document.querySelectorAll("div.match").length);
        console.log(`等待后: ${newCount}`);
        if (newCount === prevCount) {
          console.log('数据没有变化，停止加载');
          break;
        }
      }
    }

    console.log('\n=== 最终状态 ===');
    const finalCount = await page.evaluate(() => document.querySelectorAll("div.match").length);
    console.log(`最终赛事数量: ${finalCount}`);

    // 获取所有联赛统计
    const leagueStats = await page.evaluate(() => {
      const matches = document.querySelectorAll("div.match");
      const leagues = {};
      for (const match of matches) {
        const table = match.querySelector("table.football_item");
        if (table) {
          const tds = table.querySelectorAll("td");
          if (tds.length >= 2) {
            const league = tds[1].textContent?.trim() || "未知";
            leagues[league] = (leagues[league] || 0) + 1;
          }
        }
      }
      return leagues;
    });

    console.log('\n=== 联赛统计 ===');
    for (const [league, count] of Object.entries(leagueStats)) {
      console.log(`${league}: ${count}场`);
    }

  } catch (error) {
    console.error('Error:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }
}

testPaginationFix();