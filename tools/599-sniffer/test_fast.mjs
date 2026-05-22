import express from "express";
import { chromium } from "playwright";

const app = express();
const PORT = 3001;

let browser = null;

async function getBrowser() {
  if (!browser) {
    browser = await chromium.launch({ 
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox']
    });
  }
  return browser;
}

async function fetchMatchList() {
  const browser = await getBrowser();
  const context = await browser.newContext({
    userAgent:
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    viewport: { width: 1400, height: 900 },
  });

  const page = await context.newPage();
  
  try {
    await page.goto("https://www.599.com/live/", { 
      waitUntil: "networkidle", 
      timeout: 60000 
    });
    
    await page.waitForTimeout(3000);

    let prevMatchCount = 0;
    let loadAttempts = 0;
    const maxLoadAttempts = 10;
    
    while (loadAttempts < maxLoadAttempts) {
      const currentCount = await page.evaluate(() => {
        return document.querySelectorAll("div.match").length;
      });
      
      if (currentCount === prevMatchCount) break;
      prevMatchCount = currentCount;
      
      const loaded = await page.evaluate(() => {
        const allElements = Array.from(document.querySelectorAll("button, div, span"));
        const loadMoreBtn = allElements.find(el => el.textContent?.includes("加载更多"));
        if (loadMoreBtn && loadMoreBtn.offsetParent) {
          loadMoreBtn.click();
          return true;
        }
        return false;
      });
      
      if (!loaded) break;
      await page.waitForTimeout(2000);
      loadAttempts++;
    }

    await page.waitForTimeout(2000);

    const rawMatches = await page.evaluate(() => {
      const result = [];
      const matchIds = new Set();

      const matchDivs = Array.from(document.querySelectorAll("div.match"));
      
      for (const matchDiv of matchDivs) {
        try {
          const table = matchDiv.querySelector("table.football_item");
          if (!table) continue;
          
          const tds = table.querySelectorAll("td");
          if (tds.length < 12) continue;
          
          const leagueEl = tds[1];
          const league = leagueEl.textContent?.trim() || "";
          
          const timeEl = tds[2];
          const matchTime = timeEl.textContent?.trim() || "";
          
          const statusEl = tds[3];
          const status = statusEl.textContent?.trim() || "";
          
          const homeTeamEl = tds[4];
          const awayTeamEl = tds[6];
          const scoreEl = tds[8];
          const detailEl = tds[10];
          
          let homeTeam = "";
          let awayTeam = "";
          let homeScore = "";
          let awayScore = "";
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
          
          if (scoreEl) {
            const scoreText = scoreEl.textContent?.trim() || "";
            const scoreMatch = scoreText.match(/^(\d+)-(\d+)$/);
            if (scoreMatch) {
              homeScore = scoreMatch[1];
              awayScore = scoreMatch[2];
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
          
          if (!matchId || matchIds.has(matchId)) continue;
          matchIds.add(matchId);
          
          result.push({
            matchId,
            homeTeam: homeTeam || "未知主队",
            awayTeam: awayTeam || "未知客队",
            homeScore,
            awayScore,
            matchTime: matchTime || status,
            league
          });
        } catch (e) {
          continue;
        }
      }
      
      return result;
    });

    console.log(`Found ${rawMatches.length} matches`);
    
    await page.close();

    const batchSize = 5;
    const matches = [];
    const limit = Math.min(10, rawMatches.length);
    
    for (let i = 0; i < limit; i += batchSize) {
      const batch = rawMatches.slice(i, Math.min(i + batchSize, limit));
      
      const batchPromises = batch.map(async (rawMatch) => {
        const detailPage = await context.newPage();
        try {
          const url = `https://www.599.com/live/${rawMatch.matchId}/`;
          await detailPage.goto(url, { waitUntil: "domcontentloaded", timeout: 20000 });
          await detailPage.waitForTimeout(1500);
          
          const bifenId = await detailPage.evaluate(() => {
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
            ? `https://www.599.com/live/${rawMatch.matchId}/bifen-${bifenId}.html`
            : `https://www.599.com/live/${rawMatch.matchId}/`;
          
          console.log(`${rawMatch.homeTeam} vs ${rawMatch.awayTeam}: ${detailUrl}`);
          
          return {
            ...rawMatch,
            bifenId: bifenId || "",
            detailUrl
          };
        } catch (error) {
          console.error(`Error fetching bifenId for ${rawMatch.matchId}:`, error.message);
          return {
            ...rawMatch,
            bifenId: "",
            detailUrl: `https://www.599.com/live/${rawMatch.matchId}/`
          };
        } finally {
          await detailPage.close();
        }
      });
      
      const batchResults = await Promise.all(batchPromises);
      matches.push(...batchResults);
    }

    await context.close();
    return matches;
  } catch (error) {
    await context.close();
    throw error;
  }
}

app.get("/api/matches", async (req, res) => {
  try {
    const matches = await fetchMatchList();
    res.json({
      success: true,
      count: matches.length,
      data: matches,
      timestamp: Date.now()
    });
  } catch (error) {
    console.error("Error fetching match list:", error);
    res.status(500).json({
      success: false,
      error: error.message
    });
  }
});

app.listen(PORT, async () => {
  console.log(`Server running on http://localhost:${PORT}`);
  console.log(`Testing with first 10 matches...`);
});