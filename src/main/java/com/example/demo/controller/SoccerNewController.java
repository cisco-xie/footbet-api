
package com.example.demo.controller;

import com.example.demo.core.SoccerApiNewTool;
import com.example.demo.core.SoccerApiNewTool.MatchDetail;
import com.example.demo.core.SoccerApiNewTool.MatchSummary;
import com.example.demo.core.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/inplay")
public class SoccerNewController {

    private final SoccerApiNewTool tool = new SoccerApiNewTool();

    // 获取今日赛事列表（根据联赛分组并且过滤掉已完场的赛事）
    @GetMapping("/events")
    public Result getTodayMatchesByLeague() {
        try {
            Map<String, List<MatchSummary>> groupByLeague = tool.fetchTodayMatchesByLeague();
            
            List<Map<String, Object>> data = new ArrayList<>();
            for (Map.Entry<String, List<MatchSummary>> entry : groupByLeague.entrySet()) {
                String league = entry.getKey();
                List<MatchSummary> groupMatches = entry.getValue();
                
                // 对赛事进行排序，按照开赛时间正序排序
                groupMatches.sort((m1, m2) -> m1.matchTime().compareTo(m2.matchTime()));

                List<Map<String, Object>> events = new ArrayList<>();
                for (MatchSummary m : groupMatches) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("id", m.id());
                    ev.put("name", m.homeName() + " -vs- " + m.awayName());
                    events.add(ev);
                }
                
                Map<String, Object> leagueBlock = new LinkedHashMap<>();
                leagueBlock.put("id", groupMatches.isEmpty() ? "" : groupMatches.get(0).leagueId());
                leagueBlock.put("league", league);
                leagueBlock.put("events", events);
                data.add(leagueBlock);
            }
            
            return Result.success(data);
        } catch (Exception e) {
            log.error("获取今日赛事列表失败", e);
            return Result.failed(500, "获取今日赛事列表失败");
        }
    }

    // 根据id查看单场赛事趋势详情
    @GetMapping("/match/{matchId}")
    public Result getMatchDetail(@PathVariable String matchId) {
        try {
            MatchDetail detail = tool.fetchMatchDetailByMatchId(matchId);
            if (detail == null) {
                return Result.failed(404, "未找到该赛事");
            }
            
            Map<String, Object> data = new LinkedHashMap<>();
            
            // 赛事基本信息
            MatchSummary summary = detail.summary();
            Map<String, Object> matchInfo = new LinkedHashMap<>();
            matchInfo.put("id", summary.id());
            matchInfo.put("leagueName", summary.leagueName());
            matchInfo.put("homeName", summary.homeName());
            matchInfo.put("awayName", summary.awayName());
            matchInfo.put("homeScore", summary.homeScore());
            matchInfo.put("awayScore", summary.awayScore());
            matchInfo.put("status", summary.status());
            matchInfo.put("minute", summary.minute());
            matchInfo.put("matchTime", summary.matchTime());
            data.put("matchInfo", matchInfo);
            
            // 赛事事件
            data.put("events", detail.events());
            
            // 赛事统计
            data.put("stats", detail.stats());
            
            return Result.success(data);
        } catch (Exception e) {
            log.error("获取赛事详情失败", e);
            return Result.failed(500, "获取赛事详情失败");
        }
    }
}
