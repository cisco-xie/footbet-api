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
import com.example.demo.core.SoccerApiInplayTool;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import com.example.demo.model.dto.settings.BetAmountDTO;
import com.example.demo.model.dto.settings.IntervalDTO;
import com.example.demo.model.dto.settings.LimitDTO;
import com.example.demo.model.vo.dict.BindLeagueVO;
import com.example.demo.model.vo.dict.BindTeamVO;
import com.fasterxml.jackson.databind.JsonNode;
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
    private SuccessBasedLimitManager limitManager;

    @Resource
    private BindDictService bindDictService;

    private final SoccerApiInplayTool tool = new SoccerApiInplayTool();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, String> lastStateByMatch = new ConcurrentHashMap<>();

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
            TimeInterval feedTotal = DateUtil.timer();
            JsonNode feed = tool.loadInplayFeed();
            log.info("角球检测任务：inplay 数据源获取结束，总花费:{}毫秒", feedTotal.interval());
            if (feed == null || feed.isNull()) {
                log.warn("角球检测任务：inplay 数据源不可用，本轮结束");
                return;
            }
            List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
            if (adminUsers == null || adminUsers.isEmpty()) {
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
                            SoccerApiInplayTool.MatchDetail detail = tool.fetchMatchDetailByMatchId(matchIdA, feed);
                            if (detail == null) {
                                return;
                            }
                            String stateCode = detail.stateCode();
                            if (stateCode == null) {
                                return;
                            }
                            log.info(
                                    "角球检测任务：赛事详情，赛事ID={}，赛事名称={}，状态码={}，状态名={}，事件时间={}，描述={}",
                                    matchIdA,
                                    team.getNameB(),
                                    stateCode,
                                    detail.stateName(),
                                    detail.eventTime(),
                                    detail.description()
                            );
                            boolean isHomeCornerCode = "11004".equals(stateCode) || "11901".equals(stateCode) || "11902".equals(stateCode);
                            boolean isAwayCornerCode = "21004".equals(stateCode) || "21901".equals(stateCode) || "21902".equals(stateCode);
                            // boolean isNeutralCornerCode = "1004".equals(stateCode);
                            boolean isCorner = isHomeCornerCode || isAwayCornerCode;
                            String key = matchIdA;
                            String last = lastStateByMatch.get(key);
                            if (isCorner) {
                                log.info(
                                        "角球检测任务：发生角球，赛事ID={}，赛事名称={}，状态码={}，状态名={}，事件时间={}，描述={}",
                                        matchIdA,
                                        team.getNameB(),
                                        stateCode,
                                        detail.stateName(),
                                        detail.eventTime(),
                                        detail.description()
                                );
                                lastStateByMatch.put(key, stateCode);
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
                                    boolean lastHome = last.startsWith("11") || last.startsWith("119");
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
                                    return;
                                }
                                // 提取球队名称
                                List<String> names = Collections.singletonList(team.getNameB());
                                Map<String, JSONObject> leagueMap = sweepwaterService.buildLeagueMap(events);
                                // 平台绑定球队赛事对应获取盘口赛事列表
                                JSONObject eventJson = sweepwaterService.findEventByLeagueName(leagueMap, league.getLeagueIdB(), names);
                                if (eventJson == null || eventJson.getJSONArray("events").isEmpty()) {
                                    log.info("角球检测任务执行,平台用户:{},新二网站赔率数据为空,跳出", admin.getUsername());
                                    return;
                                }
                                String eventId = eventJson.getStr("ecid");
                                log.info("角球检测任务执行,平台用户:{},新二网站赔率数据:{}", admin.getUsername(), eventJson);
                                JSONArray eventsJson = eventJson.getJSONArray("events");
                                if (eventsJson == null || eventsJson.isEmpty()) {
                                    log.info("角球检测任务执行,平台用户:{},赛事赔率 events 为空,跳出", admin.getUsername());
                                    return;
                                }
                                JSONObject event = eventsJson.getJSONObject(0);
                                if (event == null) {
                                    log.info("角球检测任务执行,平台用户:{},赛事赔率 event 为空,跳出", admin.getUsername());
                                    return;
                                }
                                String score = event.getStr("score");
                                String reTime = event.getStr("reTime");
                                JSONObject fullCourt = event.getJSONObject("fullCourt");
                                if (fullCourt == null) {
                                    log.info("角球检测任务执行,平台用户:{},赛事 fullCourt 为空,跳出", admin.getUsername());
                                    return;
                                }
                                JSONObject letBall = fullCourt.getJSONObject("letBall");
                                if (letBall == null) {
                                    log.info("角球检测任务执行,平台用户:{},赛事 letBall 为空,跳出", admin.getUsername());
                                    return;
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
                                    return;
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
                                        return;
                                    }
                                    if (eventId == null || eventId.isEmpty()) {
                                        log.info("角球检测任务：投注跳过，eventId为空，user={}, 赛事={}", admin.getUsername(), team.getNameB());
                                        return;
                                    }
                                    String oddsId = firstOdds.getStr("id");
                                    if (oddsId == null || oddsId.isEmpty()) {
                                        log.info("角球检测任务：投注跳过，赔率id为空，user={}, 赛事={}, odds={}", admin.getUsername(), team.getNameB(), firstOdds);
                                        return;
                                    }
                                    BetAmountDTO amount = settingsService.getBetAmount(admin.getUsername());
                                    LimitDTO limit = settingsBetService.getLimit(admin.getUsername());
                                    IntervalDTO interval = settingsBetService.getInterval(admin.getUsername());
                                    if (amount == null || limit == null) {
                                        log.info("角球检测任务：投注跳过，配置为空，user={}, amountNull={}, limitNull={}",
                                                admin.getUsername(), amount == null, limit == null);
                                        return;
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
                                    sweepwater.setReTimeA(reTime);
                                    sweepwater.setHandicapType("letBall");

                                    // 校验投注间隔key
                                    String intervalKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_INTERVAL_PREFIX, "corner", admin.getUsername(), String.valueOf(admin.getSimulateBet()), sweepwater.getEventIdA(), sweepwater.getEventIdB());
                                    // 投注次数限制key
                                    String limitKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, "corner", admin.getUsername(), String.valueOf(admin.getSimulateBet()), sweepwater.getEventIdA(), sweepwater.getEventIdB());

                                    // 这里是校验间隔时间
                                    long intervalMillis = interval.getBetSuccessSec() * 1000L;

                                    // 1. 强制预检查
                                    SuccessBasedLimitManager.EnforcementResult checkResult = limitManager.preCheckAndReserve(
                                            limitKey, intervalKey, sweepwater.getScoreA(), limit, intervalMillis);

                                    // 快速检查：如果明显不满足条件，直接返回
                                    if (!checkResult.isSuccess()) {
                                        log.info("角球检测任务：快速检查不满足: 用户 {}, 联赛:{}, 比分 {}, 原因: {}",
                                                admin.getUsername(), sweepwater.getLeague(), sweepwater.getScoreA(), checkResult.getFailReason());
                                        return;
                                    }
                                    // 保存reservationId，用于后续确认或回滚
                                    String reservationId = checkResult.getReservationId();

                                    JSONObject betParams = betService.buildBetParams(sweepwater, amount, true, false);
                                    JSONObject retryPreview = betService.buildBetInfo(
                                            admin.getUsername(), betTeamName, league.getWebsiteIdB(),
                                            betParams, true, sweepwater
                                    );
                                    if (retryPreview == null) {
                                        log.info("角球检测任务：预览失败，user={}, 投注队伍={}, 赛事={}, oddsId={}",
                                                admin.getUsername(), betTeamName, eventId, oddsId);
                                        // 回滚投注次数
                                        limitManager.rollbackReservation(limitKey, reservationId);
                                        return; // 跳过
                                    }
                                    JSONObject successJson = betService.tryBetCorner(admin.getUsername(), eventId, league.getWebsiteIdB(), true, sweepwater, limit, amount, retryPreview);
                                    if (successJson == null) {
                                        log.info("角球检测任务：投注返回空，user={}, 投注队伍={}, 赛事={}, oddsId={}",
                                                admin.getUsername(), betTeamName, eventId, oddsId);
                                        return;
                                    }
                                    boolean success = successJson.getBool("success", false);
                                    if (!success) {
                                        // 回滚投注次数
                                        limitManager.rollbackReservation(limitKey, reservationId);
                                        log.info("角球检测任务：投注失败,用户 {} 赛事A={} 赛事B={} 投注失败", admin.getUsername(), sweepwater.getLeagueNameA(), sweepwater.getLeagueNameB());
                                        return; // 跳过
                                    } else {
                                        // 记录投注成功到限流管理器（关键步骤）
                                        limitManager.confirmSuccess(limitKey, reservationId);
                                    }
                                    log.info("角球检测任务：投注结果: user={}, 投注队伍={}, eventId={}, oddsId={}, success={}, raw={}",
                                            admin.getUsername(), betTeamName, eventId, oddsId, success, successJson);

                                } catch (Exception e) {
                                    log.info("角球检测任务：执行投注异常，user={}, 赛事={}, eventId={}, teamName={}, odds={}, error={}",
                                            admin.getUsername(), team.getNameB(), eventId, teamNameLabel, firstOdds, e.getMessage());
                                }

                            } else {
                                if (last != null && (last.startsWith("11") || last.startsWith("119") || last.startsWith("21") || last.startsWith("219") || "1004".equals(last))) {
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
                                        boolean lastHome = last.startsWith("11") || last.startsWith("119");
                                        if (lastHome) {
                                            sideLabel = "联赛B主队角球";
                                            teamNameLabel = homeTeam;
                                        } else {
                                            sideLabel = "联赛B客队角球";
                                            teamNameLabel = awayTeam;
                                        }
                                    }
                                    log.info("角球检测任务：发生角球-角球结束触发投注逻辑，联赛B赛事名称: {}，角球队伍: {}，角球方: {}", nameB, teamNameLabel, sideLabel);
                                    lastStateByMatch.remove(key);
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

