import express from "express";
import { chromium } from "playwright";

const app = express();
const PORT = process.env.PORT || 3002;

async function getBrowser() {
  return await chromium.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
  });
}

// 快速获取赛事列表（不含bifenId，用于搜索）
async function fetchMatchListFast() {
  const browser = await getBrowser();
  const context = await browser.newContext({
    userAgent:
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    viewport: { width: 1400, height: 900 },
  });

  const page = await context.newPage();

  try {
    await page.goto("https://www.599.com/live/", {
      waitUntil: "domcontentloaded",
      timeout: 30000
    });

    await page.waitForTimeout(3000);

    let prevMatchCount = 0;
    let loadAttempts = 0;
    const maxLoadAttempts = 30;

    console.log("开始加载更多赛事...");

    while (loadAttempts < maxLoadAttempts) {
      const currentCount = await page.evaluate(() => {
        return document.querySelectorAll("div.match").length;
      });

      console.log(`当前赛事数量: ${currentCount}, 加载尝试: ${loadAttempts + 1}`);

      if (currentCount === prevMatchCount) break;
      prevMatchCount = currentCount;

      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await page.waitForTimeout(500);

      try {
        const loadMoreBtn = page.locator('text=加载更多').first();
        const isVisible = await loadMoreBtn.isVisible().catch(() => false);

        if (isVisible) {
          await loadMoreBtn.click({ timeout: 2000 });
        } else {
          console.log("加载更多按钮已不可见，停止加载");
          break;
        }
      } catch (e) {
        console.log(`查找按钮失败: ${e.message}`);
        break;
      }

      await page.waitForTimeout(2000);
      loadAttempts++;
    }

    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(1000);

    const matches = await page.evaluate(() => {
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
            league,
            bifenId: "",
            detailUrl: `https://www.599.com/live/${matchId}/`
          });
        } catch (e) {
          continue;
        }
      }

      return result;
    });

    console.log(`快速获取到 ${matches.length} 场赛事`);

    await page.close();
    await context.close();
    await browser.close();

    return matches;
  } catch (error) {
    await context.close();
    await browser.close();
    throw error;
  }
}

// 获取单个赛事的bifenId
async function fetchBifenId(matchId) {
  const browser = await getBrowser();
  const context = await browser.newContext({
    userAgent:
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    viewport: { width: 1400, height: 900 },
  });

  const page = await context.newPage();

  try {
    const url = `https://www.599.com/live/${matchId}/`;
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
      ? `https://www.599.com/live/${matchId}/bifen-${bifenId}.html`
      : `https://www.599.com/live/${matchId}/`;

    return { bifenId: bifenId || "", detailUrl };
  } catch (error) {
    console.error(`Error fetching bifenId for ${matchId}:`, error.message);
    return { bifenId: "", detailUrl: `https://www.599.com/live/${matchId}/` };
  } finally {
    await page.close();
    await context.close();
    await browser.close();
  }
}

