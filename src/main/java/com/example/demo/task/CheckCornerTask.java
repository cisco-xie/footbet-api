package com.example.demo.task;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.AdminService;
import com.example.demo.api.BindDictService;
import com.example.demo.api.HandicapApi;
import com.example.demo.api.SweepwaterService;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.core.SoccerApiInplayTool;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.dict.BindLeagueVO;
import com.example.demo.model.vo.dict.BindTeamVO;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private BindDictService bindDictService;

    private final SoccerApiInplayTool tool = new SoccerApiInplayTool();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, String> lastStateByMatch = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 1000)
    public void checkCorner() {
        if (!running.compareAndSet(false, true)) {
            log.info("角球检测任务正在运行中，本轮跳过...");
            return;
        }
        log.info("角球检测任务开始执行...");
        try {
            JsonNode feed = tool.loadInplayFeed();
            if (feed == null || feed.isNull()) {
                log.warn("角球检测任务：inplay 数据源不可用，本轮结束");
                return;
            }
            List<AdminLoginDTO> adminUsers = adminService.getUsers(null);
            if (adminUsers == null || adminUsers.isEmpty()) {
                return;
            }
            for (AdminLoginDTO admin : adminUsers) {
                List<BindLeagueVO> bindGroups = bindDictService.getCornerBindDictAll(admin.getUsername());
                if (bindGroups == null || bindGroups.isEmpty()) {
                    continue;
                }
                log.info("角球检测任务：平台用户 {} 有绑定数据，共 {} 条联赛绑定", admin.getUsername(), bindGroups.size());
                for (BindLeagueVO league : bindGroups) {
                    if (!WebsiteType.XINBAO.getId().equals(league.getWebsiteIdB())) {
                        continue;
                    }
                    if (league.getEvents() == null) {
                        continue;
                    }
                    for (BindTeamVO team : league.getEvents()) {
                        if (team.getIdA() == null || team.getIdB() == null) {
                            continue;
                        }
                        String matchIdA = team.getIdA();
                        if (matchIdA == null || matchIdA.isEmpty()) {
                            continue;
                        }
                        log.info("角球检测任务：平台用户 {} 检查赛事:{}", admin.getUsername(), team.getNameB());
                        SoccerApiInplayTool.MatchDetail detail = tool.fetchMatchDetailByMatchId(matchIdA, feed);
                        if (detail == null) {
                            continue;
                        }
                        String stateCode = detail.stateCode();
                        if (stateCode == null) {
                            continue;
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
                                JSONObject targetSide = lastHome ? up : down;
                                if (targetSide == null || targetSide.isEmpty()) {
                                    log.info("角球检测任务执行,平台用户:{},未找到对应 {} 盘口数据,跳出",
                                            admin.getUsername(),
                                            lastHome ? "up(主队)" : "down(客队)");
                                    continue;
                                }
                                JSONObject firstOdds = null;
                                for (Object val : targetSide.values()) {
                                    if (val instanceof JSONObject) {
                                        firstOdds = (JSONObject) val;
                                        break;
                                    }
                                }
                                if (firstOdds == null) {
                                    log.info("角球检测任务执行,平台用户:{},盘口 JSON 结构为空,跳出", admin.getUsername());
                                    continue;
                                }
                                log.info("角球检测任务：选择投注盘口，平台用户:{}，角球方:{}，赔率JSON:{}",
                                        admin.getUsername(),
                                        lastHome ? "主队角球(up)" : "客队角球(down)",
                                        firstOdds);
                            }
                        }

                    }
                }
            }
        } catch (Exception e) {
            log.error("角球检测任务执行失败", e);
        } finally {
            running.set(false);
        }
    }
}

