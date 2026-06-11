package com.example.demo.task;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.*;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.config.SuccessBasedLimitManager;
import com.example.demo.core.SoccerApiNewTool;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import com.example.demo.model.dto.settings.BetAmountDTO;
import com.example.demo.model.dto.settings.IntervalDTO;
import com.example.demo.model.dto.settings.LimitDTO;
import com.example.demo.model.dto.settings.TimeFrameDTO;
import com.example.demo.model.vo.dict.BindLeagueVO;
import com.example.demo.model.vo.dict.BindTeamVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class CheckCornerTask {

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;
    @Resource
    private RealtimeIndexService realtimeIndexService;

    @Resource
    private SweepwaterService sweepwaterService;

    @Resource
    private HandicapApi handicapApi;

    @Resource
    private AdminService adminService;

    @Resource
    private BetService betService;

    @Resource
    private SettingsService settingsService;

    @Resource
    private SettingsBetService settingsBetService;

    @Resource
    private SettingsFilterService settingsFilterService;

    @Resource
    private SuccessBasedLimitManager limitManager;

    @Resource
    private BindDictService bindDictService;

    private final SoccerApiNewTool tool = new SoccerApiNewTool();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, String> lastStateByMatch = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastCornerMinuteByMatch = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProcessTimeByMatch = new ConcurrentHashMap<>();
    private final Map<String, Boolean> processedCornerByMatch = new ConcurrentHashMap<>();
    private static final long MIN_PROCESS_INTERVAL_MS = 2000;

    @Scheduled(fixedDelay = 100)
    public void checkCorner() {
        if (!running.compareAndSet(false, true)) {
            log.info("角球检测任务正在运行中，本轮跳过...");
            return;
        }
        log.info("角球检测任务开始执行...");
        int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        ExecutorService adminExecutor = Executors.newFixedThreadPool(poolSize);
        ExecutorService leagueExecutor = Executors.newFixedThreadPool(poolSize);
        ExecutorService teamExecutor = Executors.newFixedThreadPool(poolSize);
        try {
            List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
            if (adminUsers == null || adminUsers.isEmpty()) {
                return;
            }
            adminUsers.removeIf(adminUser -> adminUser.getStatus() == 0);
            if (adminUsers.isEmpty()) {
                // 没有开启投注的平台用户
                return;
            }
            List<Future<?>> adminFutures = new ArrayList<>();
            for (AdminLoginDTO admin : adminUsers) {
                adminFutures.add(adminExecutor.submit(() -> {
                    TimeInterval bindTotal = DateUtil.timer();
                    List<BindLeagueVO> bindGroups = bindDictService.getCornerBindDictAll(admin.getUsername());
                    log.info("角球检测任务：联赛绑定获取结束，总花费:{}毫秒", bindTotal.interval());
                    if (bindGroups == null || bindGroups.isEmpty()) {
                        return;
                    }
                    log.info("角球检测任务：平台用户 {} 有绑定数据，共 {} 条联赛绑定", admin.getUsername(), bindGroups.size());
                    List<Future<?>> leagueFutures = new ArrayList<>();
                    for (BindLeagueVO league : bindGroups) {
                        leagueFutures.add(leagueExecutor.submit(() -> {
                            if (!WebsiteType.XINBAO.getId().equals(league.getWebsiteIdB())) {
                                return;
                            }
                            if (league.getLeagueIdA() == null || league.getLeagueIdB() == null || league.getEvents() == null) {
                                return;
                            }
                            List<Future<?>> teamFutures = new ArrayList<>();
                            for (BindTeamVO team : league.getEvents()) {
                                teamFutures.add(teamExecutor.submit(() -> {
                                    if (team.getIdA() == null || team.getIdB() == null) {
                                        return;
                                    }
                                    String matchIdA = team.getIdA();
                                    if (matchIdA == null || matchIdA.isEmpty()) {
                                        return;
                                    }
                                    SoccerApiNewTool.MatchDetail detail = tool.fetchMatchDetailByMatchId(matchIdA);
                                    if (detail == null) {
                                        return;
                                    }
                                    // 检查赛事状态
                                    SoccerApiNewTool.MatchSummary summary = detail.summary();
                                    String status = summary.status();
                                    if ("完场".equals(status)
                                            || "未开赛".equals(status)
                                            || "推迟".equals(status)
                                            || "中断".equals(status)
                                            || "腰斩".equals(status)
                                            || "取消".equals(status)
                                            || "待定".equals(status)) {
        //                                log.info(
        //                                        "角球检测任务：跳过赛事，赛事ID={}，赛事名称={}，状态={}",
        //                                        matchIdA,
        //                                        team.getNameB(),
        //                                        status
        //                                );
                                        return;
                                    }
                                    // 检查最新的事件是否为角球
                                    boolean isCorner = false;
                                    String stateCode = "";
                                    String key = matchIdA;
                                    int currentMinute = 0;
                                    Long now = System.currentTimeMillis();
                                    Integer originalLastMinute = lastCornerMinuteByMatch.get(key); // 保存原始值用于回滚
                                    
                                    // 防抖动检查：快速连续调用直接跳过（使用原子操作）
                                    Long lastProcessTime = lastProcessTimeByMatch.putIfAbsent(key, now);
                                    if (lastProcessTime != null && (now - lastProcessTime) < MIN_PROCESS_INTERVAL_MS) {
                                        return;
                                    }
                                    
                                    List<SoccerApiNewTool.MatchEvent> namiEvents = detail.events();
                                    if (!namiEvents.isEmpty()) {
                                        SoccerApiNewTool.MatchEvent latestEvent = namiEvents.get(namiEvents.size() - 1);
                                        if ("角球".equals(latestEvent.typeLabel())) {
                                            currentMinute = latestEvent.minute();
                                            
                                            // 修复：一个角球只处理一次，不管成功失败都永不重试
                                            // 用"分钟+事件类型"作为唯一标识，确保同一角球永不重复处理
                                            String cornerKey = key + "_" + currentMinute;
                                            Boolean alreadyProcessed = processedCornerByMatch.putIfAbsent(cornerKey, true);
                                            if (alreadyProcessed != null && alreadyProcessed) {
                                                return;
                                            }
                                            
                                            log.info(
                                                    "角球检测任务：发生角球，赛事ID={}，赛事名称={}，分钟={}",
                                                    matchIdA,
                                                    team.getNameB(),
                                                    currentMinute
                                            );
                                            // 原子操作：只有第一个线程能成功设置minute
                                            Integer existingMinute = lastCornerMinuteByMatch.putIfAbsent(key, currentMinute);
                                            
                                            if (existingMinute != null) {
                                                // 已有记录，检查是否为同一角球（分钟差<=1）或相同分钟
                                                if (currentMinute - existingMinute <= 1) {
                                                    log.info(
                                                            "角球检测任务：赛事ID={}，赛事名称={}，上次分钟={}，当前分钟={}，同一角球，跳过",
                                                            matchIdA,
                                                            team.getNameB(),
                                                            existingMinute,
                                                            currentMinute
                                                    );
                                                    return;
                                                }
                                                // 分钟差>1，说明是新角球，尝试更新
                                                if (!lastCornerMinuteByMatch.replace(key, existingMinute, currentMinute)) {
                                                    log.info(
                                                            "角球检测任务：赛事ID={}，赛事名称={}，并发更新失败，跳过",
                                                            matchIdA,
                                                            team.getNameB()
                                                    );
                                                    return;
                                                }
                                            }
                                            
                                            isCorner = true;
                                            stateCode = latestEvent.typeKey();
                                            if ("主队".equals(latestEvent.team())) {
                                                stateCode = "11004";
                                            } else if ("客队".equals(latestEvent.team())) {
                                                stateCode = "21004";
                                            }
                                            lastStateByMatch.put(key, stateCode);
                                            lastCornerMinuteByMatch.put(key, currentMinute);
                                        }
                                    }
                                    String last = lastStateByMatch.get(key);
                                    if (isCorner) {
                                        LimitDTO limit = settingsBetService.getLimit(admin.getUsername());
                                        // ========== 重试配置 ==========
                                        int maxRetries = (limit.getRetry() != null && limit.getRetry() == 1)
                                                ? limit.getRetryCount()
                                                : 1;                     // 最大重试次数
                                        long retryDelayMs = 200;                // 重试间隔0.2秒
                                        boolean betSuccess = false;             // 投注成功标志
                                        boolean isBet = false;                  // 是否进入投注流程标志
                                        String teamNameLabel = "";                   // 需要投注的队伍名称
                                        SweepwaterBetDTO sweepwater = new SweepwaterBetDTO();
                                        for (int retryCount = 0; retryCount < maxRetries && !betSuccess; retryCount++) {
                                            if (retryCount > 0) {
                                                log.info("角球检测任务：第{}次重试投注，赛事={}，分钟={}", retryCount, team.getNameB(), currentMinute);
                                                try {
                                                    Thread.sleep(retryDelayMs);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    break;
                                                }
                                            }

                                            String sideLabel;
                                            String nameB = team.getNameB();
                                            String homeTeam = nameB;
                                            String awayTeam = "";
                                            if (nameB != null && nameB.contains(" -vs- ")) {
                                                String[] parts = nameB.split(" -vs- ");
                                                if (parts.length >= 2) {
                                                    homeTeam = parts[0];
                                                    awayTeam = parts[1];
                                                }
                                            }
                                            if ("1004".equals(last)) {
                                                sideLabel = "未知球队角球";
                                                teamNameLabel = "";
                                            } else {
                                                boolean lastHome = last.startsWith("11");
                                                if (lastHome) {
                                                    sideLabel = "联赛B主队角球";
                                                    teamNameLabel = homeTeam;
                                                } else {
                                                    sideLabel = "联赛B客队角球";
                                                    teamNameLabel = awayTeam;
                                                }
                                            }

                                            BetAmountDTO amount = settingsService.getBetAmount(admin.getUsername());
                                            // 获取新二网站赔率
                                            JSONArray events = (JSONArray) handicapApi.eventsOdds(admin.getUsername(), league.getWebsiteIdB(), team.getIdB(), null);
                                            if (events.isEmpty()) {
                                                log.info("角球检测任务,投注跳过,平台用户:{},网站A:{},获取赛事id:{},名称:{},球队id:{},名称:{},赔率失败, 跳出",
                                                        admin.getUsername(),
                                                        WebsiteType.getById(league.getWebsiteIdB()).getDescription(),
                                                        league.getLeagueIdB(),
                                                        league.getLeagueNameB(),
                                                        team.getIdB(),
                                                        team.getNameB()
                                                );
                                                // 只要进入流程，就记录已执行投注，
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                JSONObject betInfo = new JSONObject();
                                                betInfo.putOpt("amount", amount.getAmountXinEr());
                                                betInfo.putOpt("betTeamName", teamNameLabel);
                                                betInfo.putOpt("handicap", null);
                                                betInfo.putOpt("league", league.getLeagueNameB());
                                                betInfo.putOpt("marketName", teamNameLabel);
                                                betInfo.putOpt("marketTypeName", "让球盘");
                                                betInfo.putOpt("odds", teamNameLabel);
                                                betInfo.putOpt("oddsValue", null);
                                                betInfo.putOpt("team", nameB);
                                                sweepwater.setBetInfoA(betInfo);
                                                continue;
                                            }
                                            // 提取球队名称
                                            List<String> names = Collections.singletonList(team.getNameB());
                                            Map<String, JSONObject> leagueMap = sweepwaterService.buildLeagueMap(events);
                                            // 平台绑定球队赛事对应获取盘口赛事列表
                                            JSONObject eventJson = sweepwaterService.findEventByLeagueName(leagueMap, league.getLeagueIdB(), names);
                                            if (eventJson == null ||
                                                    !eventJson.containsKey("events") ||
                                                    eventJson.getJSONArray("events") == null) {
                                                log.info("角球检测任务执行,投注跳过,平台用户:{},新二网站赔率数据为空,跳出", admin.getUsername());
                                                // 只要进入流程，就记录已执行投注，
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                JSONObject betInfo = new JSONObject();
                                                betInfo.putOpt("amount", amount.getAmountXinEr());
                                                betInfo.putOpt("betTeamName", teamNameLabel);
                                                betInfo.putOpt("handicap", null);
                                                betInfo.putOpt("league", league.getLeagueNameB());
                                                betInfo.putOpt("marketName", teamNameLabel);
                                                betInfo.putOpt("marketTypeName", "让球盘");
                                                betInfo.putOpt("odds", teamNameLabel);
                                                betInfo.putOpt("oddsValue", null);
                                                betInfo.putOpt("team", nameB);
                                                sweepwater.setBetInfoA(betInfo);
                                                continue;
                                            }
                                            String eventId = eventJson.getStr("ecid");
                                            log.info("角球检测任务执行,平台用户:{},新二网站赔率数据:{}", admin.getUsername(), eventJson);
                                            JSONArray eventsJson = eventJson.getJSONArray("events");
                                            if (eventsJson == null || eventsJson.isEmpty()) {
                                                log.info("角球检测任务执行,投注跳过,平台用户:{},赛事赔率 events 为空,跳出", admin.getUsername());
                                                // 只要进入流程，就记录已执行投注，
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                JSONObject betInfo = new JSONObject();
                                                betInfo.putOpt("amount", amount.getAmountXinEr());
                                                betInfo.putOpt("betTeamName", teamNameLabel);
                                                betInfo.putOpt("handicap", null);
                                                betInfo.putOpt("league", league.getLeagueNameB());
                                                betInfo.putOpt("marketName", teamNameLabel);
                                                betInfo.putOpt("marketTypeName", "让球盘");
                                                betInfo.putOpt("odds", teamNameLabel);
                                                betInfo.putOpt("oddsValue", null);
                                                betInfo.putOpt("team", nameB);
                                                sweepwater.setBetInfoA(betInfo);
                                                continue;
                                            }
                                            JSONObject event = eventsJson.getJSONObject(0);
                                            if (event == null) {
                                                log.info("角球检测任务执行,投注跳过,平台用户:{},赛事赔率 event 为空,跳出", admin.getUsername());
                                                // 只要进入流程，就记录已执行投注，
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                JSONObject betInfo = new JSONObject();
                                                betInfo.putOpt("amount", amount.getAmountXinEr());
                                                betInfo.putOpt("betTeamName", teamNameLabel);
                                                betInfo.putOpt("handicap", null);
                                                betInfo.putOpt("league", league.getLeagueNameB());
                                                betInfo.putOpt("marketName", teamNameLabel);
                                                betInfo.putOpt("marketTypeName", "让球盘");
                                                betInfo.putOpt("odds", teamNameLabel);
                                                betInfo.putOpt("oddsValue", null);
                                                betInfo.putOpt("team", nameB);
                                                sweepwater.setBetInfoA(betInfo);
                                                continue;
                                            }
                                            String eid = event.getStr("id");
                                            String score = event.getStr("score");
                                            String reTime = event.getStr("reTime");
                                            JSONObject fullCourt = event.getJSONObject("fullCourt");
                                            if (fullCourt == null) {
                                                log.info("角球检测任务执行,投注跳过,平台用户:{},赛事 fullCourt 为空,跳出", admin.getUsername());
                                                // 只要进入流程，就记录已执行投注，
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                JSONObject betInfo = new JSONObject();
                                                betInfo.putOpt("amount", amount.getAmountXinEr());
                                                betInfo.putOpt("betTeamName", teamNameLabel);
                                                betInfo.putOpt("handicap", null);
                                                betInfo.putOpt("league", league.getLeagueNameB());
                                                betInfo.putOpt("marketName", teamNameLabel);
                                                betInfo.putOpt("marketTypeName", "让球盘");
                                                betInfo.putOpt("odds", teamNameLabel);
                                                betInfo.putOpt("oddsValue", null);
                                                betInfo.putOpt("team", nameB);
                                                sweepwater.setBetInfoA(betInfo);
                                                continue;
                                            }
                                            JSONObject letBall = fullCourt.getJSONObject("letBall");
                                            if (letBall == null) {
                                                log.info("角球检测任务执行,投注跳过,平台用户:{},赛事 letBall 为空,跳出", admin.getUsername());
                                                // 只要进入流程，就记录已执行投注，
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                JSONObject betInfo = new JSONObject();
                                                betInfo.putOpt("amount", amount.getAmountXinEr());
                                                betInfo.putOpt("betTeamName", teamNameLabel);
                                                betInfo.putOpt("handicap", null);
                                                betInfo.putOpt("league", league.getLeagueNameB());
                                                betInfo.putOpt("marketName", teamNameLabel);
                                                betInfo.putOpt("marketTypeName", "让球盘");
                                                betInfo.putOpt("odds", teamNameLabel);
                                                betInfo.putOpt("oddsValue", null);
                                                betInfo.putOpt("team", nameB);
                                                sweepwater.setBetInfoA(betInfo);
                                                continue;
                                            }
                                            JSONObject up = letBall.getJSONObject("up");
                                            JSONObject down = letBall.getJSONObject("down");
                                            boolean lastHome = last.startsWith("11") || last.startsWith("119");
                                            String desiredChoseTeam = lastHome ? "H" : "C";
                                            JSONObject firstOdds = null;
                                            if (up != null && !up.isEmpty()) {
                                                for (Object val : up.values()) {
                                                    if (val instanceof JSONObject jo && desiredChoseTeam.equalsIgnoreCase(jo.getStr("choseTeam"))) {
                                                        firstOdds = jo;
                                                        break;
                                                    }
                                                }
                                            }
                                            if (firstOdds == null && down != null && !down.isEmpty()) {
                                                for (Object val : down.values()) {
                                                    if (val instanceof JSONObject jo && desiredChoseTeam.equalsIgnoreCase(jo.getStr("choseTeam"))) {
                                                        firstOdds = jo;
                                                        break;
                                                    }
                                                }
                                            }
                                            if (firstOdds == null) {
                                                log.info("角球检测任务执行,投注跳过,平台用户:{},未找到符合 choseTeam={} 的盘口,跳出", admin.getUsername(), desiredChoseTeam);
                                                // 只要进入流程，就记录已执行投注，
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                JSONObject betInfo = new JSONObject();
                                                betInfo.putOpt("amount", amount.getAmountXinEr());
                                                betInfo.putOpt("betTeamName", teamNameLabel);
                                                betInfo.putOpt("handicap", null);
                                                betInfo.putOpt("league", league.getLeagueNameB());
                                                betInfo.putOpt("marketName", teamNameLabel);
                                                betInfo.putOpt("marketTypeName", "让球盘");
                                                betInfo.putOpt("odds", teamNameLabel);
                                                betInfo.putOpt("oddsValue", null);
                                                betInfo.putOpt("team", nameB);
                                                sweepwater.setBetInfoA(betInfo);
                                                continue;
                                            }
                                            log.info("角球检测任务：选择投注盘口，平台用户:{}，角球方:{}，choseTeam={}，赔率JSON:{}",
                                                    admin.getUsername(),
                                                    lastHome ? "主队角球" : "客队角球",
                                                    desiredChoseTeam,
                                                    firstOdds);
                                            try {
                                                String choseTeam = firstOdds.getStr("choseTeam");
                                                String betTeamName = teamNameLabel;
                                                if ("H".equalsIgnoreCase(choseTeam)) {
                                                    betTeamName = homeTeam;
                                                } else if ("C".equalsIgnoreCase(choseTeam) || "A".equalsIgnoreCase(choseTeam)) {
                                                    betTeamName = awayTeam;
                                                }
                                                if (betTeamName == null || betTeamName.isEmpty()) {
                                                    log.info("角球检测任务：投注跳过，队伍名称为空，user={}, 赛事={}", admin.getUsername(), team.getNameB());
                                                    // 只要进入流程，就记录已执行投注，
                                                    lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                    JSONObject betInfo = new JSONObject();
                                                    betInfo.putOpt("amount", amount.getAmountXinEr());
                                                    betInfo.putOpt("betTeamName", teamNameLabel);
                                                    betInfo.putOpt("handicap", firstOdds.getStr("handicap"));
                                                    betInfo.putOpt("league", league.getLeagueNameB());
                                                    betInfo.putOpt("marketName", teamNameLabel);
                                                    betInfo.putOpt("marketTypeName", "让球盘");
                                                    betInfo.putOpt("odds", teamNameLabel + " " + firstOdds.getStr("handicap"));
                                                    betInfo.putOpt("oddsValue", firstOdds.getStr("odds"));
                                                    betInfo.putOpt("team", nameB);
                                                    sweepwater.setBetInfoA(betInfo);
                                                    continue;
                                                }
                                                if (eventId == null || eventId.isEmpty()) {
                                                    log.info("角球检测任务：投注跳过，eventId为空，user={}, 赛事={}", admin.getUsername(), team.getNameB());
                                                    // 只要进入流程，就记录已执行投注，
                                                    lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                    JSONObject betInfo = new JSONObject();
                                                    betInfo.putOpt("amount", amount.getAmountXinEr());
                                                    betInfo.putOpt("betTeamName", teamNameLabel);
                                                    betInfo.putOpt("handicap", firstOdds.getStr("handicap"));
                                                    betInfo.putOpt("league", league.getLeagueNameB());
                                                    betInfo.putOpt("marketName", teamNameLabel);
                                                    betInfo.putOpt("marketTypeName", "让球盘");
                                                    betInfo.putOpt("odds", teamNameLabel + " " + firstOdds.getStr("handicap"));
                                                    betInfo.putOpt("oddsValue", firstOdds.getStr("odds"));
                                                    betInfo.putOpt("team", nameB);
                                                    sweepwater.setBetInfoA(betInfo);
                                                    continue;
                                                }
                                                String oddsId = firstOdds.getStr("id");
                                                if (oddsId == null || oddsId.isEmpty()) {
                                                    log.info("角球检测任务：投注跳过，赔率id为空，user={}, 赛事={}, odds={}", admin.getUsername(), team.getNameB(), firstOdds);
                                                    // 只要进入流程，就记录已执行投注，
                                                    lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                    JSONObject betInfo = new JSONObject();
                                                    betInfo.putOpt("amount", amount.getAmountXinEr());
                                                    betInfo.putOpt("betTeamName", teamNameLabel);
                                                    betInfo.putOpt("handicap", firstOdds.getStr("handicap"));
                                                    betInfo.putOpt("league", league.getLeagueNameB());
                                                    betInfo.putOpt("marketName", teamNameLabel);
                                                    betInfo.putOpt("marketTypeName", "让球盘");
                                                    betInfo.putOpt("odds", teamNameLabel + " " + firstOdds.getStr("handicap"));
                                                    betInfo.putOpt("oddsValue", firstOdds.getStr("odds"));
                                                    betInfo.putOpt("team", nameB);
                                                    sweepwater.setBetInfoA(betInfo);
                                                    continue;
                                                }
                                                IntervalDTO interval = settingsBetService.getInterval(admin.getUsername());
                                                List<TimeFrameDTO> timeFrames = settingsFilterService.getTimeFrames(admin.getUsername());
                                                if (!timeFrames.isEmpty() || !"中场".equals(reTime)) {
                                                    TimeFrameDTO timeFrame = timeFrames.get(0);
                                                    int reTimeValue = Integer.parseInt(reTime);
                                                    if (reTimeValue < timeFrame.getTimeFormSec() || reTimeValue > timeFrame.getTimeToSec()) {
                                                        log.info("角球检测任务：当前赛事时间:{}不在[{}-{}]范围内",
                                                                reTimeValue, timeFrame.getTimeFormSec(), timeFrame.getTimeToSec());
                                                        // 只要进入流程，就记录已执行投注，
                                                        lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                        return;
                                                    }
                                                }
                                                if (amount == null || limit == null) {
                                                    log.info("角球检测任务：投注跳过，配置为空，user={}, amountNull={}, limitNull={}",
                                                            admin.getUsername(), amount == null, limit == null);
                                                    // 只要进入流程，就记录已执行投注，
                                                    lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                    JSONObject betInfo = new JSONObject();
                                                    betInfo.putOpt("amount", amount.getAmountXinEr());
                                                    betInfo.putOpt("betTeamName", teamNameLabel);
                                                    betInfo.putOpt("handicap", firstOdds.getStr("handicap"));
                                                    betInfo.putOpt("league", league.getLeagueNameB());
                                                    betInfo.putOpt("marketName", teamNameLabel);
                                                    betInfo.putOpt("marketTypeName", "让球盘");
                                                    betInfo.putOpt("odds", teamNameLabel + " " + firstOdds.getStr("handicap"));
                                                    betInfo.putOpt("oddsValue", firstOdds.getStr("odds"));
                                                    betInfo.putOpt("team", nameB);
                                                    sweepwater.setBetInfoA(betInfo);
                                                    continue;
                                                }

                                                log.info("角球检测任务：配置信息，user={}, amount={}, limit={}",
                                                        admin.getUsername(), amount, limit);
                                                sweepwater.setWebsiteIdA(league.getWebsiteIdB());
                                                sweepwater.setOddsIdA(oddsId);
                                                sweepwater.setStrongA(firstOdds.getStr("oddFType"));
                                                sweepwater.setGTypeA(firstOdds.getStr("gtype"));
                                                sweepwater.setWTypeA(firstOdds.getStr("wtype"));
                                                sweepwater.setRTypeA(firstOdds.getStr("rtype"));
                                                sweepwater.setChoseTeamA(firstOdds.getStr("choseTeam"));
                                                sweepwater.setOddsA(firstOdds.getBigDecimal("odds"));
                                                sweepwater.setConA(firstOdds.getStr("con"));
                                                sweepwater.setRatioA(firstOdds.getStr("ratio"));
                                                sweepwater.setHandicapA(firstOdds.getStr("handicap"));
                                                sweepwater.setScoreA(score);
                                                sweepwater.setReTimeA(reTime);
                                                sweepwater.setHandicapType("letBall");

                                                // 校验投注间隔key - 使用matchIdA确保同一赛事始终使用相同的key
                                                String intervalKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_INTERVAL_PREFIX, "corner", admin.getUsername(), String.valueOf(admin.getSimulateBet()), matchIdA, null);
                                                // 投注次数限制key
                                                String limitKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, "corner", admin.getUsername(), String.valueOf(admin.getSimulateBet()), matchIdA, null);

                                                // 这里是校验间隔时间
                                                long intervalMillis = interval.getBetSuccessSec() * 1000L;

                                                // 1. 强制预检查
                                                SuccessBasedLimitManager.EnforcementResult checkResult = limitManager.preCheckAndReserve(
                                                        limitKey, intervalKey, sweepwater.getScoreA(), limit, intervalMillis);

                                                // 快速检查：如果明显不满足条件，直接返回（但要回滚状态）
                                                if (!checkResult.isSuccess()) {
                                                    log.info("角球检测任务：快速检查不满足: 用户 {}, 联赛:{}, 比分 {}, 原因: {}",
                                                            admin.getUsername(), sweepwater.getLeague(), sweepwater.getScoreA(), checkResult.getFailReason());
                                                    // 回滚状态，允许下次重试
                                                    if (originalLastMinute == null) {
                                                        lastCornerMinuteByMatch.remove(key);
                                                    } else {
                                                        lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                    }
                                                    lastStateByMatch.remove(key);
                                                    return;
                                                }
                                                // 保存reservationId，用于后续确认或回滚
                                                String reservationId = checkResult.getReservationId();
                                                boolean betAmountByOdds = limit.getBetAmountByOdds() != null && limit.getBetAmountByOdds() == 1;

                                                // 赔率获取正常，先把信息写入betInfoA
                                                JSONObject betInfo = new JSONObject();
                                                betInfo.putOpt("amount", amount.getAmountXinEr());
                                                betInfo.putOpt("betTeamName", teamNameLabel);
                                                betInfo.putOpt("handicap", firstOdds.getStr("handicap"));
                                                betInfo.putOpt("league", league.getLeagueNameB());
                                                betInfo.putOpt("marketName", teamNameLabel);
                                                betInfo.putOpt("marketTypeName", "让球盘");
                                                betInfo.putOpt("odds", teamNameLabel + " " + firstOdds.getStr("handicap"));
                                                betInfo.putOpt("oddsValue", firstOdds.getStr("odds"));
                                                betInfo.putOpt("team", nameB);
                                                sweepwater.setBetInfoA(betInfo);

                                                JSONObject betParams = betService.buildBetParams(sweepwater, amount, true, false);
                                                JSONObject retryPreview = betService.buildBetInfo(
                                                        admin.getUsername(), betTeamName, league.getWebsiteIdB(),
                                                        betParams, true, sweepwater, betAmountByOdds, null
                                                );
                                                if (retryPreview == null) {
                                                    log.info("角球检测任务：预览失败，user={}, 投注队伍={}, 赛事={}, oddsId={}",
                                                            admin.getUsername(), betTeamName, eventId, oddsId);
                                                    limitManager.rollbackReservation(limitKey, reservationId);
                                                    // 只要进入流程，就记录已执行投注，
                                                    lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                    continue; 
                                                }
                                                JSONObject successJson = betService.tryBetCorner(admin.getUsername(), eventId, league.getWebsiteIdB(), true, sweepwater, limit, amount, retryPreview);
                                                isBet = true;
                                                if (successJson == null) {
                                                    log.info("角球检测任务：投注返回空，user={}, 投注队伍={}, 赛事={}, oddsId={}",
                                                            admin.getUsername(), betTeamName, eventId, oddsId);
                                                    // 只要进入流程，就记录已执行投注，
                                                    lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                    continue;
                                                }
                                                boolean success = successJson.getBool("success", false);
                                                if (!success) {
                                                    limitManager.rollbackReservation(limitKey, reservationId);
                                                    log.info("角球检测任务：投注失败,用户 {} 赛事A={} 赛事B={} 投注失败", admin.getUsername(), sweepwater.getLeagueNameA(), sweepwater.getLeagueNameB());
                                                    // 只要进入流程，就记录已执行投注，
                                                    lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                                    continue;
                                                } else {
                                                    limitManager.confirmSuccess(limitKey, reservationId);
                                                    betSuccess = true;
                                                }
                                                log.info("角球检测任务：投注结果: user={}, 投注队伍={}, eventId={}, oddsId={}, success={}, raw={}",
                                                        admin.getUsername(), betTeamName, eventId, oddsId, success, successJson);

                                            } catch (Exception e) {
                                                log.info("角球检测任务：执行投注异常，user={}, 赛事={}, eventId={}, teamName={}, odds={}, error={}",
                                                        admin.getUsername(), team.getNameB(), eventId, teamNameLabel, firstOdds, e.getMessage());
                                            }
                                        }
                                        if (!isBet && !betSuccess) {
                                            // 角球没有进入投注流程就失败了，才进行记录保存
                                            betFailed(admin.getUsername(), sweepwater, teamNameLabel, league.getWebsiteIdB(), league.getLeagueIdB(), team.getIdB());
                                        }
                                        // 投注全部失败：回滚lastCornerMinuteByMatch，允许下次重试,注释掉则说明投注失败后下次不允许重试
                                        /*if (!betSuccess && isCorner) {
                                            if (originalLastMinute == null) {
                                                lastCornerMinuteByMatch.remove(key);
                                            } else {
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                            }
                                            lastStateByMatch.remove(key);
                                            log.info("角球检测任务：投注全部失败，已回滚状态，赛事ID={}，赛事名称={}", matchIdA, team.getNameB());
                                        }*/
                                    }
                                }));
                            }
                            for (Future<?> future : teamFutures) {
                                try {
                                    future.get();
                                } catch (Exception e) {
                                    log.error("角球检测任务：并发处理赛事失败，user={}，leagueIdA={}, emsg={}", admin.getUsername(), league.getLeagueIdA(), e.getMessage());
                                }
                            }
                        }));
                    }
                    for (Future<?> future : leagueFutures) {
                        try {
                            future.get();
                        } catch (Exception e) {
                            log.error("角球检测任务：并发处理联赛失败，user={}", admin.getUsername(), e);
                        }
                    }
                }));
            }
            for (Future<?> future : adminFutures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log.error("角球检测任务：并发处理用户失败", e);
                }
            }
        } catch (Exception e) {
            log.error("角球检测任务执行失败", e);
        } finally {
            adminExecutor.shutdown();
            leagueExecutor.shutdown();
            teamExecutor.shutdown();
            running.set(false);
        }
    }

    /**
     * 角球投注失败记录-就算没有进入到投注流程也记录因为前端需要展示
     * @param username
     * @param dto
     * @param websiteId
     */
    public void betFailed(String username, SweepwaterBetDTO dto, String teamName, String websiteId, String leagueId, String eventId) {
        log.info("角球投注失败记录-就算没有进入到投注流程也记录因为前端需要展示，username={},dto={},websiteId={}", username, dto, websiteId);
        if (dto.getId() == null || dto.getId().isEmpty()) {
            dto.setId(IdUtil.fastSimpleUUID());
        } else {
            log.info("角球投注记录已经写入,无需重复写入 username={}, id={}", username, dto.getId());
            return;
        }
        dto.setBetSuccessA(false);
        dto.setEventIdA(eventId);
        dto.setLeagueIdA(leagueId);
        dto.setWebsiteIdA(websiteId);
        dto.setWebsiteNameA(WebsiteType.getById(websiteId).getDescription());
        dto.setBetTimeA(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
        dto.setCreateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
        String date = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN);

        // 投注成功后异步调用599-sniffer获取比分详情URL
        String detailUrl = betService.fetchBiFenUrlAsync(teamName, username);
        dto.setBiFenUrlA(detailUrl);
        String json = JSONUtil.toJsonStr(dto);

        String key = KeyUtil.genKey(
                RedisConstants.PLATFORM_BET_CORNER_PREFIX,
                username,
                date,
                dto.getId()
        );
        businessPlatformRedissonClient.getBucket(key).set(json);
        String indexKey = KeyUtil.genKey("INDEX", username, "corner-history", date);
        businessPlatformRedissonClient.getList(indexKey).add(key);

        String realTimeKey = KeyUtil.genKey(
                RedisConstants.PLATFORM_BET_CORNER_PREFIX,
                username,
                "realtime",
                dto.getId()
        );
        realtimeIndexService.pushRealtimeIndex(username, realTimeKey, json, "corner", true);

    }

}

