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
    await page.goto("https://www.599.com/live/", { waitUntil: "domcontentloaded", timeout: 30000 });
    await page.waitForTimeout(3000);
    
    const matches = await page.evaluate(() => {
      const result = [];
      const matchDivs = document.querySelectorAll("div.match");
      
      for (const matchDiv of matchDivs) {
        const table = matchDiv.querySelector("table.football_item");
        if (!table) continue;
        
        const tds = table.querySelectorAll("td");
        if (tds.length < 12) continue;
        
        const leagueEl = tds[1];
        const league = leagueEl.textContent?.trim() || "";
        
        const homeTeamEl = tds[4];
        const awayTeamEl = tds[6];
        const detailEl = tds[10];
        
        let homeTeam = "";
        let awayTeam = "";
        let matchId = "";
        
        if (homeTeamEl) {
          const teamLink = homeTeamEl.querySelector("a");
          if (teamLink) {
            let text = teamLink.textContent?.trim() || "";
            text = text.replace(/^\[\d+\]\s*/, "").trim();
            homeTeam = text;
          }
        }
        
        if (awayTeamEl) {
          const teamLink = awayTeamEl.querySelector("a");
          if (teamLink) {
            let text = teamLink.textContent?.trim() || "";
            text = text.replace(/\s*\[\d+\]$/, "").trim();
            awayTeam = text;
          }
        }
        
        if (detailEl) {
          const detailLink = detailEl.querySelector("a");
          if (detailLink) {
            const href = detailLink.getAttribute("href");
            if (href) {
              const match = href.match(/\/live\/(\d+_\d+)\/?$/);
              if (match) {
                matchId = match[1];
              }
            }
          }
        }
        
        result.push({
          matchId,
          homeTeam,
          awayTeam,
          league
        });
      }
      
      return result;
    });
    
    console.log(`Total matches found: ${matches.length}`);
    console.log('\n=== 中超赛事 ===');
    const chineseMatches = matches.filter(m => m.league === '中超');
    for (const match of chineseMatches) {
      console.log(`${match.homeTeam} vs ${match.awayTeam} - ${match.matchId}`);
    }
    
    console.log('\n=== 包含深圳或大连的赛事 ===');
    const shenzhenDalianMatches = matches.filter(m => 
      m.homeTeam.includes('深圳') || m.homeTeam.includes('大连') ||
      m.awayTeam.includes('深圳') || m.awayTeam.includes('大连')
    );
    for (const match of shenzhenDalianMatches) {
      console.log(`${match.homeTeam} vs ${match.awayTeam} - ${match.league} - ${match.matchId}`);
    }
    
    console.log('\n=== 所有联赛统计 ===');
    const leagueCount = {};
    for (const match of matches) {
      leagueCount[match.league] = (leagueCount[match.league] || 0) + 1;
    }
    for (const [league, count] of Object.entries(leagueCount)) {
      console.log(`${league}: ${count}`);
    }
    
  } catch (error) {
    console.error('Error:', error.message);
  } finally {
    await context.close();
    await browser.close();
  }
}

test();