package com.example.demo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SoccerApiNewTool {

    // 赛事列表展示所需的核心字段
    public record MatchSummary(
            String id,
            String leagueId,
            String leagueName,
            String status,
            int minute,
            String homeName,
            int homeScore,
            String awayName,
            int awayScore,
            String country,
            String matchTime
    ) {}

    public record StatLine(String key, String label, int home, int away) {}

    public record MatchEvent(
            int minute,
            String typeKey,
            String typeLabel,
            String player,
            String team,
            String description
    ) {}

    // 赛事详情（含状态描述与实时统计）
    public record MatchDetail(
            MatchSummary summary,
            List<MatchEvent> events,
            List<StatLine> stats
    ) {}

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    private final String apiUser = getenvOrDefault("SOCCER_API_USER", "sptsmtty");
    private final String apiSecret = getenvOrDefault("SOCCER_API_SECRET", "b5f7f055498beeb470fca780fc269359");
    private final String apiBaseUrl = getenvOrDefault("SOCCER_API_BASE_URL", "https://global.sportnanoapi.com/api/v5/football");

    private volatile long matchesCacheTsMs = 0;
    private volatile JsonNode matchesCacheData = null;



    // 获取今日赛事列表（根据联赛分组并且过滤掉已完场的赛事）
    public Map<String, List<MatchSummary>> fetchTodayMatchesByLeague() {
        List<MatchSummary> matches = fetchMatchList();
        Map<String, List<MatchSummary>> groupByLeague = new HashMap<>();
        
        for (MatchSummary match : matches) {
            // 过滤掉已完场的赛事
            if ("完场".equals(match.status())) {
                continue;
            }
            
            String league = match.leagueName();
            groupByLeague.computeIfAbsent(league, k -> new ArrayList<>()).add(match);
        }
        
        return groupByLeague;
    }

    // 根据id查看单场赛事趋势详情
    public MatchDetail fetchMatchDetailByMatchId(String matchId) {
        if (matchId == null || matchId.trim().isEmpty()) return null;
        
        // 先从赛事列表中查找
        List<MatchSummary> matches = fetchMatchList();
        MatchSummary summary = matches.stream()
                .filter(m -> m.id().equals(matchId))
                .findFirst()
                .orElse(null);
        
        if (summary == null) return null;
        
        // 获取单场比赛趋势详情
        JsonNode trendDetail = fetchMatchTrendDetail(matchId);
        
        // 获取赛事实时事件和统计数据
        List<MatchEvent> events = fetchMatchEvents(trendDetail);
        List<StatLine> stats = fetchMatchStats(trendDetail);
        
        return new MatchDetail(summary, events, stats);
    }

    // 获取赛事列表
    private List<MatchSummary> fetchMatchList() {
        long now = System.currentTimeMillis();
        // 缓存10分钟
        if (matchesCacheData != null && (now - matchesCacheTsMs) < 10 * 60 * 1000) {
            return parseMatchesFromCache(matchesCacheData);
        }
        
        try {
            String date = getTodayDate();
            String url = apiBaseUrl + "/match/schedule/diary?user=" + apiUser + "&secret=" + apiSecret + "&date=" + date;
            Request request = new Request.Builder().url(url).get().build();
            
            try (Response resp = httpClient.newCall(request).execute()) {
                if (resp.body() == null) throw new IOException("empty body");
                if (resp.code() != 200) throw new IOException("http status=" + resp.code());
                
                String text = resp.body().string();
                JsonNode json = objectMapper.readTree(text);
                
                if (json.has("code") && json.get("code").asInt() == 0) {
                    matchesCacheTsMs = now;
                    matchesCacheData = json;
                    return parseMatchesFromCache(json);
                }
            }
        } catch (Exception ignored) {
        }
        
        return List.of();
    }

    // 解析赛事列表
    private List<MatchSummary> parseMatchesFromCache(JsonNode json) {
        List<MatchSummary> matches = new ArrayList<>();
        
        if (!json.has("results")) return matches;
        JsonNode results = json.get("results");
        
        if (!results.has("match")) return matches;
        JsonNode matchNodes = results.get("match");
        
        if (!matchNodes.isArray()) return matches;
        
        for (JsonNode matchNode : matchNodes) {
            // 查找对应的赛事信息
            String competitionId = textTrim(matchNode.path("competition_id"));
            String leagueName = "未知联赛";
            String leagueId = "";
            
            if (results.has("competition")) {
                JsonNode competitionNodes = results.get("competition");
                if (competitionNodes.isArray()) {
                    for (JsonNode compNode : competitionNodes) {
                        if (competitionId.equals(textTrim(compNode.path("id")))) {
                            leagueName = textTrim(compNode.path("name"));
                            leagueId = competitionId;
                            break;
                        }
                    }
                }
            }
            
            // 查找主队信息
            String homeTeamId = textTrim(matchNode.path("home_team_id"));
            String homeName = "未知球队";
            
            if (results.has("team")) {
                JsonNode teamNodes = results.get("team");
                if (teamNodes.isArray()) {
                    for (JsonNode teamNode : teamNodes) {
                        if (homeTeamId.equals(textTrim(teamNode.path("id")))) {
                            homeName = textTrim(teamNode.path("name"));
                            break;
                        }
                    }
                }
            }
            
            // 查找客队信息
            String awayTeamId = textTrim(matchNode.path("away_team_id"));
            String awayName = "未知球队";
            
            if (results.has("team")) {
                JsonNode teamNodes = results.get("team");
                if (teamNodes.isArray()) {
                    for (JsonNode teamNode : teamNodes) {
                        if (awayTeamId.equals(textTrim(teamNode.path("id")))) {
                            awayName = textTrim(teamNode.path("name"));
                            break;
                        }
                    }
                }
            }
            
            // 解析比分
            int homeScore = 0;
            int awayScore = 0;
            
            if (matchNode.has("home_scores")) {
                JsonNode homeScores = matchNode.get("home_scores");
                if (homeScores.isArray() && homeScores.size() > 0) {
                    homeScore = homeScores.get(0).asInt();
                }
            }
            
            if (matchNode.has("away_scores")) {
                JsonNode awayScores = matchNode.get("away_scores");
                if (awayScores.isArray() && awayScores.size() > 0) {
                    awayScore = awayScores.get(0).asInt();
                }
            }
            
            // 解析比赛状态
            String status = "未开始";
            if (matchNode.has("status_id")) {
                int statusId = matchNode.get("status_id").asInt();
                status = getStatusLabel(statusId);
            }
            
            // 解析比赛时间
            String matchTime = "";
            if (matchNode.has("match_time")) {
                long timestamp = matchNode.get("match_time").asLong();
                matchTime = formatMatchTime(timestamp);
            }
            
            String id = textTrim(matchNode.path("id"));
            if (id.isEmpty()) continue;
            
            MatchSummary summary = new MatchSummary(
                    id,
                    leagueId.isEmpty() ? leagueName : leagueId,
                    leagueName,
                    status,
                    0, // 新API需要单独计算
                    homeName,
                    homeScore,
                    awayName,
                    awayScore,
                    "地区：未知",
                    matchTime
            );
            
            matches.add(summary);
        }
        
        return matches;
    }

    // 获取单场比赛趋势详情
    private JsonNode fetchMatchTrendDetail(String matchId) {
        try {
            String url = apiBaseUrl + "/match/trend/detail?user=" + apiUser + "&secret=" + apiSecret + "&id=" + matchId;
            Request request = new Request.Builder().url(url).get().build();
            
            try (Response resp = httpClient.newCall(request).execute()) {
                if (resp.body() == null) throw new IOException("empty body");
                if (resp.code() != 200) throw new IOException("http status=" + resp.code());
                
                String text = resp.body().string();
                JsonNode json = objectMapper.readTree(text);
                
                if (json.has("code") && json.get("code").asInt() == 0 && json.has("results")) {
                    return json.get("results");
                }
            }
        } catch (Exception ignored) {
        }
        
        return null;
    }

    // 获取赛事实时事件
    private List<MatchEvent> fetchMatchEvents(JsonNode trendDetail) {
        List<MatchEvent> events = new ArrayList<>();
        
        if (trendDetail == null) return events;
        
        // 处理比赛事件
        if (trendDetail.has("incidents")) {
            JsonNode incidents = trendDetail.get("incidents");
            if (incidents.isArray()) {
                for (JsonNode incident : incidents) {
                    String typeLabel = "比赛事件";
                    String typeKey = "";
                    
                    if (incident.has("type")) {
                        int type = incident.get("type").asInt();
                        switch (type) {
                            case 1: typeLabel = "进球"; typeKey = "goal"; break;
                            case 2: typeLabel = "角球"; break;
                            case 3: typeLabel = "黄牌"; typeKey = "card"; break;
                            case 4: typeLabel = "红牌"; typeKey = "card"; break;
                            case 5: typeLabel = "越位"; break;
                            case 6: typeLabel = "任意球"; break;
                            case 7: typeLabel = "球门球"; break;
                            case 8: typeLabel = "点球"; break;
                            case 9: typeLabel = "换人"; typeKey = "substitution"; break;
                            case 10: typeLabel = "比赛开始"; break;
                            case 11: typeLabel = "中场"; break;
                            case 12: typeLabel = "结束"; break;
                            case 13: typeLabel = "半场比分"; break;
                            case 15: typeLabel = "两黄变红"; typeKey = "card"; break;
                            case 16: typeLabel = "点球未进"; break;
                            case 17: typeLabel = "乌龙球"; typeKey = "goal"; break;
                            case 18: typeLabel = "助攻"; break;
                            case 19: typeLabel = "伤停补时"; break;
                            case 21: typeLabel = "射正"; break;
                            case 22: typeLabel = "射偏"; break;
                            case 23: typeLabel = "进攻"; break;
                            case 24: typeLabel = "危险进攻"; break;
                            case 25: typeLabel = "控球率"; break;
                            case 26: typeLabel = "加时赛结束"; break;
                            case 27: typeLabel = "点球大战结束"; break;
                            case 28: typeLabel = "VAR(视频助理裁判)"; break;
                            case 29: typeLabel = "点球(点球大战)"; break;
                            case 30: typeLabel = "点球未进(点球大战)"; break;
                            case 37: typeLabel = "射门被阻挡"; break;
                            default: typeLabel = "比赛事件"; break;
                        }
                    }
                    
                    String team = "";
                    if (incident.has("position")) {
                        int position = incident.get("position").asInt();
                        if (position == 1) team = "主队";
                        else if (position == 2) team = "客队";
                    }
                    
                    int minute = 0;
                    if (incident.has("time")) {
                        String timeStr = textTrim(incident.get("time"));
                        minute = parseMinute(timeStr);
                    }
                    
                    String player = "";
                    if (incident.has("player_name")) {
                        player = textTrim(incident.get("player_name"));
                    }
                    
                    String description = player.isEmpty() ? typeLabel : player;
                    
                    events.add(new MatchEvent(minute, typeKey, typeLabel, player, team, description));
                }
            }
        }
        
        // 按时间排序
        events.sort((a, b) -> a.minute() - b.minute());
        
        return events;
    }

    // 获取赛事统计数据
    private List<StatLine> fetchMatchStats(JsonNode trendDetail) {
        List<StatLine> stats = new ArrayList<>();
        
        if (trendDetail == null) return stats;
        
        // 处理统计数据
        if (trendDetail.has("stats")) {
            JsonNode statsNode = trendDetail.get("stats");
            if (statsNode.isArray()) {
                for (JsonNode stat : statsNode) {
                    String label = "统计数据";
                    
                    if (stat.has("type")) {
                        int type = stat.get("type").asInt();
                        switch (type) {
                            case 1: label = "进球"; break;
                            case 2: label = "角球"; break;
                            case 3: label = "黄牌"; break;
                            case 4: label = "红牌"; break;
                            case 5: label = "越位"; break;
                            case 6: label = "任意球"; break;
                            case 7: label = "球门球"; break;
                            case 8: label = "点球"; break;
                            case 9: label = "换人"; break;
                            case 10: label = "比赛开始"; break;
                            case 11: label = "中场"; break;
                            case 12: label = "结束"; break;
                            case 13: label = "半场比分"; break;
                            case 15: label = "两黄变红"; break;
                            case 16: label = "点球未进"; break;
                            case 17: label = "乌龙球"; break;
                            case 18: label = "助攻"; break;
                            case 19: label = "伤停补时"; break;
                            case 21: label = "射正"; break;
                            case 22: label = "射偏"; break;
                            case 23: label = "进攻"; break;
                            case 24: label = "危险进攻"; break;
                            case 25: label = "控球率"; break;
                            case 26: label = "加时赛结束"; break;
                            case 27: label = "点球大战结束"; break;
                            case 28: label = "VAR(视频助理裁判)"; break;
                            case 29: label = "点球(点球大战)"; break;
                            case 30: label = "点球未进(点球大战)"; break;
                            case 37: label = "射门被阻挡"; break;
                            default: label = "统计数据"; break;
                        }
                    }
                    
                    int home = 0;
                    if (stat.has("home")) {
                        home = stat.get("home").asInt();
                    }
                    
                    int away = 0;
                    if (stat.has("away")) {
                        away = stat.get("away").asInt();
                    }
                    
                    String key = "";
                    if (stat.has("type")) {
                        key = String.valueOf(stat.get("type").asInt());
                    }
                    
                    stats.add(new StatLine(key, label, home, away));
                }
            }
        }
        
        return stats;
    }

    // 获取今日日期（YYYYMMDD格式）
    private String getTodayDate() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    // 格式化比赛时间
    private String formatMatchTime(long timestamp) {
        java.util.Date date = new java.util.Date(timestamp * 1000);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm");
        return sdf.format(date);
    }

    // 获取状态标签
    private String getStatusLabel(int statusId) {
        switch (statusId) {
            case 0: return "比赛异常";
            case 1: return "未开赛";
            case 2: return "上半场";
            case 3: return "中场";
            case 4: return "下半场";
            case 5: return "加时赛";
            case 6: return "加时赛(弃用)";
            case 7: return "点球决战";
            case 8: return "完场";
            case 9: return "推迟";
            case 10: return "中断";
            case 11: return "腰斩";
            case 12: return "取消";
            case 13: return "待定";
            default: return "未知状态";
        }
    }

    // 工具方法：获取环境变量或默认值
    private String getenvOrDefault(String key, String def) {
        try {
            String v = System.getenv(key);
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Exception ignored) {
            return def;
        }
    }

    // 工具方法：处理JsonNode文本
    private String textTrim(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        String s = node.isTextual() ? node.asText() : node.asText();
        if (s == null) return "";
        return s.trim();
    }
    
    // 解析时间字符串，处理"45+7"这样的格式
    private int parseMinute(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        
        try {
            // 处理"45+7"这样的格式，只取前面的数字
            if (timeStr.contains("+")) {
                String[] parts = timeStr.split("\\+");
                if (parts.length > 0) {
                    return Integer.parseInt(parts[0]);
                }
            }
            // 直接解析数字
            return Integer.parseInt(timeStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
