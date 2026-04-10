package com.example.demo.controller;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.core.SoccerApiInplayTool;
import com.example.demo.core.SoccerApiInplayTool.MatchSummary;
import com.example.demo.core.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/inplay")
public class SoccerInplayController {

    private final SoccerApiInplayTool tool = new SoccerApiInplayTool();
    private final Map<String, String> zhTranslateCache = new ConcurrentHashMap<>();

    @GetMapping("/events")
    public Result listMatchesByLeague() {
        try {
            List<MatchSummary> matches = tool.fetchInplayMatchesInPlay(Integer.MAX_VALUE);
            Map<String, List<MatchSummary>> groupByLeague = new LinkedHashMap<>();
            for (MatchSummary m : matches) {
                String league = m.leagueName();
                groupByLeague.computeIfAbsent(league, k -> new ArrayList<>()).add(m);
            }

            List<Map<String, Object>> data = new ArrayList<>();
            for (Map.Entry<String, List<MatchSummary>> entry : groupByLeague.entrySet()) {
                String league = entry.getKey();
                List<MatchSummary> groupMatches = entry.getValue();
                String leagueZh = translateToZh(league);

                List<Map<String, Object>> events = new ArrayList<>();
                for (MatchSummary m : groupMatches) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("id", m.id());
                    String homeZh = translateToZh(m.homeName());
                    String awayZh = translateToZh(m.awayName());
                    ev.put("name", homeZh + " -vs- " + awayZh);
                    events.add(ev);
                }

                Map<String, Object> leagueBlock = new LinkedHashMap<>();
                leagueBlock.put("id", groupMatches.get(0).leagueId());
                leagueBlock.put("league", leagueZh);
                leagueBlock.put("events", events);
                data.add(leagueBlock);
            }

            return Result.success(data);
        } catch (Exception e) {
            log.error("赛事列表接口失败", e);
            return Result.failed(500, "获取赛事列表失败");
        }
    }

    @GetMapping("/translate-test")
    public Result translateTest(@RequestParam String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("src", text);
        data.put("zh", translateToZh(text));
        return Result.success(data);
    }

    private String translateToZh(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String src = text.trim();
        if (src.matches(".*[\\u4e00-\\u9fa5].*")) return src;
        String cached = zhTranslateCache.get(src);
        if (cached != null && !cached.isEmpty()) return cached;
        try {
            String q = URLEncoder.encode(src, StandardCharsets.UTF_8);
            String url = "https://api.mymemory.translated.net/get?q=" + q + "&langpair=en|zh-CN";
            String body = HttpUtil.get(url, 8000);
            var obj = JSONUtil.parseObj(body);
            String translated = obj.getJSONObject("responseData").getStr("translatedText");
            if (translated != null) translated = translated.trim();
            if (translated != null && !translated.isEmpty() && !translated.equalsIgnoreCase(src) && translated.matches(".*[\\u4e00-\\u9fa5].*")) {
                zhTranslateCache.put(src, translated);
                return translated;
            }
            var matches = obj.getJSONArray("matches");
            if (matches != null) {
                for (Object item : matches) {
                    if (!(item instanceof cn.hutool.json.JSONObject jo)) continue;
                    String target = jo.getStr("target");
                    String mTranslated = jo.getStr("translation");
                    if (mTranslated == null) continue;
                    mTranslated = mTranslated.trim();
                    if (mTranslated.isEmpty()) continue;
                    if (!"zh-CN".equalsIgnoreCase(target)) continue;
                    if (!mTranslated.matches(".*[\\u4e00-\\u9fa5].*")) continue;
                    zhTranslateCache.put(src, mTranslated);
                    return mTranslated;
                }
            }
            if (translated != null && !translated.trim().isEmpty()) {
                String out = translated.trim();
                zhTranslateCache.put(src, out);
                return out;
            }
        } catch (Exception e) {
            log.warn("翻译失败，回退原文: {}", src);
        }
        zhTranslateCache.put(src, src);
        return src;
    }

    public static void main(String[] args) {
        SoccerInplayController controller = new SoccerInplayController();
        String src = args != null && args.length > 0 ? args[0] : "Sportivo Barracas Reserves";
        String zh = controller.translateToZh(src);
        System.out.println("src=" + src);
        System.out.println("zh=" + zh);
    }
}

