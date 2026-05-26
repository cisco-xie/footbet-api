package com.example.demo.task;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class CheckCornerTask {

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
                                    log.info("角球检测任务：平台用户 {} 检查赛事:{}", admin.getUsername(), team.getNameB());
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
                                    
                                    // 防抖动检查：快速连续调用直接跳过
                                    Long lastProcessTime = lastProcessTimeByMatch.get(key);
                                    if (lastProcessTime != null && (now - lastProcessTime) < MIN_PROCESS_INTERVAL_MS) {
                                        return;
                                    }
                                    lastProcessTimeByMatch.put(key, now);
                                    
                                    List<SoccerApiNewTool.MatchEvent> namiEvents = detail.events();
                                    if (!namiEvents.isEmpty()) {
                                        SoccerApiNewTool.MatchEvent latestEvent = namiEvents.get(namiEvents.size() - 1);
                                        if ("角球".equals(latestEvent.typeLabel())) {
                                            currentMinute = latestEvent.minute();
                                            log.info(
                                                    "角球检测任务：发生角球，赛事ID={}，赛事名称={}，分钟={}",
                                                    matchIdA,
                                                    team.getNameB(),
                                                    currentMinute
                                            );
                                            
                                            // 使用原子操作避免竞态条件
                                            Integer lastMinute = lastCornerMinuteByMatch.get(key);
                                            if (lastMinute != null && (currentMinute - lastMinute) <= 1) {
                                                log.info(
                                                        "角球检测任务：赛事ID={}，赛事名称={}，上次分钟={}，当前分钟={}，已处理过，跳过",
                                                        matchIdA,
                                                        team.getNameB(),
                                                        lastMinute,
                                                        currentMinute
                                                );
                                                return;
                                            }
                                            
                                            // 原子检查并设置 - 只有第一个线程能成功设置
                                            if (lastMinute == null) {
                                                lastCornerMinuteByMatch.put(key, currentMinute);
                                            } else {
                                                // 防止并发 - 只有第一个能成功更新
                                                if (!lastCornerMinuteByMatch.replace(key, lastMinute, currentMinute)) {
                                                    log.info(
                                                            "角球检测任务：赛事ID={}，赛事名称={}，并发检测到相同角球，跳过",
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
                                        }
                                    }
                                    String last = lastStateByMatch.get(key);
                                    if (isCorner) {
                                        // ========== 重试配置 ==========
                                        int maxRetries = 3;                    // 最大重试次数
                                        long retryDelayMs = 200;              // 重试间隔0.2秒
                                        boolean betSuccess = false;            // 投注成功标志

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
                                            String teamNameLabel;
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

                                            // 获取新二网站赔率
                                            JSONArray events = (JSONArray) handicapApi.eventsOdds(admin.getUsername(), league.getWebsiteIdB(), team.getIdB(), null);
                                            if (events.isEmpty()) {
                                                log.info("角球检测任务,平台用户:{},网站A:{},获取赛事id:{},名称:{},球队id:{},名称:{},赔率失败, 跳出",
                                                        admin.getUsername(),
                                                        WebsiteType.getById(league.getWebsiteIdB()).getDescription(),
                                                        league.getLeagueIdB(),
                                                        league.getLeagueNameB(),
                                                        team.getIdB(),
                                                        team.getNameB()
                                                );
                                                continue;
                                            }
                                            // 提取球队名称
                                            List<String> names = Collections.singletonList(team.getNameB());
                                            Map<String, JSONObject> leagueMap = sweepwaterService.buildLeagueMap(events);
                                            // 平台绑定球队赛事对应获取盘口赛事列表
                                            JSONObject eventJson = sweepwaterService.findEventByLeagueName(leagueMap, league.getLeagueIdB(), names);
                                            if (eventJson == null || eventJson.getJSONArray("events").isEmpty()) {
                                                log.info("角球检测任务执行,平台用户:{},新二网站赔率数据为空,跳出", admin.getUsername());
                                                continue;
                                            }
                                            String eventId = eventJson.getStr("ecid");
                                            log.info("角球检测任务执行,平台用户:{},新二网站赔率数据:{}", admin.getUsername(), eventJson);
                                            JSONArray eventsJson = eventJson.getJSONArray("events");
                                            if (eventsJson == null || eventsJson.isEmpty()) {
                                                log.info("角球检测任务执行,平台用户:{},赛事赔率 events 为空,跳出", admin.getUsername());
                                                continue;
                                            }
                                            JSONObject event = eventsJson.getJSONObject(0);
                                            if (event == null) {
                                                log.info("角球检测任务执行,平台用户:{},赛事赔率 event 为空,跳出", admin.getUsername());
                                                continue;
                                            }
                                            String eid = event.getStr("id");
                                            String score = event.getStr("score");
                                            Integer reTime = event.getInt("reTime");
                                            JSONObject fullCourt = event.getJSONObject("fullCourt");
                                            if (fullCourt == null) {
                                                log.info("角球检测任务执行,平台用户:{},赛事 fullCourt 为空,跳出", admin.getUsername());
                                                continue;
                                            }
                                            JSONObject letBall = fullCourt.getJSONObject("letBall");
                                            if (letBall == null) {
                                                log.info("角球检测任务执行,平台用户:{},赛事 letBall 为空,跳出", admin.getUsername());
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
                                                log.info("角球检测任务执行,平台用户:{},未找到符合 choseTeam={} 的盘口,跳出", admin.getUsername(), desiredChoseTeam);
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
                                                    continue;
                                                }
                                                if (eventId == null || eventId.isEmpty()) {
                                                    log.info("角球检测任务：投注跳过，eventId为空，user={}, 赛事={}", admin.getUsername(), team.getNameB());
                                                    continue;
                                                }
                                                String oddsId = firstOdds.getStr("id");
                                                if (oddsId == null || oddsId.isEmpty()) {
                                                    log.info("角球检测任务：投注跳过，赔率id为空，user={}, 赛事={}, odds={}", admin.getUsername(), team.getNameB(), firstOdds);
                                                    continue;
                                                }
                                                BetAmountDTO amount = settingsService.getBetAmount(admin.getUsername());
                                                LimitDTO limit = settingsBetService.getLimit(admin.getUsername());
                                                IntervalDTO interval = settingsBetService.getInterval(admin.getUsername());
                                                List<TimeFrameDTO> timeFrames = settingsFilterService.getTimeFrames(admin.getUsername());
                                                if (!timeFrames.isEmpty()) {
                                                    TimeFrameDTO timeFrame = timeFrames.get(0);
                                                    if (reTime < timeFrame.getTimeFormSec() || reTime > timeFrame.getTimeToSec()) {
                                                        log.info("角球检测任务：当前赛事时间:{}不在[{}-{}]范围内",
                                                                reTime, timeFrame.getTimeFormSec(), timeFrame.getTimeToSec());
                                                        return;
                                                    }
                                                }
                                                if (amount == null || limit == null) {
                                                    log.info("角球检测任务：投注跳过，配置为空，user={}, amountNull={}, limitNull={}",
                                                            admin.getUsername(), amount == null, limit == null);
                                                    continue;
                                                }

                                                log.info("角球检测任务：配置信息，user={}, amount={}, limit={}",
                                                        admin.getUsername(), amount, limit);
                                                SweepwaterBetDTO sweepwater = new SweepwaterBetDTO();
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
                                                sweepwater.setReTimeA(String.valueOf(reTime));
                                                sweepwater.setHandicapType("letBall");

                                                // 校验投注间隔key
                                                String intervalKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_INTERVAL_PREFIX, "corner", admin.getUsername(), String.valueOf(admin.getSimulateBet()), eid, null);
                                                // 投注次数限制key
                                                String limitKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, "corner", admin.getUsername(), String.valueOf(admin.getSimulateBet()), eid, null);

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

                                                JSONObject betParams = betService.buildBetParams(sweepwater, amount, true, false);
                                                JSONObject retryPreview = betService.buildBetInfo(
                                                        admin.getUsername(), betTeamName, league.getWebsiteIdB(),
                                                        betParams, true, sweepwater, betAmountByOdds
                                                );
                                                if (retryPreview == null) {
                                                    log.info("角球检测任务：预览失败，user={}, 投注队伍={}, 赛事={}, oddsId={}",
                                                            admin.getUsername(), betTeamName, eventId, oddsId);
                                                    limitManager.rollbackReservation(limitKey, reservationId);
                                                    continue; 
                                                }
                                                JSONObject successJson = betService.tryBetCorner(admin.getUsername(), eventId, league.getWebsiteIdB(), true, sweepwater, limit, amount, retryPreview);
                                                if (successJson == null) {
                                                    log.info("角球检测任务：投注返回空，user={}, 投注队伍={}, 赛事={}, oddsId={}",
                                                            admin.getUsername(), betTeamName, eventId, oddsId);
                                                    continue;
                                                }
                                                boolean success = successJson.getBool("success", false);
                                                if (!success) {
                                                    limitManager.rollbackReservation(limitKey, reservationId);
                                                    log.info("角球检测任务：投注失败,用户 {} 赛事A={} 赛事B={} 投注失败", admin.getUsername(), sweepwater.getLeagueNameA(), sweepwater.getLeagueNameB());
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
                                        
                                        // 投注全部失败：回滚lastCornerMinuteByMatch，允许下次重试
                                        if (!betSuccess && isCorner) {
                                            if (originalLastMinute == null) {
                                                lastCornerMinuteByMatch.remove(key);
                                            } else {
                                                lastCornerMinuteByMatch.replace(key, currentMinute, originalLastMinute);
                                            }
                                            lastStateByMatch.remove(key);
                                            log.info("角球检测任务：投注全部失败，已回滚状态，赛事ID={}，赛事名称={}", matchIdA, team.getNameB());
                                        }
                                    }
                                }));
                            }
                            for (Future<?> future : teamFutures) {
                                try {
                                    future.get();
                                } catch (Exception e) {
                                    log.error("角球检测任务：并发处理赛事失败，user={}，leagueIdA={}", admin.getUsername(), league.getLeagueIdA(), e);
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


}

