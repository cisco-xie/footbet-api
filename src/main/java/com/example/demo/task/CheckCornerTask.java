package com.example.demo.task;

import com.example.demo.api.AdminService;
import com.example.demo.api.BindDictService;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class CheckCornerTask {

    @Resource
    private SweepwaterService sweepwaterService;

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
                        boolean isNeutralCornerCode = "1004".equals(stateCode);
                        boolean isCorner = isHomeCornerCode || isAwayCornerCode || isNeutralCornerCode;
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

