package com.example.demo.task;

import com.example.demo.core.SoccerApiInplayTool;
import com.example.demo.core.SoccerApiInplayTool.MatchDetail;
import com.example.demo.core.SoccerApiInplayTool.MatchSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class SoccerApiLogTask {

    private final SoccerApiInplayTool tool = new SoccerApiInplayTool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${soccer.demo.inplay.listLimit:50}")
    private int listLimit;

    @Value("${soccer.demo.inplay.printLimit:10}")
    private int printLimit;

    @Value("${soccer.demo.inplay.queryLimit:20}")
    private int queryLimit;

    // 定时打印：赛事列表 -> 按球队名称查询 -> 赛事详情与统计
    @Scheduled(fixedDelayString = "${soccer.demo.inplay.delayMs:30000}")
    public void logInplayMatches() {
        if (!running.compareAndSet(false, true)) return;
        try {
            // 1) 打印进行中的赛事列表
            List<MatchSummary> matches = tool.fetchInplayMatchesInPlay(listLimit);
            log.info("实时足球赛事：本轮获取到进行中赛事 {} 场", matches.size());

            int p = Math.min(matches.size(), Math.max(0, printLimit));
            for (int i = 0; i < p; i++) {
                MatchSummary m = matches.get(i);
                log.info("赛事列表[{}]：比赛ID={}，联赛={}，比分={} {}-{} {}，状态={}，分钟={}",
                        i + 1,
                        m.id(),
                        m.leagueName(),
                        m.homeName(),
                        m.homeScore(),
                        m.awayScore(),
                        m.awayName(),
                        m.status(),
                        m.minute());
            }

            if (matches.isEmpty()) return;

            // 2) 使用第一场比赛主队名做球队名称查询演示
            MatchSummary first = matches.get(0);
            List<MatchSummary> queried = tool.queryMatchesByTeamName(first.homeName(), queryLimit);
            log.info("球队名称查询：关键词='{}'，命中 {} 场", first.homeName(), queried.size());

            int qp = Math.min(queried.size(), Math.max(0, printLimit));
            for (int i = 0; i < qp; i++) {
                MatchSummary m = queried.get(i);
                log.info("查询结果[{}]：比赛ID={}，联赛={}，比分={} {}-{} {}，状态={}，分钟={}",
                        i + 1,
                        m.id(),
                        m.leagueName(),
                        m.homeName(),
                        m.homeScore(),
                        m.awayScore(),
                        m.awayName(),
                        m.status(),
                        m.minute());
            }

            if (queried.isEmpty()) return;

            // 3) 打印第一场命中赛事的详情与实时统计
            MatchDetail detail = tool.fetchMatchDetailByMatchId(queried.get(0).id());
            if (detail == null) return;

            MatchSummary s = detail.summary();
            log.info("赛事详情：比赛ID={}，联赛={}，比分={} {}-{} {}，状态={}，分钟={}，状态码={}，状态名={}，事件时间={}，事件描述={}，统计项数={}",
                    s.id(),
                    s.leagueName(),
                    s.homeName(),
                    s.homeScore(),
                    s.awayScore(),
                    s.awayName(),
                    s.status(),
                    s.minute(),
                    detail.stateCode(),
                    detail.stateName(),
                    detail.eventTime(),
                    detail.description(),
                    detail.stats().size());

            int sp = Math.min(detail.stats().size(), 20);
            for (int i = 0; i < sp; i++) {
                var st = detail.stats().get(i);
                log.info("实时统计[{}]：键={}，名称={}，主队={}，客队={}", i + 1, st.key(), st.label(), st.home(), st.away());
            }
        } catch (Exception e) {
            log.error("实时足球赛事任务执行失败", e);
        } finally {
            running.set(false);
        }
    }
}

