package com.example.demo.api;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import com.example.demo.model.dto.settings.*;
import com.example.demo.model.dto.sweepwater.SweepwaterDTO;
import com.example.demo.model.vo.WebsiteVO;
import com.example.demo.model.vo.dict.BindLeagueVO;
import com.example.demo.model.vo.dict.BindTeamVO;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 扫水
 */
@Slf4j
@Service
public class SweepwaterService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    @Resource
    private BindDictService bindDictService;

    @Resource
    private HandicapApi handicapApi;

    @Resource
    private SettingsService settingsService;
    @Resource
    private SettingsBetService settingsBetService;
    @Resource
    private SettingsFilterService settingsFilterService;

    @Lazy
    @Resource
    private BetService betService;

    @Resource
    private WebsiteService websiteService;

    public void setIsBet(String username, String id) {
        String key = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);

        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        for (int i = 0; i < jsonList.size(); i++) {
            String json = jsonList.get(i);
            SweepwaterDTO dto = JSONUtil.toBean(json, SweepwaterDTO.class);
            if (dto.getId().equals(id)) { // 比如根据 ID 匹配
                dto.setIsBet(1);
                jsonList.set(i, JSONUtil.toJsonStr(dto)); // ✅ 修改这个位置
                break;
            }
        }
    }

    public List<SweepwaterDTO> getSweepwaters(String username) {
        String key = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);

        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return null;
        }

        return jsonList.stream()
                .map(json -> JSONUtil.toBean(json, SweepwaterDTO.class))
                // 排序:id倒叙
                .sorted(Comparator.comparing(SweepwaterDTO::getId).reversed())
                .toList();
    }

    /**
     * 清空扫水列表
     * @param username
     */
    public void delSweepwaters(String username) {
        String key = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);
        businessPlatformRedissonClient.getList(key).delete();
    }

    /**
     * 获取已绑定的球队字典进行扫水比对
     * @param username
     * @return
     */
    public void sweepwater(String username) {
        TimeInterval timerTotal = DateUtil.timer();

        List<List<BindLeagueVO>> bindLeagueVOList = bindDictService.getAllBindDict(username);
        if (CollUtil.isEmpty(bindLeagueVOList)) {
            log.warn("无球队绑定数据，平台用户:{}", username);
            return;
        }

        OddsScanDTO oddsScan = settingsService.getOddsScan(username);
        ProfitDTO profit = settingsService.getProfit(username);
        IntervalDTO interval = settingsBetService.getInterval(username);
        LimitDTO limit = settingsBetService.getLimit(username);
        TypeFilterDTO typeFilter = settingsBetService.getTypeFilter(username);
        List<OddsRangeDTO> oddsRanges = settingsFilterService.getOddsRanges(username);
        List<TimeFrameDTO> timeFrames = settingsFilterService.getTimeFrames(username);
        List<WebsiteVO> websites = websiteService.getWebsites(username);
        // 过滤掉未启用的网站
        websites.removeIf(website -> website.getEnable() == 0);
        if (CollUtil.isEmpty(websites)) {
            log.info("无启用网站，平台用户:{}", username);
            return;
        }

        // 转换为 Map<id, WebsiteVO>
        Map<String, WebsiteVO> websiteMap = websites.stream()
                .collect(Collectors.toMap(WebsiteVO::getId, Function.identity()));
        // 用于记录已获取的 ecid 对应事件，避免重复请求远程 API
        Map<String, JSONArray> fetchedEcidSetA = new ConcurrentHashMap<>();
        Map<String, JSONArray> fetchedEcidSetB = new ConcurrentHashMap<>();

        // 统计所有 BindLeagueVO 数量
        int totalBindLeagueCount = bindLeagueVOList.stream()
                .flatMap(List::stream)
                .filter(vo -> StringUtils.isNotBlank(vo.getLeagueIdA()) && StringUtils.isNotBlank(vo.getLeagueIdB()))
                .mapToInt(vo -> 1)
                .sum();

        int cpuCoreCount = Math.min(Runtime.getRuntime().availableProcessors() * 4, 100);
        int corePoolSize = Math.min(totalBindLeagueCount, cpuCoreCount);
        int maxPoolSize = Math.max(totalBindLeagueCount, cpuCoreCount);

        log.info("sweepwater 开始执行，平台用户:{}，核心线程数:{}，最大线程数:{}", username, corePoolSize, maxPoolSize);

        // 联赛级线程池（外层）
        ExecutorService executorLeagueService = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize,
                0L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactoryBuilder().setNameFormat("league-pool-%d").build(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        // 事件级线程池（内层）
        ExecutorService eventExecutor = new ThreadPoolExecutor(
                20, 40,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                new ThreadFactoryBuilder().setNameFormat("event-pool-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        List<CompletableFuture<Void>> leagueFutures = new ArrayList<>();

        for (List<BindLeagueVO> leagueGroup : bindLeagueVOList) {
            for (BindLeagueVO bindLeagueVO : leagueGroup) {
                leagueFutures.add(CompletableFuture.runAsync(() -> {
                    if (StringUtils.isBlank(bindLeagueVO.getLeagueIdA()) || StringUtils.isBlank(bindLeagueVO.getLeagueIdB())) {
                        return;
                    }

                    String websiteIdA = bindLeagueVO.getWebsiteIdA();
                    String websiteIdB = bindLeagueVO.getWebsiteIdB();

                    if (!websiteMap.containsKey(websiteIdA) || !websiteMap.containsKey(websiteIdB)) {
                        log.info("扫水任务 - 网站idA[{}]idB[{}]存在未启用状态", websiteIdA, websiteIdB);
                        return;
                    }
                    List<CompletableFuture<Void>> eventFutures = new ArrayList<>();

                    for (BindTeamVO event : bindLeagueVO.getEvents()) {
                        eventFutures.add(CompletableFuture.runAsync(() -> {
                            try {
                                if (StringUtils.isBlank(event.getIdA()) || StringUtils.isBlank(event.getIdB())) {
                                    return;
                                }

                                String ecidA = event.getEcidA();
                                String ecidB = event.getEcidB();
                                // 根据网站获取对应盘口的赛事列表
                                JSONArray eventsA = getEventsForEcid(username, fetchedEcidSetA, websiteIdA, bindLeagueVO.getLeagueIdA(), ecidA);
                                JSONArray eventsB = getEventsForEcid(username, fetchedEcidSetB, websiteIdB, bindLeagueVO.getLeagueIdB(), ecidB);

                                if (eventsA == null || eventsB == null) return;
                                // 平台绑定球队赛事对应获取盘口赛事列表
                                JSONObject eventAJson = findEventByLeagueId(eventsA, bindLeagueVO.getLeagueIdA());
                                JSONObject eventBJson = findEventByLeagueId(eventsB, bindLeagueVO.getLeagueIdB());

                                if (eventAJson == null || eventBJson == null) return;

                                List<SweepwaterDTO> results = aggregateEventOdds(
                                        username,
                                        oddsScan,
                                        profit,
                                        interval,
                                        limit,
                                        oddsRanges,
                                        timeFrames,
                                        typeFilter,
                                        websiteMap.get(websiteIdA), websiteMap.get(websiteIdB),
                                        eventAJson, eventBJson,
                                        event.getNameA(), event.getNameB(),
                                        websiteIdA, websiteIdB,
                                        bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB(),
                                        event.getIdA(), event.getIdB(),
                                        event.getIsHomeA(), event.getIsHomeB()
                                );
                            } catch (Exception ex) {
                                log.error("扫水事件处理异常，平台用户:{}，联赛:{} - {}，事件:{} - {}", username,
                                        bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB(),
                                        event.getNameA(), event.getNameB(), ex);
                            }
                        }, eventExecutor));
                    }

                    // 等待当前联赛所有事件处理完
                    CompletableFuture.allOf(eventFutures.toArray(new CompletableFuture[0])).join();

                }, executorLeagueService));
            }
        }

        // 等待所有联赛级任务执行完毕
        try {
            CompletableFuture.allOf(leagueFutures.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            log.error("扫水执行失败，平台用户:{}，异常信息:{}", username, e.getMessage(), e);
        } finally {
            PriorityTaskExecutor.shutdownExecutor(executorLeagueService);
            PriorityTaskExecutor.shutdownExecutor(eventExecutor);
        }

        log.info("扫水结束，平台用户:{}，耗时:{}毫秒", username, timerTotal.interval());
    }

    private JSONArray getEventsForEcid(String username, Map<String, JSONArray> fetchedEcidSet, String websiteId, String leagueId, String ecid) {
        if (StringUtils.isNotBlank(ecid)) {
            // 存在ecid表示这是新二网站的比赛id
            if (fetchedEcidSet.containsKey(ecid)) {
                return fetchedEcidSet.get(ecid);
            } else {
                // 根据ecid获取数据
                try {
                    JSONArray events = (JSONArray) handicapApi.eventsOdds(username, websiteId, leagueId, ecid);
                    if (events != null) {
                        fetchedEcidSet.put(ecid, events);
                    }
                    return events;
                } catch (Exception e) {
                    log.error("拉取eventsOdds异常，ecid={} username={}", ecid, username, e);
                    return null;
                }
            }
        } else {
            if (fetchedEcidSet.containsKey(websiteId)) {
                return fetchedEcidSet.get(websiteId);
            } else {
                // 获取默认的events数据
                try {
                    JSONArray events = (JSONArray) handicapApi.eventsOdds(username, websiteId, null, null);
                    if (events != null) {
                        fetchedEcidSet.put(websiteId, events);
                    }
                    return events;
                } catch (Exception e) {
                    log.error("拉取eventsOdds异常，ecid={} username={}", ecid, username, e);
                    return null;
                }
            }
        }
    }

    private JSONObject findEventByLeagueId(JSONArray events, String leagueId) {
        for (Object eventObj : events) {
            JSONObject eventJson = (JSONObject) eventObj;
            if (leagueId.equals(eventJson.getStr("id"))) {
                return eventJson;
            }
        }
        return null; // 如果没有找到，返回null
    }

    public List<SweepwaterDTO> aggregateEventOdds(String username, OddsScanDTO oddsScan, ProfitDTO profit, IntervalDTO interval, LimitDTO limit, List<OddsRangeDTO> oddsRanges, List<TimeFrameDTO> timeFrames, TypeFilterDTO typeFilter, WebsiteVO websiteA, WebsiteVO websiteB, JSONObject eventAJson, JSONObject eventBJson, String bindTeamNameA, String bindTeamNameB, String websiteIdA, String websiteIdB, String leagueIdA, String leagueIdB, String eventIdA, String eventIdB, boolean isHomeA, boolean isHomeB) {
        List<SweepwaterDTO> results = new ArrayList<>();
        // 遍历第一个 JSON 的事件列表
        JSONArray eventsA = eventAJson.getJSONArray("events");
        JSONArray eventsB = eventBJson.getJSONArray("events");
        for (Object eventA : eventsA) {
            JSONObject aJson = (JSONObject) eventA;
            String nameA = aJson.getStr("name");
            String scoreA = aJson.getStr("score");
            int reTimeA = aJson.getInt("reTime");        // 比赛时长
            String sessionA = aJson.getStr("session");      // 赛事阶段
            // 限制赛事时间范围 start
            if ("HT".equalsIgnoreCase(sessionA)) {
                // 场间休息
            } else if ("1H".equalsIgnoreCase(sessionA)) {
                // 上半场
                Optional<TimeFrameDTO> timeFrameDTOA = timeFrames.stream()
                        .filter(w -> w.getBallType() == 1 && w.getCourseType() == 1)
                        .findFirst();
                if (timeFrameDTOA.isPresent()) {
                    TimeFrameDTO timeFrameA = timeFrameDTOA.get();
                    // 使用 oddsGreaterA
                    if (reTimeA < timeFrameA.getTimeFormSec() || reTimeA > timeFrameA.getTimeToSec()) {
                        log.info("当前赛事时间:{}不在[{}-{}]范围内", reTimeA, timeFrameA.getTimeFormSec(), timeFrameA.getTimeToSec());
                        continue;
                    }
                }
            } else if ("2H".equalsIgnoreCase(sessionA)) {
                // 下半场（即全场）
                Optional<TimeFrameDTO> timeFrameDTOA = timeFrames.stream()
                        .filter(w -> w.getBallType() == 1 && w.getCourseType() == 2)
                        .findFirst();
                if (timeFrameDTOA.isPresent()) {
                    TimeFrameDTO timeFrameA = timeFrameDTOA.get();
                    // 使用 oddsGreaterA
                    if (reTimeA < timeFrameA.getTimeFormSec() || reTimeA > timeFrameA.getTimeToSec()) {
                        log.info("当前赛事时间:{}不在[{}-{}]范围内", reTimeA, timeFrameA.getTimeFormSec(), timeFrameA.getTimeToSec());
                        continue;
                    }
                }
            }
            // 限制赛事时间范围 end
            JSONObject fullCourtA = new JSONObject();
            JSONObject firstHalfA = new JSONObject();
            if (websiteA.getFullCourt() == 1) {
                // 开启全场
                fullCourtA = aJson.getJSONObject("fullCourt");
                if (websiteA.getBigBall() == 0 && isHomeA) {
                    // 关闭大球,主队是大球,直接清空大小球信息
                    fullCourtA.putOpt("overSize", new JSONObject());
                }
                if (websiteA.getSmallBall() == 0 && !isHomeA) {
                    // 关闭小球,客队是小球,直接清空大小球信息
                    fullCourtA.putOpt("overSize", new JSONObject());
                }
                if (websiteA.getHangingWall() == 0) {
                    // 关闭上盘
                    JSONObject letBall = fullCourtA.getJSONObject("letBall");
                    if (letBall != null && !letBall.isEmpty()) {
                        for (Object v : letBall.values()) {
                            JSONObject letBallInfo = JSONUtil.parseObj(v);
                            if (letBallInfo.containsKey("wall") && "hanging".equals(letBallInfo.getStr("wall"))) {
                                fullCourtA.putOpt("letBall", new JSONObject());
                            }
                        }
                    }
                }
                if (websiteA.getFootWall() == 0) {
                    // 关闭下盘
                    JSONObject letBall = fullCourtA.getJSONObject("letBall");
                    if (letBall != null && !letBall.isEmpty()) {
                        for (Object v : letBall.values()) {
                            JSONObject letBallInfo = JSONUtil.parseObj(v);
                            if (letBallInfo.containsKey("wall") && "foot".equals(letBallInfo.getStr("wall"))) {
                                fullCourtA.putOpt("letBall", new JSONObject());
                            }
                        }
                    }
                }
                if (typeFilter.getFlatPlate() == 1) {
                    // 软件设置-投注相关-盘口类型过滤选项  不做·让球盘·平手盘
                    JSONObject letBall = fullCourtA.getJSONObject("letBall");
                    if (letBall != null && !letBall.isEmpty()) {
                        letBall.remove("0");
                    }
                }
            }
            if (websiteA.getFirstHalf() == 1) {
                // 开启上半场
                firstHalfA = aJson.getJSONObject("firstHalf");
                if (websiteA.getBigBall() == 0 && isHomeA) {
                    // 关闭大球,主队是大球,直接清空大小球信息
                    firstHalfA.putOpt("overSize", new JSONObject());
                }
                if (websiteA.getSmallBall() == 0 && !isHomeA) {
                    // 关闭小球,客队是小球,直接清空大小球信息
                    firstHalfA.putOpt("overSize", new JSONObject());
                }
                if (websiteA.getHangingWall() == 0) {
                    // 关闭上盘
                    JSONObject letBall = firstHalfA.getJSONObject("letBall");
                    if (letBall != null && !letBall.isEmpty()) {
                        for (Object v : letBall.values()) {
                            JSONObject letBallInfo = JSONUtil.parseObj(v);
                            if (letBallInfo.containsKey("wall") && "hanging".equals(letBallInfo.getStr("wall"))) {
                                firstHalfA.putOpt("letBall", new JSONObject());
                            }
                        }
                    }
                }
                if (websiteA.getFootWall() == 0) {
                    // 关闭下盘
                    JSONObject letBall = firstHalfA.getJSONObject("letBall");
                    if (letBall != null && !letBall.isEmpty()) {
                        for (Object v : letBall.values()) {
                            JSONObject letBallInfo = JSONUtil.parseObj(v);
                            if (letBallInfo.containsKey("wall") && "foot".equals(letBallInfo.getStr("wall"))) {
                                firstHalfA.putOpt("letBall", new JSONObject());
                            }
                        }
                    }
                }
                if (typeFilter.getFlatPlate() == 1) {
                    // 软件设置-投注相关-盘口类型过滤选项  不做·让球盘·平手盘
                    JSONObject letBall = firstHalfA.getJSONObject("letBall");
                    if (letBall != null && !letBall.isEmpty()) {
                        letBall.remove("0");
                    }
                }
            }

            // 遍历第二个 JSON 的事件列表
            for (Object event2 : eventsB) {
                JSONObject bJson = (JSONObject) event2;
                String nameB = bJson.getStr("name");
                String scoreB = bJson.getStr("score");
                int reTimeB = bJson.getInt("reTime");        // 比赛时长
                String sessionB = bJson.getStr("session");      // 赛事阶段
                // 限制赛事时间范围 start
                if ("HT".equalsIgnoreCase(sessionB)) {
                    // 场间休息
                } else if ("1H".equalsIgnoreCase(sessionB)) {
                    // 上半场
                    Optional<TimeFrameDTO> timeFrameDTOB = timeFrames.stream()
                            .filter(w -> w.getBallType() == 1 && w.getCourseType() == 1)
                            .findFirst();
                    if (timeFrameDTOB.isPresent()) {
                        TimeFrameDTO timeFrameB = timeFrameDTOB.get();
                        // 使用 oddsGreaterA
                        if (reTimeB < timeFrameB.getTimeFormSec() || reTimeB > timeFrameB.getTimeToSec()) {
                            log.info("当前赛事时间:{}不在[{}-{}]范围内", reTimeB, timeFrameB.getTimeFormSec(), timeFrameB.getTimeToSec());
                            continue;
                        }
                    }
                } else if ("2H".equalsIgnoreCase(sessionB)) {
                    // 下半场（即全场）
                    Optional<TimeFrameDTO> timeFrameDTOB = timeFrames.stream()
                            .filter(w -> w.getBallType() == 1 && w.getCourseType() == 2)
                            .findFirst();
                    if (timeFrameDTOB.isPresent()) {
                        TimeFrameDTO timeFrameB = timeFrameDTOB.get();
                        // 使用 oddsGreaterA
                        if (reTimeB < timeFrameB.getTimeFormSec() || reTimeB > timeFrameB.getTimeToSec()) {
                            log.info("当前赛事时间:{}不在[{}-{}]范围内", reTimeB, timeFrameB.getTimeFormSec(), timeFrameB.getTimeToSec());
                            continue;
                        }
                    }
                }
                // 限制赛事时间范围 end
                JSONObject fullCourtB = new JSONObject();
                JSONObject firstHalfB = new JSONObject();
                if (websiteB.getFullCourt() == 1) {
                    // 开启全场
                    fullCourtB = bJson.getJSONObject("fullCourt");
                    if (websiteB.getBigBall() == 0 && isHomeB) {
                        // 关闭大球,主队是大球,直接清空大小球信息
                        fullCourtB.putOpt("overSize", new JSONObject());
                    }
                    if (websiteB.getSmallBall() == 0 && !isHomeB) {
                        // 关闭小球,客队是小球,直接清空大小球信息
                        fullCourtB.putOpt("overSize", new JSONObject());
                    }
                    if (websiteB.getHangingWall() == 0) {
                        // 关闭上盘
                        JSONObject letBall = fullCourtB.getJSONObject("letBall");
                        if (letBall != null && !letBall.isEmpty()) {
                            for (Object v : letBall.values()) {
                                JSONObject letBallInfo = JSONUtil.parseObj(v);
                                if (letBallInfo.containsKey("wall") && "hanging".equals(letBallInfo.getStr("wall"))) {
                                    fullCourtB.putOpt("letBall", new JSONObject());
                                }
                            }
                        }
                    }
                    if (websiteB.getFootWall() == 0) {
                        // 关闭下盘
                        JSONObject letBall = fullCourtB.getJSONObject("letBall");
                        if (letBall != null && !letBall.isEmpty()) {
                            for (Object v : letBall.values()) {
                                JSONObject letBallInfo = JSONUtil.parseObj(v);
                                if (letBallInfo.containsKey("wall") && "foot".equals(letBallInfo.getStr("wall"))) {
                                    fullCourtB.putOpt("letBall", new JSONObject());
                                }
                            }
                        }
                    }
                    if (typeFilter.getFlatPlate() == 1) {
                        // 软件设置-投注相关-盘口类型过滤选项  不做·让球盘·平手盘
                        JSONObject letBall = fullCourtB.getJSONObject("letBall");
                        if (letBall != null && !letBall.isEmpty()) {
                            letBall.remove("0");
                        }
                    }
                }
                if (websiteB.getFirstHalf() == 1) {
                    // 开启上半场
                    firstHalfB = bJson.getJSONObject("firstHalf");
                    if (websiteB.getBigBall() == 0 && isHomeB) {
                        // 关闭大球,主队是大球,直接清空大小球信息
                        firstHalfB.putOpt("overSize", new JSONObject());
                    }
                    if (websiteB.getSmallBall() == 0 && !isHomeB) {
                        // 关闭小球,客队是小球,直接清空大小球信息
                        firstHalfB.putOpt("overSize", new JSONObject());
                    }
                    if (websiteB.getHangingWall() == 0) {
                        // 关闭上盘
                        JSONObject letBall = firstHalfB.getJSONObject("letBall");
                        if (letBall != null && !letBall.isEmpty()) {
                            for (Object v : letBall.values()) {
                                JSONObject letBallInfo = JSONUtil.parseObj(v);
                                if (letBallInfo.containsKey("wall") && "hanging".equals(letBallInfo.getStr("wall"))) {
                                    firstHalfB.putOpt("letBall", new JSONObject());
                                }
                            }
                        }
                    }
                    if (websiteB.getFootWall() == 0) {
                        // 关闭下盘
                        JSONObject letBall = firstHalfB.getJSONObject("letBall");
                        if (letBall != null && !letBall.isEmpty()) {
                            for (Object v : letBall.values()) {
                                JSONObject letBallInfo = JSONUtil.parseObj(v);
                                if (letBallInfo.containsKey("wall") && "foot".equals(letBallInfo.getStr("wall"))) {
                                    firstHalfB.putOpt("letBall", new JSONObject());
                                }
                            }
                        }
                    }
                    if (typeFilter.getFlatPlate() == 1) {
                        // 软件设置-投注相关-盘口类型过滤选项  不做·让球盘·平手盘
                        JSONObject letBall = firstHalfB.getJSONObject("letBall");
                        if (letBall != null && !letBall.isEmpty()) {
                            letBall.remove("0");
                        }
                    }
                }

                // 检查是否是相反的队伍
                if (nameA.equals(bindTeamNameA) && !nameB.equals(bindTeamNameB) ||
                        nameA.equals(bindTeamNameB) && !nameB.equals(bindTeamNameA)) {
                    // 合并 fullCourt 和 firstHalf 数据
                    processFullCourtOdds(username, oddsScan, profit, interval, limit, oddsRanges, fullCourtA, fullCourtB, "fullCourt", nameA, nameB, eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, results, scoreA, scoreB);
                    processFullCourtOdds(username, oddsScan, profit, interval, limit, oddsRanges, firstHalfA, firstHalfB, "firstHalf", nameA, nameB, eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, results, scoreA, scoreB);
                }
            }
        }
        return results;
    }

    // 提取的处理逻辑
    private void processFullCourtOdds(String username, OddsScanDTO oddsScan, ProfitDTO profit, IntervalDTO interval, LimitDTO limit, List<OddsRangeDTO> oddsRanges,
                                      JSONObject fullCourtA, JSONObject fullCourtB, String courtType,
                                      String nameA, String nameB, JSONObject eventAJson, JSONObject eventBJson,
                                      String websiteIdA, String websiteIdB, String leagueIdA, String leagueIdB,
                                      String eventIdA, String eventIdB, List<SweepwaterDTO> results, String scoreA, String scoreB) {
        Optional<OddsRangeDTO> optionalOddsA = oddsRanges.stream()
                .filter(w -> w.getWebsiteId().equals(websiteIdA))
                .findFirst();
        Optional<OddsRangeDTO> optionalOddsB = oddsRanges.stream()
                .filter(w -> w.getWebsiteId().equals(websiteIdA))
                .findFirst();

        for (String key : fullCourtA.keySet()) {
            if (fullCourtB.containsKey(key)) {
                if (("win".equals(key) || "draw".equals(key))) {
                    if (!fullCourtA.isNull(key) && StringUtils.isNotBlank(fullCourtA.getStr(key))) {
                        JSONObject valueAJson = fullCourtA.getJSONObject(key);
                        if (valueAJson.containsKey("odds") && StringUtils.isNotBlank(valueAJson.getStr("odds"))) {

                            // 校验投注间隔
                            String intervalKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_INTERVAL_PREFIX, username, valueAJson.getStr("id"));
                            Object lastBetTimeObj = businessPlatformRedissonClient.getBucket(intervalKey).get();
                            if (lastBetTimeObj != null) {
                                long lastBetTime = Long.parseLong(lastBetTimeObj.toString());
                                if (System.currentTimeMillis() - lastBetTime < interval.getBetSuccessSec() * 1000L) {
                                    log.info("用户 {} 投注间隔未到，eventId={}, 当前时间={}, 上次投注时间={}",
                                            username, valueAJson.getStr("id"), LocalDateTime.now(), Instant.ofEpochMilli(lastBetTime));
                                    continue;
                                }
                            }

                            // 投注次数限制
                            String limitKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, username, valueAJson.getStr("id"));
                            // 校验投注次数限制
                            // 投注前锁定额度（确保不会超过）
                            if (!tryReserveBetLimit(limitKey, scoreA, limit)) {
                                log.info("用户 {} 当前比分 {} 投注次数超限，eventId={}", username, scoreA, valueAJson.getStr("id"));
                                continue;
                            }

                            double valueA = valueAJson.getDouble("odds");
                            if (optionalOddsA.isPresent()) {
                                OddsRangeDTO oddsGreaterA = optionalOddsA.get();
                                // 使用 oddsGreaterA
                                if (valueA < oddsGreaterA.getOddsGreater() || valueA > oddsGreaterA.getOddsLess()) {
                                    log.info("当前赔率赔率:{}不在[{}-{}]范围内", valueA, oddsGreaterA.getOddsGreater(), oddsGreaterA.getOddsLess());
                                    continue;
                                }
                            }
                            String decimalOddsA = valueAJson.containsKey("decimalOdds") ? valueAJson.getStr("decimalOdds") : null;
                            if (fullCourtB.containsKey(key) && !fullCourtB.getJSONObject(key).isEmpty()) {
                                JSONObject valueBJson = fullCourtB.getJSONObject(key);
                                if (valueBJson.containsKey("odds") && StringUtils.isNotBlank(valueBJson.getStr("odds"))) {

                                    // 校验投注间隔
                                    String intervalKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_INTERVAL_PREFIX, username, valueBJson.getStr("id"));
                                    Object lastBetTimeObjB = businessPlatformRedissonClient.getBucket(intervalKeyB).get();
                                    if (lastBetTimeObjB != null) {
                                        long lastBetTime = Long.parseLong(lastBetTimeObjB.toString());
                                        if (System.currentTimeMillis() - lastBetTime < interval.getBetSuccessSec() * 1000L) {
                                            log.info("用户 {} 投注间隔未到，eventId={}, 当前时间={}, 上次投注时间={}",
                                                    username, valueBJson.getStr("id"), LocalDateTime.now(), Instant.ofEpochMilli(lastBetTime));
                                            continue;
                                        }
                                    }

                                    // 投注次数限制
                                    String limitKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, username, valueBJson.getStr("id"));
                                    // 校验投注次数限制
                                    // 投注前锁定额度（确保不会超过）
                                    if (!tryReserveBetLimit(limitKeyB, scoreB, limit)) {
                                        log.info("用户 {} 当前比分 {} 投注次数超限，eventId={}", username, scoreA, valueBJson.getStr("id"));
                                        continue;
                                    }

                                    double valueB = valueBJson.getDouble("odds");
                                    if (optionalOddsB.isPresent()) {
                                        OddsRangeDTO oddsGreaterB = optionalOddsB.get();
                                        // 使用 oddsGreaterB
                                        if (valueB < oddsGreaterB.getOddsGreater() || valueB > oddsGreaterB.getOddsLess()) {
                                            log.info("当前赔率赔率:{}不在[{}-{}]范围内", valueB, oddsGreaterB.getOddsGreater(), oddsGreaterB.getOddsLess());
                                            continue;
                                        }
                                    }

                                    // 记录网站A的赔率
                                    String oddsKeyA = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, valueAJson.getStr("id"));
                                    RBucket<Object> bucketA = businessPlatformRedissonClient.getBucket(oddsKeyA);
                                    Object oddsA = bucketA.get();

                                    long nowTimeA = System.currentTimeMillis();

                                    if (oddsA == null) {
                                        JSONObject oddsJson = new JSONObject();
                                        oddsJson.putOpt("odds", valueA);
                                        oddsJson.putOpt("time", nowTimeA);
                                        bucketA.set(oddsJson, Duration.ofMinutes(10));
                                    } else {
                                        JSONObject oddsJson = JSONUtil.parseObj(oddsA);
                                        double cachedOdds = oddsJson.getDouble("odds");
                                        if (Math.abs(cachedOdds - valueA) >= 0.00001) {
                                            // 赔率变动，进行更新
                                            oddsJson.putOpt("odds", valueA);
                                            oddsJson.putOpt("time", nowTimeA);
                                            bucketA.set(oddsJson, Duration.ofMinutes(10));
                                        }
                                    }

                                    // 记录网站B的赔率
                                    String oddsKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, valueBJson.getStr("id"));
                                    RBucket<Object> bucketB = businessPlatformRedissonClient.getBucket(oddsKeyB);
                                    Object oddsB = bucketB.get();

                                    long nowTimeB = System.currentTimeMillis();

                                    if (oddsB == null) {
                                        JSONObject oddsJson = new JSONObject();
                                        oddsJson.putOpt("odds", valueA);
                                        oddsJson.putOpt("time", nowTimeB);
                                        bucketB.set(oddsJson, Duration.ofMinutes(10));
                                    } else {
                                        JSONObject oddsJson = JSONUtil.parseObj(oddsB);
                                        double cachedOdds = oddsJson.getDouble("odds");
                                        if (Math.abs(cachedOdds - valueA) >= 0.00001) {
                                            // 赔率变动，进行更新
                                            oddsJson.putOpt("odds", valueA);
                                            oddsJson.putOpt("time", nowTimeB);
                                            bucketB.set(oddsJson, Duration.ofMinutes(10));
                                        }
                                    }

                                    double value = valueA + valueB + 2;
                                    String decimalOddsB = valueBJson.containsKey("decimalOdds") ? valueBJson.getStr("decimalOdds") : null;
                                    // 判断赔率是否在指定区间内
                                    if (oddsScan.getWaterLevelFrom() <= value && value <= oddsScan.getWaterLevelTo()) {
                                        // 查询缓存看谁的赔率是最新变动的
                                        Object oddsRedisA = businessPlatformRedissonClient.getBucket(oddsKeyA).get();
                                        Object oddsRedisB = businessPlatformRedissonClient.getBucket(oddsKeyB).get();
                                        JSONObject oddsJsonA = JSONUtil.parseObj(oddsRedisA);
                                        JSONObject oddsJsonB = JSONUtil.parseObj(oddsRedisB);
                                        Long lastOddsTimeA = oddsJsonA.getLong("time");
                                        Long lastOddsTimeB = oddsJsonB.getLong("time");

                                        boolean lastTimeA;
                                        boolean lastTimeB;

                                        if (lastOddsTimeA < lastOddsTimeB) {
                                            lastTimeA = false;
                                            lastTimeB = true;
                                        } else if (lastOddsTimeA > lastOddsTimeB) {
                                            lastTimeA = true;
                                            lastTimeB = false;
                                        } else {
                                            // 时间一样,理论上不太可能
                                            lastTimeA = false;
                                            lastTimeB = false;
                                        }
                                        SweepwaterDTO sweepwaterDTO = createSweepwaterDTO(valueAJson.getStr("id"), valueBJson.getStr("id"), valueAJson.getStr("selectionId"), valueBJson.getStr("selectionId"), courtType, "draw", eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, nameA, nameB, null, valueA, valueB, value, lastTimeA, lastTimeB, decimalOddsA, decimalOddsB, scoreA, scoreB,
                                                valueAJson.getStr("oddFType"), valueBJson.getStr("oddFType"), valueAJson.getStr("gtype"), valueBJson.getStr("gtype"), valueAJson.getStr("wtype"), valueBJson.getStr("wtype"), valueAJson.getStr("rtype"), valueBJson.getStr("rtype"), valueAJson.getStr("choseTeam"), valueBJson.getStr("choseTeam"), valueAJson.getStr("con"), valueBJson.getStr("con"), valueAJson.getStr("ratio"), valueBJson.getStr("ratio")
                                        );
                                        results.add(sweepwaterDTO);
                                        // 把投注放在这里的目的是让扫水到数据后马上进行投注，防止因为时间问题导致赔率变更的情况
                                        tryBet(username, sweepwaterDTO);
                                    }
                                    logInfo("平手盘", nameA, key, valueA, nameB, key, valueB, value, oddsScan);
                                }
                            }
                        }
                    }
                } else {
                    // 处理其他盘类型
                    JSONObject letBallA = fullCourtA.getJSONObject(key);
                    JSONObject letBallB = fullCourtB.getJSONObject(key);
                    for (String subKey : letBallA.keySet()) {
                        if (StringUtils.isBlank(subKey)) {
                            continue;
                        }
                        JSONObject valueAJson = letBallA.getJSONObject(subKey);
                        if (valueAJson.containsKey("odds") && StringUtils.isNotBlank(valueAJson.getStr("odds"))) {
                            double valueA = valueAJson.getDouble("odds");
                            if (optionalOddsA.isPresent()) {
                                OddsRangeDTO oddsGreaterA = optionalOddsA.get();
                                // 使用 oddsGreaterA
                                if (valueA < oddsGreaterA.getOddsGreater() || valueA > oddsGreaterA.getOddsLess()) {
                                    log.info("当前赔率赔率:{}不在[{}-{}]范围内", valueA, oddsGreaterA.getOddsGreater(), oddsGreaterA.getOddsLess());
                                    continue;
                                }
                            }
                            String decimalOddsA = valueAJson.containsKey("decimalOdds") ? valueAJson.getStr("decimalOdds") : null;
                            if (letBallB.containsKey(subKey)) {
                                JSONObject valueBJson = letBallB.getJSONObject(subKey);
                                if (valueBJson.containsKey("odds") && StringUtils.isNotBlank(valueBJson.getStr("odds"))) {
                                    double valueB = valueBJson.getDouble("odds");
                                    if (optionalOddsB.isPresent()) {
                                        OddsRangeDTO oddsGreaterB = optionalOddsB.get();
                                        // 使用 oddsGreaterB
                                        if (valueB < oddsGreaterB.getOddsGreater() || valueB > oddsGreaterB.getOddsLess()) {
                                            log.info("当前赔率赔率:{}不在[{}-{}]范围内", valueB, oddsGreaterB.getOddsGreater(), oddsGreaterB.getOddsLess());
                                            continue;
                                        }
                                    }
                                    // 记录网站A的赔率
                                    String oddsKeyA = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, valueAJson.getStr("id"));
                                    RBucket<Object> bucketA = businessPlatformRedissonClient.getBucket(oddsKeyA);
                                    Object oddsA = bucketA.get();

                                    long nowTimeA = System.currentTimeMillis();

                                    if (oddsA == null) {
                                        JSONObject oddsJson = new JSONObject();
                                        oddsJson.putOpt("odds", valueA);
                                        oddsJson.putOpt("time", nowTimeA);
                                        bucketA.set(oddsJson, Duration.ofMinutes(10));
                                    } else {
                                        JSONObject oddsJson = JSONUtil.parseObj(oddsA);
                                        double cachedOdds = oddsJson.getDouble("odds");
                                        if (Math.abs(cachedOdds - valueA) >= 0.00001) {
                                            // 赔率变动，进行更新
                                            oddsJson.putOpt("odds", valueA);
                                            oddsJson.putOpt("time", nowTimeA);
                                            bucketA.set(oddsJson, Duration.ofMinutes(10));
                                        }
                                    }

                                    // 记录网站B的赔率
                                    String oddsKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, valueBJson.getStr("id"));
                                    RBucket<Object> bucketB = businessPlatformRedissonClient.getBucket(oddsKeyB);
                                    Object oddsB = bucketB.get();

                                    long nowTimeB = System.currentTimeMillis();

                                    if (oddsB == null) {
                                        JSONObject oddsJson = new JSONObject();
                                        oddsJson.putOpt("odds", valueA);
                                        oddsJson.putOpt("time", nowTimeB);
                                        bucketB.set(oddsJson, Duration.ofMinutes(10));
                                    } else {
                                        JSONObject oddsJson = JSONUtil.parseObj(oddsB);
                                        double cachedOdds = oddsJson.getDouble("odds");
                                        if (Math.abs(cachedOdds - valueA) >= 0.00001) {
                                            // 赔率变动，进行更新
                                            oddsJson.putOpt("odds", valueA);
                                            oddsJson.putOpt("time", nowTimeB);
                                            bucketB.set(oddsJson, Duration.ofMinutes(10));
                                        }
                                    }

                                    double value = valueA + valueB + 2;
                                    String decimalOddsB = valueBJson.containsKey("decimalOdds") ? valueBJson.getStr("decimalOdds") : null;
                                    // 判断赔率是否在指定区间内
                                    if (oddsScan.getWaterLevelFrom() <= value && value <= oddsScan.getWaterLevelTo()) {
                                        // 查询缓存看谁的赔率是最新变动的
                                        Object oddsRedisA = businessPlatformRedissonClient.getBucket(oddsKeyA).get();
                                        Object oddsRedisB = businessPlatformRedissonClient.getBucket(oddsKeyB).get();
                                        JSONObject oddsJsonA = JSONUtil.parseObj(oddsRedisA);
                                        JSONObject oddsJsonB = JSONUtil.parseObj(oddsRedisB);
                                        Long lastOddsTimeA = oddsJsonA.getLong("time");
                                        Long lastOddsTimeB = oddsJsonB.getLong("time");

                                        boolean lastTimeA;
                                        boolean lastTimeB;

                                        if (lastOddsTimeA < lastOddsTimeB) {
                                            lastTimeA = false;
                                            lastTimeB = true;
                                        } else if (lastOddsTimeA > lastOddsTimeB) {
                                            lastTimeA = true;
                                            lastTimeB = false;
                                        } else {
                                            // 时间一样,理论上不太可能
                                            lastTimeA = false;
                                            lastTimeB = false;
                                        }
                                        SweepwaterDTO sweepwaterDTO = createSweepwaterDTO(valueAJson.getStr("id"), valueBJson.getStr("id"), valueAJson.getStr("selectionId"), valueBJson.getStr("selectionId"), courtType, key, eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, nameA, nameB, subKey, valueA, valueB, value, lastTimeA, lastTimeB, decimalOddsA, decimalOddsB, scoreA, scoreB,
                                                valueAJson.getStr("oddFType"), valueBJson.getStr("oddFType"), valueAJson.getStr("gtype"), valueBJson.getStr("gtype"), valueAJson.getStr("wtype"), valueBJson.getStr("wtype"), valueAJson.getStr("rtype"), valueBJson.getStr("rtype"), valueAJson.getStr("choseTeam"), valueBJson.getStr("choseTeam"), valueAJson.getStr("con"), valueBJson.getStr("con"), valueAJson.getStr("ratio"), valueBJson.getStr("ratio")
                                                );
                                        results.add(sweepwaterDTO);
                                        // 把投注放在这里的目的是让扫水到数据后马上进行投注，防止因为时间问题导致赔率变更的情况
                                        if ("letBall".equals(key)) {
                                            if (value >= profit.getRollingLetBall()) {
                                                // 满足利润设置的让球盘水位才进行投注
                                                tryBet(username, sweepwaterDTO);
                                            }
                                        } else if ("overSize".equals(key)) {
                                            if (value >= profit.getRollingSize()) {
                                                // 满足利润设置的大小盘水位才进行投注
                                                tryBet(username, sweepwaterDTO);
                                            }
                                        }
                                    }
                                    logInfo("letBall".equals(key) ? "让球盘" : "大小盘", nameA, subKey, valueA, nameB, subKey, valueB, value, oddsScan);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 创建 SweepwaterDTO 对象的简化方法
    private static SweepwaterDTO createSweepwaterDTO(String oddsIdA, String oddsIdB, String selectionIdA, String selectionIdB, String courtType, String handicapType, JSONObject eventAJson, JSONObject eventBJson,
                                                     String websiteIdA, String websiteIdB, String leagueIdA, String leagueIdB, String eventIdA, String eventIdB, String nameA, String nameB, String subKey,
                                                     double valueA, double valueB, double value, boolean lastTimeA, boolean lastTimeB, String decimalOddsA, String decimalOddsB, String scoreA, String scoreB,
                                                     String strongA, String strongB, String gTypeA, String gTypeB, String wTypeA, String wTypeB, String rTypeA, String rTypeB, String choseTeamA, String choseTeamB, String conA, String conB, String ratioA, String ratioB) {
        SweepwaterDTO sweepwaterDTO = new SweepwaterDTO();
        sweepwaterDTO.setId(IdUtil.getSnowflakeNextIdStr());
        sweepwaterDTO.setOddsIdA(oddsIdA);
        sweepwaterDTO.setOddsIdB(oddsIdB);
        sweepwaterDTO.setSelectionIdA(selectionIdA);
        sweepwaterDTO.setSelectionIdB(selectionIdB);
        sweepwaterDTO.setType(courtType);
        sweepwaterDTO.setHandicapType(handicapType);
        sweepwaterDTO.setLeague(eventAJson.getStr("league") + " × " + eventBJson.getStr("league"));
        sweepwaterDTO.setLeagueNameA(eventAJson.getStr("league"));
        sweepwaterDTO.setLeagueNameB(eventBJson.getStr("league"));
        sweepwaterDTO.setProject(WebsiteType.getById(websiteIdA).getDescription() + " × " + WebsiteType.getById(websiteIdB).getDescription());
        sweepwaterDTO.setTeam(nameA + " × " + nameB);
        sweepwaterDTO.setOdds(valueA + " / " + valueB);
        sweepwaterDTO.setOddsA(String.format("%.2f", valueA));
        sweepwaterDTO.setOddsB(String.format("%.2f", valueB));
        sweepwaterDTO.setLastOddsTimeA(lastTimeA);
        sweepwaterDTO.setLastOddsTimeB(lastTimeB);
        sweepwaterDTO.setWater(String.format("%.3f", value));
        sweepwaterDTO.setWebsiteIdA(websiteIdA);
        sweepwaterDTO.setWebsiteIdB(websiteIdB);
        sweepwaterDTO.setWebsiteNameA(WebsiteType.getById(websiteIdA).getDescription());
        sweepwaterDTO.setWebsiteNameB(WebsiteType.getById(websiteIdB).getDescription());
        sweepwaterDTO.setLeagueIdA(leagueIdA);
        sweepwaterDTO.setLeagueIdB(leagueIdB);
        sweepwaterDTO.setEventIdA(eventIdA);
        sweepwaterDTO.setEventIdB(eventIdB);
        sweepwaterDTO.setDecimalOddsA(decimalOddsA);
        sweepwaterDTO.setDecimalOddsB(decimalOddsB);
        sweepwaterDTO.setHandicapA(subKey);
        sweepwaterDTO.setHandicapB(subKey);
        sweepwaterDTO.setScoreA(scoreA);
        sweepwaterDTO.setScoreB(scoreB);
        sweepwaterDTO.setIsBet(0);

        sweepwaterDTO.setStrongA(strongA);
        sweepwaterDTO.setStrongB(strongB);
        sweepwaterDTO.setGTypeA(gTypeA);
        sweepwaterDTO.setGTypeB(gTypeB);
        sweepwaterDTO.setWTypeA(wTypeA);
        sweepwaterDTO.setWTypeB(wTypeB);
        sweepwaterDTO.setRTypeA(rTypeA);
        sweepwaterDTO.setRTypeB(rTypeB);
        sweepwaterDTO.setChoseTeamA(choseTeamA);
        sweepwaterDTO.setChoseTeamB(choseTeamB);
        sweepwaterDTO.setConA(conA);
        sweepwaterDTO.setConB(conB);
        sweepwaterDTO.setRatioA(ratioA);
        sweepwaterDTO.setRatioB(ratioB);

        sweepwaterDTO.setCreateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));

        return sweepwaterDTO;
    }

    /**
     * 校验比分限制和总投注限制
     * 下注前检查 + 锁定额度
     * @param limitKey
     * @param score
     * @param limit
     * @return
     */
    private boolean tryReserveBetLimit(String limitKey, String score, LimitDTO limit) {
        String lockKey = "lock:betlimit:" + limitKey;
        RLock lock = businessPlatformRedissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(2, 5, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取比分限制锁失败，limitKey={}", limitKey);
                return false;
            }

            RBucket<Object> bucket = businessPlatformRedissonClient.getBucket(limitKey);
            JSONObject counterJson = Optional.ofNullable(bucket.get())
                    .map(JSONUtil::parseObj)
                    .orElse(new JSONObject());

            int scoreCount = counterJson.getInt(score, 0);
            int totalCount = counterJson.values().stream()
                    .filter(val -> val instanceof Integer)
                    .mapToInt(val -> (Integer) val)
                    .sum();

            if (scoreCount >= limit.getBetLimitScore() || totalCount >= limit.getBetLimitGame()) {
                return false;
            }

            // 预占额度
            counterJson.putOpt(score, scoreCount + 1);
            bucket.set(counterJson, Duration.ofHours(24));
            return true;

        } catch (Exception e) {
            log.error("预占比分限制失败，limitKey={}, score={}", limitKey, score, e);
            return false;
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    public void setFilter(String username, JSONObject valueAJson, JSONObject valueBJson,
                          OddsScanDTO oddsScan, ProfitDTO profit, List<OddsRangeDTO> oddsRanges, List<TimeFrameDTO> timeFrames, TypeFilterDTO typeFilter,
                          String websiteIdA, String websiteIdB) {
        Optional<OddsRangeDTO> optionalOddsA = oddsRanges.stream()
                .filter(w -> w.getWebsiteId().equals(websiteIdA))
                .findFirst();
        Optional<OddsRangeDTO> optionalOddsB = oddsRanges.stream()
                .filter(w -> w.getWebsiteId().equals(websiteIdA))
                .findFirst();

        double valueA = valueAJson.getDouble("odds");
        if (optionalOddsA.isPresent()) {
            OddsRangeDTO oddsGreaterA = optionalOddsA.get();
            // 使用 oddsGreaterB
            if (valueA < oddsGreaterA.getOddsGreater() || valueA > oddsGreaterA.getOddsLess()) {
                log.info("当前赔率赔率:{}不在[{}-{}]范围内", valueA, oddsGreaterA.getOddsGreater(), oddsGreaterA.getOddsLess());
                return;
            }
        }
        double valueB = valueBJson.getDouble("odds");
        if (optionalOddsB.isPresent()) {
            OddsRangeDTO oddsGreaterB = optionalOddsB.get();
            // 使用 oddsGreaterB
            if (valueB < oddsGreaterB.getOddsGreater() || valueB > oddsGreaterB.getOddsLess()) {
                log.info("当前赔率赔率:{}不在[{}-{}]范围内", valueB, oddsGreaterB.getOddsGreater(), oddsGreaterB.getOddsLess());
                return;
            }
        }
    }

    /**
     * 尝试投注
     * @param username      平台用户名
     * @param sweepwaterDTO 扫水数据
     */
    public void tryBet(String username, SweepwaterDTO sweepwaterDTO) {
        log.info("扫水匹配到数据-保存扫水数据");
        String sweepWaterKey = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);
        businessPlatformRedissonClient.getList(sweepWaterKey).add(JSONUtil.toJsonStr(sweepwaterDTO));
        // 只要扫水有结果就执行下注
        log.info("扫水匹配到数据-进行投注");
        betService.bet(username);
    }

    // 提取的日志输出方法
    private static void logInfo(String handicapType, String nameA, String key, double value1, String nameB, String key2, double value2, double value, OddsScanDTO oddsScanDTO) {
        log.info("比对扫描中,队伍[{}]的{}[{}]赔率是[{}],对比队伍[{}]的{}[{}]赔率[{}],相加结果赔率是[{}],系统设置的区间是[{}]到[{}]", nameA, handicapType, key, value1, nameB, handicapType, key2, value2, value, oddsScanDTO.getWaterLevelFrom(), oddsScanDTO.getWaterLevelTo());
    }
}