// 获取完整赛事列表（包含所有bifenId）
async function fetchMatchList() {
  const rawMatches = await fetchMatchListFast();
  const totalMatches = rawMatches.length;

  const detailBrowser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
  });

  const batchSize = 4;
  const matches = [];

  for (let i = 0; i < rawMatches.length; i += batchSize) {
    const batch = rawMatches.slice(i, i + batchSize);

    const detailContext = await detailBrowser.newContext({
      userAgent:
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
      viewport: { width: 1400, height: 900 },
    });

    const batchPromises = batch.map(async (rawMatch) => {
      const detailPage = await detailContext.newPage();
      try {
        const url = `https://www.599.com/live/${rawMatch.matchId}/`;
        await detailPage.goto(url, { waitUntil: "domcontentloaded", timeout: 20000 });
        await detailPage.waitForTimeout(1000);

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

        console.log(`Fetched ${rawMatch.matchId}: bifenId=${bifenId || 'N/A'}`);

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
    console.log(`Processed ${matches.length}/${totalMatches} matches`);

    await detailContext.close();
    await new Promise(r => setTimeout(r, 500));
  }

  await detailBrowser.close();
  return matches;
}

// 缓存赛事列表
let cachedMatches = [];
let cacheTimestamp = 0;
const CACHE_DURATION = 30 * 60 * 1000; // 30分钟缓存

// Jaccard相似度计算
function jaccardSimilarity(str1, str2) {
  const set1 = new Set(str1.split(''));
  const set2 = new Set(str2.split(''));
  let intersection = 0;
  for (const char of set1) {
    if (set2.has(char)) {
      intersection++;
    }
  }
  const union = set1.size + set2.size - intersection;
  return union === 0 ? 0 : intersection / union;
}

// 综合相似度计算（考虑完整匹配和分词匹配）
function calculateMatchScore(inputHome, inputAway, matchHome, matchAway) {
  // 标准化球队名称（去除常见后缀）
  const normalize = (name) => {
    return name.toLowerCase()
      .replace(/\s*(fc|女足|男足|俱乐部|队|FC)\s*/g, '')
      .replace(/\s+/g, '');
  };

  const normInputHome = normalize(inputHome);
  const normInputAway = normalize(inputAway);
  const normMatchHome = normalize(matchHome);
  const normMatchAway = normalize(matchAway);

  // 计算两种匹配方式的得分
  const score1 = jaccardSimilarity(normInputHome, normMatchHome) * 0.5 + 
                 jaccardSimilarity(normInputAway, normMatchAway) * 0.5;
  
  const score2 = jaccardSimilarity(normInputHome, normMatchAway) * 0.5 + 
                 jaccardSimilarity(normInputAway, normMatchHome) * 0.5;

  return Math.max(score1, score2);
}

app.get("/api/matches", async (req, res) => {
  try {
    const matches = await fetchMatchList();
    // 更新缓存
    cachedMatches = matches;
    cacheTimestamp = Date.now();
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

// 根据球队名搜索赛事（必须放在 :matchId 路由之前）
app.get("/api/match/search", async (req, res) => {
  try {
    const { teams } = req.query;
    
    if (!teams) {
      return res.status(400).json({
        success: false,
        error: "缺少teams参数"
      });
    }

    // 解析球队名（格式：主队 -vs- 客队）
    const parts = teams.split(/-vs-/);
    if (parts.length !== 2) {
      return res.status(400).json({
        success: false,
        error: "teams参数格式错误，应为：主队 -vs- 客队"
      });
    }

    const inputHome = parts[0].trim();
    const inputAway = parts[1].trim();

    // 获取赛事列表（优先使用缓存，使用快速方法，不含bifenId）
    const now = Date.now();
    if (now - cacheTimestamp > CACHE_DURATION || cachedMatches.length === 0) {
      console.log("缓存过期，快速获取赛事列表（不含bifenId）...");
      cachedMatches = await fetchMatchListFast();
      cacheTimestamp = now;
    }

    // 计算每个赛事的匹配度
    let bestMatch = null;
    let highestScore = 0;
    
    for (const match of cachedMatches) {
      const score = calculateMatchScore(inputHome, inputAway, match.homeTeam, match.awayTeam);
      if (score > highestScore) {
        highestScore = score;
        bestMatch = {
          ...match,
          matchScore: score
        };
      }
    }

    // 如果找到了匹配的赛事，获取它的bifenId
    if (bestMatch) {
      console.log(`找到最佳匹配: ${bestMatch.homeTeam} vs ${bestMatch.awayTeam}, 匹配度: ${highestScore}`);
      console.log(`正在获取bifenId...`);
      const bifenInfo = await fetchBifenId(bestMatch.matchId);
      bestMatch.bifenId = bifenInfo.bifenId;
      bestMatch.detailUrl = bifenInfo.detailUrl;
      console.log(`获取完成: bifenId=${bifenInfo.bifenId}`);
    }

    res.json({
      success: true,
      data: bestMatch,
      searchQuery: {
        homeTeam: inputHome,
        awayTeam: inputAway
      },
      timestamp: Date.now()
    });

  } catch (error) {
    console.error("Error searching match:", error);
    res.status(500).json({
      success: false,
      error: error.message
    });
  }
});

app.get("/api/match/:matchId", async (req, res) => {
  try {
    const { matchId } = req.params;

    const browser = await getBrowser();
    const context = await browser.newContext({
      userAgent:
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
      viewport: { width: 1400, height: 900 },
    });

    const page = await context.newPage();

    try {
      const url = `https://www.599.com/live/${matchId}/`;
      await page.goto(url, { waitUntil: "domcontentloaded", timeout: 15000 });
      await page.waitForTimeout(500);

      const matchData = await page.evaluate(() => {
        const result = {
          matchId: "",
          homeTeam: "",
          awayTeam: "",
          homeScore: "",
          awayScore: "",
          matchTime: "",
          league: "",
          bifenId: "",
          detailUrl: ""
        };

        const links = document.querySelectorAll("a");
        for (const link of links) {
          const href = link.getAttribute("href");
          if (href && href.includes("bifen-")) {
            const match = href.match(/bifen-(\d+)\.html/);
            if (match) {
              result.bifenId = match[1];
              break;
            }
          }
        }

        return result;
      });

      matchData.matchId = matchId;
      matchData.detailUrl = matchData.bifenId
        ? `https://www.599.com/live/${matchId}/bifen-${matchData.bifenId}.html`
        : `https://www.599.com/live/${matchId}/`;

      await page.close();
      await context.close();
      await browser.close();

      res.json({
        success: true,
        data: matchData
      });

    } catch (error) {
      await page.close();
      await context.close();
      await browser.close();
      throw error;
    }

  } catch (error) {
    console.error("Error fetching match detail:", error);
    res.status(500).json({
      success: false,
      error: error.message
    });
  }
});

app.get("/api/health", (req, res) => {
  res.json({ status: "ok", timestamp: Date.now() });
});

app.listen(PORT, () => {
  console.log(`Server running on http://localhost:${PORT}`);
  console.log(`API endpoints:`);
  console.log(`  GET /api/matches - 获取赛事列表`);
  console.log(`  GET /api/match/search?teams=主队-vs-客队 - 根据球队名搜索赛事`);
  console.log(`  GET /api/match/:matchId - 获取单个赛事详情`);
  console.log(`  GET /api/health - 健康检查`);
});