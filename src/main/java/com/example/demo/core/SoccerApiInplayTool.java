package com.example.demo.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class SoccerApiInplayTool {

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
            String country
    ) {}

    public record StatLine(String key, String label, int home, int away) {}

    // 赛事详情（含状态描述与实时统计）
    public record MatchDetail(
            MatchSummary summary,
            String stateCode,
            String stateName,
            String eventTime,
            String description,
            List<StatLine> stats
    ) {}

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final Pattern TEAM_NORMALIZE_PATTERN = Pattern.compile("[^a-z0-9\\u4e00-\\u9fa5]");

    private static final Map<String, String> STATE_MAP = buildStateMap();

    private static Map<String, String> buildStateMap() {
        Map<String, String> m = new HashMap<>();
        m.put("11000", "主队危险进攻");
        m.put("21000", "客队危险进攻");
        m.put("11001", "主队进攻");
        m.put("21001", "客队进攻");
        m.put("11002", "主队控球");
        m.put("21002", "客队控球");
        m.put("11004", "主队角球");
        m.put("21004", "客队角球");
        m.put("11007", "主队门球");
        m.put("21007", "客队门球");
        m.put("11008", "主队获得点球");
        m.put("21008", "客队获得点球");
        m.put("11009", "主队直接任意球");
        m.put("21009", "客队直接任意球");
        m.put("11010", "主队间接任意球");
        m.put("21010", "客队间接任意球");
        m.put("11024", "主队界外球");
        m.put("21024", "客队界外球");
        m.put("10008", "主队点球得分");
        m.put("20008", "客队点球得分");
        m.put("10009", "主队点球失误");
        m.put("20009", "客队点球失误");
        m.put("11003", "主队进球");
        m.put("21003", "客队进球");
        m.put("11005", "主队黄牌");
        m.put("21005", "客队黄牌");
        m.put("11006", "主队红牌");
        m.put("21006", "客队红牌");
        m.put("11011", "主队射正");
        m.put("21011", "客队射正");
        m.put("11012", "主队射偏");
        m.put("21012", "客队射偏");
        m.put("11013", "主队换人");
        m.put("21013", "客队换人");
        m.put("1014", "开球");
        m.put("1015", "半场结束");
        m.put("1016", "下半场开始");
        m.put("1017", "全场结束");
        m.put("1018", "加时赛开球");
        m.put("1019", "加时赛半场");
        m.put("1020", "加时赛下半场");
        m.put("1021", "加时赛结束");
        m.put("1022", "点球大战");
        m.put("1233", "比赛信息");
        m.put("1023", "点球失误");
        m.put("11023", "主队点球失误");
        m.put("21023", "客队点球失误");
        m.put("1025", "伤病");
        m.put("11025", "主队伤病");
        m.put("21025", "客队伤病");
        m.put("1237", "区域界外球");
        m.put("11237", "主队区域界外球");
        m.put("21237", "客队区域界外球");
        m.put("1234", "越位");
        m.put("11234", "主队越位");
        m.put("21234", "客队越位");
        m.put("1238", "换人");
        m.put("11238", "主队换人");
        m.put("21238", "客队换人");
        m.put("1332", "VAR审核进球");
        m.put("11332", "主队VAR审核进球");
        m.put("21332", "客队VAR审核进球");
        m.put("1331", "VAR审核红牌");
        m.put("11331", "主队VAR审核红牌");
        m.put("21331", "客队VAR审核红牌");
        m.put("1333", "VAR审核点球");
        m.put("11333", "主队VAR审核点球");
        m.put("21333", "客队VAR审核点球");
        m.put("1330", "VAR进行中");
        m.put("11330", "主队VAR进行中");
        m.put("21330", "客队VAR进行中");
        m.put("11901", "主队角球（上）");
        m.put("11902", "主队角球（下）");
        m.put("21901", "客队角球（上）");
        m.put("21902", "客队角球（下）");
        m.put("11931", "主队任意球（位置1）");
        m.put("11933", "主队任意球（位置2）");
        m.put("11935", "主队任意球（位置3）");
        m.put("11932", "主队任意球（位置4）");
        m.put("11934", "主队任意球（位置5）");
        m.put("11936", "主队任意球（位置6）");
        m.put("21931", "客队任意球（位置7）");
        m.put("21933", "客队任意球（位置8）");
        m.put("21935", "客队任意球（位置9）");
        m.put("21932", "客队任意球（位置10）");
        m.put("21934", "客队任意球（位置11）");
        m.put("21936", "客队任意球（位置12）");
        m.put("11301", "主队进攻");
        m.put("21301", "客队进攻");
        m.put("11300", "主队危险进攻");
        m.put("21300", "客队危险进攻");
        m.put("11302", "主队控球");
        m.put("21302", "客队控球");
        m.put("1026", "伤停补时");
        m.put("11026", "主队伤停补时");
        m.put("21026", "客队伤停补时");
        m.put("1004", "角球");
        m.put("1005", "黄牌");
        m.put("1009", "任意球");
        m.put("1003", "进球");
        m.put("1000", "危险局面");
        m.put("21016", "客队下半场开始");
        m.put("11016", "主队下半场开始");
        m.put("21014", "客队开球");
        m.put("11014", "主队开球");
        m.put("21022", "客队点球大战");
        m.put("11022", "主队点球大战");
        return m;
    }

    private final String inplaySoccerUrl = getenvOrDefault("GOALSERVE_INPLAY_SOCCER_URL", "http://inplay.goalserve.com/inplay-soccer.gz");
    private final String snapshotPath = getenvOrDefault("GOALSERVE_INPLAY_SNAPSHOT_PATH", "inplay-soccer-last.json");

    private volatile long inplayCacheTsMs = 0;
    private volatile JsonNode inplayCacheData = null;

    // 获取正在进行的赛事列表（与原 Node 脚本筛选逻辑一致）
    public List<MatchSummary> fetchInplayMatchesInPlay(int limit) {
        JsonNode feed = fetchInplayFeedData();
        if (feed == null) return List.of();

        JsonNode eventsNode = feed.get("events");
        if (eventsNode == null || eventsNode.isNull()) return List.of();

        List<MatchSummary> result = new ArrayList<>();

        for (JsonNode evt : iterateEvents(eventsNode)) {
            if (!isInplayInJsLogic(evt)) continue;

            MatchSummary summary = mapInplayEventToMatchSummary(evt);
            if (summary == null) continue;

            result.add(summary);
            if (result.size() >= limit) break;
        }

        return result;
    }

    // 按球队名称模糊匹配赛事（主队/客队任一命中）
    public List<MatchSummary> queryMatchesByTeamName(String teamName, int limit) {
        if (teamName == null || teamName.trim().isEmpty()) return List.of();
        String n = normalizeName(teamName);
        if (n.isEmpty()) return List.of();

        int scanLimit = Math.max(limit, 200);
        List<MatchSummary> inplay = fetchInplayMatchesInPlay(scanLimit);

        List<MatchSummary> out = new ArrayList<>();
        for (MatchSummary m : inplay) {
            String h = normalizeName(m.homeName());
            String a = normalizeName(m.awayName());
            boolean hit = isSimilarName(h, n) || isSimilarName(a, n);
            if (!hit) continue;
            out.add(m);
            if (out.size() >= limit) break;
        }
        return out;
    }

    // 根据比赛ID获取详情，并补充状态说明与stats
    public MatchDetail fetchMatchDetailByMatchId(String matchId) {
        if (matchId == null || matchId.trim().isEmpty()) return null;

        JsonNode evt = fetchInplayEventByMatchId(matchId.trim());
        if (evt == null) return null;

        MatchSummary summary = mapInplayEventToMatchSummary(evt);
        if (summary == null) return null;

        String stateCode = firstText(evt.path("info").path("state"), evt.path("state"));
        String stateName = translateStateName(stateCode, "");

        String stateSeconds = textTrim(evt.path("info").path("seconds"));
        String fallbackMinute = textTrim(evt.path("info").path("minute").isMissingNode() ? evt.path("minute") : evt.path("info").path("minute"));
        String eventTime = !stateSeconds.isEmpty() ? stateSeconds : (!fallbackMinute.isEmpty() ? fallbackMinute : "");

        String ballPos = textTrim(evt.path("info").path("ball_pos"));
        String description = ballPos.isEmpty() ? stateName : (stateName + " | 球场位置：" + ballPos);

        List<StatLine> stats = parseInplayStatsFromEvent(evt);

        return new MatchDetail(summary, stateCode, stateName, eventTime, description, stats);
    }

    private JsonNode fetchInplayEventByMatchId(String matchId) {
        JsonNode feed = fetchInplayFeedData();
        if (feed == null) return null;
        JsonNode eventsNode = feed.get("events");
        if (eventsNode == null || eventsNode.isNull()) return null;

        String idStr = matchId;
        for (JsonNode evt : iterateEvents(eventsNode)) {
            String[] candidates = new String[]{
                    textTrim(evt.path("info").path("id")),
                    textTrim(evt.path("info").path("mid")),
                    textTrim(evt.path("id")),
                    textTrim(evt.path("mid")),
                    textTrim(evt.path("fi")),
                    textTrim(evt.path("match_id")),
                    textTrim(evt.path("matchId"))
            };

            for (String c : candidates) {
                if (c == null || c.isEmpty()) continue;
                if (idStr.equals(c)) return evt;
            }
        }
        return null;
    }

    // 优先拉取远端 inplay 数据，失败时回退本地快照
    private JsonNode fetchInplayFeedData() {
        long now = System.currentTimeMillis();
        if (inplayCacheData != null && (now - inplayCacheTsMs) < 2000) return inplayCacheData;

        try {
            Request request = new Request.Builder().url(inplaySoccerUrl).get().build();
            try (Response resp = httpClient.newCall(request).execute()) {
                if (resp.body() == null) throw new IOException("empty body");
                if (resp.code() != 200) throw new IOException("http status=" + resp.code());

                byte[] bytes = resp.body().bytes();
                String text = tryParseTextThenGunzip(bytes);
                if (text != null && !text.trim().isEmpty()) {
                    JsonNode json = objectMapper.readTree(text);
                    inplayCacheTsMs = now;
                    inplayCacheData = json;
                    return json;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            String text = java.nio.file.Files.readString(java.nio.file.Paths.get(snapshotPath), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(text);
            inplayCacheTsMs = now;
            inplayCacheData = json;
            return json;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String tryParseTextThenGunzip(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String t = text.trim();
            if (t.startsWith("{") || t.startsWith("[")) return text;
        } catch (Exception ignored) {
        }

        try {
            return gunzipToString(bytes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String gunzipToString(byte[] bytes) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            gis.transferTo(baos);
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private Iterable<JsonNode> iterateEvents(JsonNode eventsNode) {
        if (eventsNode.isArray()) {
            List<JsonNode> list = new ArrayList<>();
            for (JsonNode n : eventsNode) list.add(n);
            return list;
        }
        if (eventsNode.isObject()) {
            List<JsonNode> list = new ArrayList<>();
            Iterator<JsonNode> it = eventsNode.elements();
            while (it.hasNext()) list.add(it.next());
            return list;
        }
        return List.of();
    }

    private boolean isInplayInJsLogic(JsonNode evt) {
        String finished = textTrim(evt.path("core").path("finished"));
        if ("1".equals(finished)) return false;

        String stopped = textTrim(evt.path("core").path("stopped"));
        int minute = toInt(firstNonMissing(evt.path("info").path("minute"), evt.path("minute")));

        String ts = textTrim(firstTextNode(evt.path("time_status"), evt.path("timeStatus")));
        if ("1".equals(ts) || "2".equals(ts)) return true;

        String period = evt.path("info").path("period").asText("");
        period = period == null ? "" : period.toLowerCase();
        if (period.contains("half") || period.contains("extra") || period.contains("penalt")) return true;

        if ("1".equals(stopped) && minute == 0) return false;
        if (minute > 0) return true;

        return false;
    }

    private MatchSummary mapInplayEventToMatchSummary(JsonNode evt) {
        String id = firstNonEmpty(
                textTrim(evt.path("info").path("id")),
                textTrim(evt.path("id")),
                textTrim(evt.path("info").path("mid")),
                textTrim(evt.path("mid")),
                textTrim(evt.path("fi"))
        );
        if (id.isEmpty()) id = firstNonEmpty(
                textTrim(evt.path("match_id")),
                textTrim(evt.path("matchId"))
        );

        String homeName = textTrim(evt.path("team_info").path("home").path("name"));
        String awayName = textTrim(evt.path("team_info").path("away").path("name"));
        if (id.isEmpty() || homeName.isEmpty() || awayName.isEmpty()) return null;

        String leagueName = textTrim(evt.path("info").path("league"));
        if (leagueName.isEmpty()) leagueName = textTrim(evt.path("cmp_name"));
        if (leagueName.isEmpty()) leagueName = "未知联赛";
        String leagueId = textTrim(evt.path("info").path("league_id"));
        if (leagueId.isEmpty()) leagueId = leagueName;

        String statusCode = inferTimeStatusCode(evt);
        String status = timeStatusLabel(statusCode);

        int minute = toInt(firstNonMissing(evt.path("info").path("minute"), evt.path("minute")));

        int homeScore = toInt(evt.path("team_info").path("home").path("score"));
        int awayScore = toInt(evt.path("team_info").path("away").path("score"));

        return new MatchSummary(
                id,
                leagueId,
                leagueName,
                status,
                minute,
                homeName,
                homeScore,
                awayName,
                awayScore,
                "地区：未知"
        );
    }

    private String inferTimeStatusCode(JsonNode evt) {
        String explicitText = textTrim(evt.path("time_status"));
        if (!explicitText.isEmpty()) return explicitText;
        explicitText = textTrim(evt.path("timeStatus"));
        if (!explicitText.isEmpty()) return explicitText;
        explicitText = textTrim(evt.path("info").path("time_status"));
        if (!explicitText.isEmpty()) return explicitText;
        explicitText = textTrim(evt.path("info").path("timeStatus"));
        if (!explicitText.isEmpty()) return explicitText;

        String finished = textTrim(evt.path("core").path("finished"));
        if ("1".equals(finished)) return "3";

        String period = evt.path("info").path("period").asText("");
        period = period == null ? "" : period.toLowerCase();
        if (period.contains("not started")) return "0";
        if (period.contains("postponed")) return "4";
        if (period.contains("cancel")) return "5";
        if (period.contains("abandon")) return "8";
        if (period.contains("interrupt")) return "7";
        if (period.contains("retired")) return "9";
        if (period.contains("walkover") || "wo".equals(period)) return "6";
        if (period.contains("half") || period.contains("time") || period.contains("penalt") || period.contains("extra")) return "1";

        int minute = toInt(firstNonMissing(evt.path("info").path("minute"), evt.path("minute")));
        if (minute > 0) return "1";
        return "0";
    }

    private String timeStatusLabel(String ts) {
        String s = ts == null ? "" : ts.trim();
        if ("0".equals(s)) return "未开始";
        if ("1".equals(s)) return "进行中";
        if ("2".equals(s)) return "待修复";
        if ("3".equals(s)) return "已完场";
        if ("4".equals(s)) return "推迟";
        if ("5".equals(s)) return "取消";
        if ("6".equals(s)) return "弃权";
        if ("7".equals(s)) return "中断";
        if ("8".equals(s)) return "放弃";
        if ("9".equals(s)) return "退赛";
        if ("99".equals(s)) return "移除";
        return "未知状态";
    }

    private List<StatLine> parseInplayStatsFromEvent(JsonNode evt) {
        JsonNode stats = evt.get("stats");
        if (stats == null || stats.isNull()) return List.of();

        Set<String> allowed = new HashSet<>();
        allowed.add("ICorner");
        allowed.add("IFreeKick");
        allowed.add("IThrowIn");
        allowed.add("IGoalKick");
        allowed.add("IPenalty");
        allowed.add("ISubstitution");
        allowed.add("IYellowCard");
        allowed.add("IRedCard");
        allowed.add("IAttacks");
        allowed.add("IDangerousAttacks");
        allowed.add("IOnTarget");
        allowed.add("IOffTarget");
        allowed.add("IPosession");
        allowed.add("IRegTimeScore");

        Map<String, String> statKeyToLabel = new HashMap<>();
        statKeyToLabel.put("ICorner", "角球");
        statKeyToLabel.put("IFreeKick", "任意球");
        statKeyToLabel.put("IThrowIn", "界外球");
        statKeyToLabel.put("IGoalKick", "门球");
        statKeyToLabel.put("IPenalty", "点球");
        statKeyToLabel.put("ISubstitution", "换人");
        statKeyToLabel.put("IYellowCard", "黄牌");
        statKeyToLabel.put("IRedCard", "红牌");
        statKeyToLabel.put("IAttacks", "进攻");
        statKeyToLabel.put("IDangerousAttacks", "危险进攻");
        statKeyToLabel.put("IOnTarget", "射正");
        statKeyToLabel.put("IOffTarget", "射偏");
        statKeyToLabel.put("IPosession", "控球率");
        statKeyToLabel.put("IRegTimeScore", "常规时间比分");

        List<StatLine> list = new ArrayList<>();
        if (stats.isArray()) {
            for (JsonNode row : stats) {
                StatLine line = mapAllowedStatRow(row, allowed, statKeyToLabel);
                if (line != null) list.add(line);
            }
        } else if (stats.isObject()) {
            Iterator<JsonNode> it = stats.elements();
            while (it.hasNext()) {
                JsonNode row = it.next();
                StatLine line = mapAllowedStatRow(row, allowed, statKeyToLabel);
                if (line != null) list.add(line);
            }
        }

        return list;
    }

    private StatLine mapAllowedStatRow(JsonNode row, Set<String> allowed, Map<String, String> statKeyToLabel) {
        String key = textTrim(row.path("name"));
        if (key.isEmpty() || !allowed.contains(key)) return null;
        int home = toInt(row.path("home"));
        int away = toInt(row.path("away"));
        String label = statKeyToLabel.getOrDefault(key, key);
        return new StatLine(key, label, home, away);
    }

    private String translateStateName(String code, String rawName) {
        if (code == null) code = "";
        String s = STATE_MAP.get(code);
        if (s != null) return s;
        return rawName == null ? "" : rawName;
    }

    private String normalizeName(String s) {
        if (s == null) s = "";
        return TEAM_NORMALIZE_PATTERN.matcher(s.toLowerCase()).replaceAll("");
    }

    private boolean isSimilarName(String candidateNorm, String queryNorm) {
        if (candidateNorm == null) candidateNorm = "";
        if (queryNorm == null) queryNorm = "";
        if (candidateNorm.isEmpty() || queryNorm.isEmpty()) return false;
        return candidateNorm.equals(queryNorm) || candidateNorm.contains(queryNorm) || queryNorm.contains(candidateNorm);
    }

    private String firstText(JsonNode a, JsonNode b) {
        String sa = textTrim(a);
        if (!sa.isEmpty()) return sa;
        return textTrim(b);
    }

    private String firstNonEmpty(String... arr) {
        for (String s : arr) {
            if (s != null && !s.isEmpty()) return s;
        }
        return "";
    }

    private String textTrim(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        String s = node.isTextual() ? node.asText() : node.asText();
        if (s == null) return "";
        return s.trim();
    }

    private int toInt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        try {
            if (node.isInt() || node.isLong() || node.isShort() || node.isBigInteger()) return node.asInt();
            String s = textTrim(node);
            if (s.isEmpty()) return 0;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private String getenvOrDefault(String key, String def) {
        try {
            String v = System.getenv(key);
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Exception ignored) {
            return def;
        }
    }

    private JsonNode firstNonMissing(JsonNode a, JsonNode b) {
        if (a != null && !a.isMissingNode() && !a.isNull()) return a;
        return b;
    }

    private JsonNode firstTextNode(JsonNode a, JsonNode b) {
        return firstNonMissing(a, b);
    }
}

