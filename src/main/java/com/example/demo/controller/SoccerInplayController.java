package com.example.demo.controller;

import com.example.demo.core.result.Result;
import com.example.demo.core.SoccerApiInplayTool;
import com.example.demo.core.SoccerApiInplayTool.MatchSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/inplay")
public class SoccerInplayController {

    private final SoccerApiInplayTool tool = new SoccerApiInplayTool();

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

                List<Map<String, Object>> events = new ArrayList<>();
                for (MatchSummary m : groupMatches) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("id", m.id());
                    ev.put("name", m.homeName() + " -vs- " + m.awayName());
                    events.add(ev);
                }

                Map<String, Object> leagueBlock = new LinkedHashMap<>();
                leagueBlock.put("id", groupMatches.get(0).leagueId());
                leagueBlock.put("league", league);
                leagueBlock.put("events", events);
                data.add(leagueBlock);
            }

            return Result.success(data);
        } catch (Exception e) {
            log.error("赛事列表接口失败", e);
            return Result.failed(500, "获取赛事列表失败");
        }
    }
}

