package com.example.demo.api;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
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
import com.example.demo.model.dto.settings.OddsScanDTO;
import com.example.demo.model.dto.settings.TypeFilterDTO;
import com.example.demo.model.dto.sweepwater.SweepwaterDTO;
import com.example.demo.model.vo.WebsiteVO;
import com.example.demo.model.vo.dict.BindLeagueVO;
import com.example.demo.model.vo.dict.BindTeamVO;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

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
        TypeFilterDTO typeFilter = settingsBetService.getTypeFilter(username);
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

    public List<SweepwaterDTO> aggregateEventOdds(String username, OddsScanDTO oddsScanDTO, TypeFilterDTO typeFilter, WebsiteVO websiteA, WebsiteVO websiteB, JSONObject eventAJson, JSONObject eventBJson, String bindTeamNameA, String bindTeamNameB, String websiteIdA, String websiteIdB, String leagueIdA, String leagueIdB, String eventIdA, String eventIdB, boolean isHomeA, boolean isHomeB) {
        List<SweepwaterDTO> results = new ArrayList<>();
        // 遍历第一个 JSON 的事件列表
        JSONArray eventsA = eventAJson.getJSONArray("events");
        JSONArray eventsB = eventBJson.getJSONArray("events");
        for (Object eventA : eventsA) {
            JSONObject aJson = (JSONObject) eventA;
            String nameA = aJson.getStr("name");
            String scoreA = aJson.getStr("score");
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
                    processFullCourtOdds(username, oddsScanDTO, fullCourtA, fullCourtB, "fullCourt", nameA, nameB, eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, results, scoreA, scoreB);
                    processFullCourtOdds(username, oddsScanDTO, firstHalfA, firstHalfB, "firstHalf", nameA, nameB, eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, results, scoreA, scoreB);
                }
            }
        }
        return results;
    }

    // 提取的处理逻辑
    private void processFullCourtOdds(String username, OddsScanDTO oddsScanDTO, JSONObject fullCourtA, JSONObject fullCourtB, String courtType, String nameA, String nameB, JSONObject eventAJson, JSONObject eventBJson, String websiteIdA, String websiteIdB, String leagueIdA, String leagueIdB, String eventIdA, String eventIdB, List<SweepwaterDTO> results, String scoreA, String scoreB) {
        for (String key : fullCourtA.keySet()) {
            if (fullCourtB.containsKey(key)) {
                if (("win".equals(key) || "draw".equals(key))) {
                    if (!fullCourtA.isNull(key) && StringUtils.isNotBlank(fullCourtA.getStr(key))) {
                        JSONObject valueAJson = fullCourtA.getJSONObject(key);
                        if (valueAJson.containsKey("odds") && StringUtils.isNotBlank(valueAJson.getStr("odds"))) {
                            double valueA = valueAJson.getDouble("odds");
                            String decimalOddsA = valueAJson.containsKey("decimalOdds") ? valueAJson.getStr("decimalOdds") : null;
                            if (fullCourtB.containsKey(key) && !fullCourtB.getJSONObject(key).isEmpty()) {
                                JSONObject valueBJson = fullCourtB.getJSONObject(key);
                                if (valueBJson.containsKey("odds") && StringUtils.isNotBlank(valueBJson.getStr("odds"))) {
                                    double valueB = valueBJson.getDouble("odds");
                                    double value = valueA + valueB + 2;
                                    String decimalOddsB = valueBJson.containsKey("decimalOdds") ? valueBJson.getStr("decimalOdds") : null;
                                    // 判断赔率是否在指定区间内
                                    if (oddsScanDTO.getWaterLevelFrom() <= value && value <= oddsScanDTO.getWaterLevelTo()) {
                                        SweepwaterDTO sweepwaterDTO = createSweepwaterDTO(valueAJson.getStr("id"), valueBJson.getStr("id"), valueAJson.getStr("selectionId"), valueBJson.getStr("selectionId"), courtType, "draw", eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, nameA, nameB, null, valueA, valueB, value, decimalOddsA, decimalOddsB, scoreA, scoreB,
                                                valueAJson.getStr("oddFType"), valueBJson.getStr("oddFType"), valueAJson.getStr("gtype"), valueBJson.getStr("gtype"), valueAJson.getStr("wtype"), valueBJson.getStr("wtype"), valueAJson.getStr("rtype"), valueBJson.getStr("rtype"), valueAJson.getStr("choseTeam"), valueBJson.getStr("choseTeam"), valueAJson.getStr("con"), valueBJson.getStr("con"), valueAJson.getStr("ratio"), valueBJson.getStr("ratio")
                                        );
                                        results.add(sweepwaterDTO);
                                        // 把投注放在这里的目的是让扫水到数据后马上进行投注，防止因为时间问题导致赔率变更的情况
                                        tryBet(username, sweepwaterDTO);
                                    }
                                    logInfo("平手盘", nameA, key, valueA, nameB, key, valueB, value, oddsScanDTO);
                                }
                            }
                        }
                    }
                } else {
                    // 处理其他盘类型
                    JSONObject letBall1 = fullCourtA.getJSONObject(key);
                    JSONObject letBall2 = fullCourtB.getJSONObject(key);
                    for (String subKey : letBall1.keySet()) {
                        if (StringUtils.isBlank(subKey)) {
                            continue;
                        }
                        JSONObject valueAJson = letBall1.getJSONObject(subKey);
                        if (valueAJson.containsKey("odds") && StringUtils.isNotBlank(valueAJson.getStr("odds"))) {
                            double valueA = valueAJson.getDouble("odds");
                            String decimalOddsA = valueAJson.containsKey("decimalOdds") ? valueAJson.getStr("decimalOdds") : null;
                            if (letBall2.containsKey(subKey)) {
                                JSONObject valueBJson = letBall2.getJSONObject(subKey);
                                if (valueBJson.containsKey("odds") && StringUtils.isNotBlank(valueBJson.getStr("odds"))) {
                                    double valueB = valueBJson.getDouble("odds");
                                    double value = valueA + valueB + 2;
                                    String decimalOddsB = valueBJson.containsKey("decimalOdds") ? valueBJson.getStr("decimalOdds") : null;
                                    // 判断赔率是否在指定区间内
                                    if (oddsScanDTO.getWaterLevelFrom() <= value && value <= oddsScanDTO.getWaterLevelTo()) {
                                        SweepwaterDTO sweepwaterDTO = createSweepwaterDTO(valueAJson.getStr("id"), valueBJson.getStr("id"), valueAJson.getStr("selectionId"), valueBJson.getStr("selectionId"), courtType, key, eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, nameA, nameB, subKey, valueA, valueB, value, decimalOddsA, decimalOddsB, scoreA, scoreB,
                                                valueAJson.getStr("oddFType"), valueBJson.getStr("oddFType"), valueAJson.getStr("gtype"), valueBJson.getStr("gtype"), valueAJson.getStr("wtype"), valueBJson.getStr("wtype"), valueAJson.getStr("rtype"), valueBJson.getStr("rtype"), valueAJson.getStr("choseTeam"), valueBJson.getStr("choseTeam"), valueAJson.getStr("con"), valueBJson.getStr("con"), valueAJson.getStr("ratio"), valueBJson.getStr("ratio")
                                                );
                                        results.add(sweepwaterDTO);
                                        // 把投注放在这里的目的是让扫水到数据后马上进行投注，防止因为时间问题导致赔率变更的情况
                                        tryBet(username, sweepwaterDTO);
                                    }
                                    logInfo("让球盘", nameA, subKey, valueA, nameB, subKey, valueB, value, oddsScanDTO);
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
                                                     double valueA, double valueB, double value, String decimalOddsA, String decimalOddsB, String scoreA, String scoreB,
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

        sweepwaterDTO.setCreateTime(LocalDateTime.now());

        return sweepwaterDTO;
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
