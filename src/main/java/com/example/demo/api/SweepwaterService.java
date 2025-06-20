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
import com.example.demo.common.enmu.PingBoOddsFormatType;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.core.holder.SweepWaterThreadPoolHolder;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import com.example.demo.model.dto.settings.*;
import com.example.demo.model.dto.sweepwater.SweepwaterDTO;
import com.example.demo.model.vo.WebsiteVO;
import com.example.demo.model.vo.dict.BindLeagueVO;
import com.example.demo.model.vo.dict.BindTeamVO;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @Resource
    private SweepWaterThreadPoolHolder threadPoolHolder;


    // 赛事缓存
    // private final ConcurrentHashMap<String, CompletableFuture<JSONArray>> ecidFetchFutures = new ConcurrentHashMap<>();
    private final Cache<String, CompletableFuture<JSONArray>> ecidFetchFuturesA =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(5, TimeUnit.SECONDS)  // 缓存 5 秒自动过期
                    .build();
    private final Cache<String, CompletableFuture<JSONArray>> ecidFetchFuturesB =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(5, TimeUnit.SECONDS)  // 缓存 5 秒自动过期
                    .build();

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
        RList<String> list = businessPlatformRedissonClient.getList(key);

        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

        int size = list.size();
        // 获取最后 1000 条（如果不足就全部返回）
        int fromIndex = Math.max(0, size - 1000);
        List<String> latestJsonList = list.range(fromIndex, size - 1);

        return latestJsonList.stream()
                .map(json -> JSONUtil.toBean(json, SweepwaterDTO.class))
                // 可选：按 ID 倒序排列（如果数据本身不是倒序写入）
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
    public void sweepwater(String username, List<AdminLoginDTO> sweepwaterUsers) {
        TimeInterval timerTotal = DateUtil.timer();

        // 扫水专用账号
        String sweepwaterUsername = sweepwaterUsers.get(0).getUsername();
        List<List<BindLeagueVO>> bindLeagueVOList = bindDictService.getAllBindDict(username);

        // 先过滤 bindLeagueVOList，去除 leagueIdA 或 leagueIdB 为空的元素，并过滤事件，剔除 idA 或 idB 为空的事件，剔除无事件的 bindLeagueVO
        bindLeagueVOList = bindLeagueVOList.stream()
                .map(leagueGroup -> leagueGroup.stream()
                        .peek(bindLeagueVO -> {
                            List<BindTeamVO> filteredEvents = bindLeagueVO.getEvents().stream()
                                    .filter(event -> StringUtils.isNotBlank(event.getIdA()) && StringUtils.isNotBlank(event.getIdB()))
                                    .collect(Collectors.toList());
                            bindLeagueVO.setEvents(filteredEvents);
                        })
                        // 剔除没有事件的 bindLeagueVO
                        .filter(bindLeagueVO -> StringUtils.isNotBlank(bindLeagueVO.getLeagueIdA())
                                && StringUtils.isNotBlank(bindLeagueVO.getLeagueIdB())
                                && !bindLeagueVO.getEvents().isEmpty())
                        .collect(Collectors.toList()))
                .filter(leagueGroup -> !leagueGroup.isEmpty())
                .collect(Collectors.toList());


        if (CollUtil.isEmpty(bindLeagueVOList)) {
            log.warn("无球队绑定数据，平台用户:{}", username);
            return;
        }
        ExecutorService configExecutor = threadPoolHolder.getConfigExecutor(); // 单独弄个轻量线程池
        // 并行获取配置项
        CompletableFuture<OddsScanDTO> oddsScanFuture = CompletableFuture.supplyAsync(() -> settingsService.getOddsScan(username), configExecutor);
        CompletableFuture<ProfitDTO> profitFuture = CompletableFuture.supplyAsync(() -> settingsService.getProfit(username), configExecutor);
        CompletableFuture<IntervalDTO> intervalFuture = CompletableFuture.supplyAsync(() -> settingsBetService.getInterval(username), configExecutor);
        CompletableFuture<LimitDTO> limitFuture = CompletableFuture.supplyAsync(() -> settingsBetService.getLimit(username), configExecutor);
        CompletableFuture<TypeFilterDTO> typeFilterFuture = CompletableFuture.supplyAsync(() -> settingsBetService.getTypeFilter(username), configExecutor);
        CompletableFuture<List<OddsRangeDTO>> oddsRangesFuture = CompletableFuture.supplyAsync(() -> settingsFilterService.getOddsRanges(username), configExecutor);
        CompletableFuture<List<TimeFrameDTO>> timeFramesFuture = CompletableFuture.supplyAsync(() -> settingsFilterService.getTimeFrames(username), configExecutor);
        CompletableFuture<List<WebsiteVO>> websitesFuture = CompletableFuture.supplyAsync(() -> websiteService.getWebsites(username), configExecutor);

        try {
            CompletableFuture.allOf(
                    oddsScanFuture, profitFuture, intervalFuture, limitFuture,
                    typeFilterFuture, oddsRangesFuture, timeFramesFuture, websitesFuture
            ).get(1, TimeUnit.SECONDS); // 最多等1秒

            // 获取结果
            OddsScanDTO oddsScan = oddsScanFuture.get();
            ProfitDTO profit = profitFuture.get();
            IntervalDTO interval = intervalFuture.get();
            LimitDTO limit = limitFuture.get();
            TypeFilterDTO typeFilter = typeFilterFuture.get();
            List<OddsRangeDTO> oddsRanges = oddsRangesFuture.get();
            List<TimeFrameDTO> timeFrames = timeFramesFuture.get();
            List<WebsiteVO> websites = websitesFuture.get();

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
            ConcurrentHashMap<String, JSONArray> ecidFetchFuturesA = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, JSONArray> ecidFetchFuturesB = new ConcurrentHashMap<>();

            log.info("sweepwater 开始执行，平台用户:{}", username);

            // 联赛级线程池（外层）
            // ExecutorService leagueExecutor = threadPoolHolder.getLeagueExecutor();
            // 事件级线程池（内层）
            // ExecutorService eventExecutor = threadPoolHolder.getEventExecutor();

            // 统计所有 BindLeagueVO 数量
            int totalBindLeagueCount = bindLeagueVOList.size();
            int totalEventCount = bindLeagueVOList.stream()
                    .flatMap(List::stream) // 拍平成所有 BindLeagueVO
                    .mapToInt(vo -> {
                        List<BindTeamVO> events = vo.getEvents();
                        return events != null ? events.size() : 0;
                    })
                    .sum();

            int cpuCoreCount = Math.min(Runtime.getRuntime().availableProcessors() * 4, 100);
            int corePoolSize = Math.min(totalBindLeagueCount, cpuCoreCount);
            int maxPoolSize = Math.max(totalBindLeagueCount, cpuCoreCount);

            int corePoolSizeEvent = Math.min(totalEventCount, cpuCoreCount);
            int maxPoolSizeEvent = Math.max(totalEventCount, cpuCoreCount);
            // 联赛级线程池（外层）
            ExecutorService leagueExecutor = new ThreadPoolExecutor(
                    corePoolSize, maxPoolSize,
                    0L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(1000),
                    new ThreadFactoryBuilder().setNameFormat("league-pool-%d").build(),
                    new ThreadPoolExecutor.AbortPolicy()
            );

            // 事件级线程池（内层）
            ExecutorService eventExecutor = new ThreadPoolExecutor(
                    corePoolSizeEvent, maxPoolSizeEvent,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(500),
                    new ThreadFactoryBuilder().setNameFormat("event-pool-%d").build(),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            List<CompletableFuture<Void>> leagueFutures = new ArrayList<>();

            for (List<BindLeagueVO> leagueGroup : bindLeagueVOList) {
                for (BindLeagueVO bindLeagueVO : leagueGroup) {
                    leagueFutures.add(CompletableFuture.runAsync(() -> {
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
                                    String ecidA = event.getEcidA();
                                    String ecidB = event.getEcidB();
                                    // 根据网站获取对应盘口的赛事列表
                                    // 并行获取eventsA和eventsB
                                    TimeInterval getEventsTimer = DateUtil.timer();
                                    CompletableFuture<JSONArray> futureA = CompletableFuture.supplyAsync(() ->
                                                    getEventsForEcid(sweepwaterUsername, ecidFetchFuturesA, websiteIdA, bindLeagueVO.getLeagueIdA(), ecidA),
                                            eventExecutor);

                                    CompletableFuture<JSONArray> futureB = CompletableFuture.supplyAsync(() ->
                                                    getEventsForEcid(sweepwaterUsername, ecidFetchFuturesB, websiteIdB, bindLeagueVO.getLeagueIdB(), ecidB),
                                            eventExecutor);

                                    // 等待两个结果都完成
                                    CompletableFuture.allOf(futureA, futureB).join();

                                    JSONArray eventsA = futureA.get();
                                    JSONArray eventsB = futureB.get();

                                    log.info("获取网站A:{}和网站B:{}赔率总耗时: {}ms",
                                            WebsiteType.getById(websiteIdA).getDescription(),
                                            WebsiteType.getById(websiteIdB).getDescription(),
                                            getEventsTimer.interval());

                                    if (eventsA == null || eventsB == null) return;
                                    // 平台绑定球队赛事对应获取盘口赛事列表
                                    JSONObject eventAJson = findEventByLeagueId(eventsA, bindLeagueVO.getLeagueIdA());
                                    JSONObject eventBJson = findEventByLeagueId(eventsB, bindLeagueVO.getLeagueIdB());

                                    if (eventAJson == null || eventBJson == null) return;

                                    aggregateEventOdds(
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
                            }, eventExecutor).orTimeout(30, TimeUnit.SECONDS)
                                    .exceptionally(ex -> {
                                        log.warn("事件任务异常，平台用户:{}，联赛:{}-{}，异常:", username,
                                                bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB(), ex);
                                        return null;
                                    }));
                        }

                        // 等待当前联赛所有事件处理完
                        try {
                            CompletableFuture.allOf(eventFutures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
                        } catch (TimeoutException te) {
                            log.warn("联赛任务超时，平台用户:{}，联赛:{}-{}", username, bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB());
                        } catch (Exception e) {
                            log.error("联赛任务异常，平台用户:{}，联赛:{}-{}，异常:{}", username,
                                    bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB(), e.getMessage(), e);
                        }

                    }, leagueExecutor));
                }
            }

            // 等待所有联赛级任务执行完毕
            try {
                CompletableFuture.allOf(leagueFutures.toArray(new CompletableFuture[0])).get(10, TimeUnit.MINUTES);
            } catch (TimeoutException te) {
                log.warn("扫水主流程执行超时，平台用户:{}", username);
            } catch (Exception e) {
                log.error("扫水主流程执行异常，平台用户:{}，异常信息:{}", username, e.getMessage(), e);
            } finally {
                PriorityTaskExecutor.shutdownExecutor(leagueExecutor);
                PriorityTaskExecutor.shutdownExecutor(eventExecutor);
            }
        } catch (TimeoutException te) {
            log.warn("获取配置超时，平台用户:{}", username);
            return;
        } catch (Exception ex) {
            log.error("获取配置失败，平台用户:{}，异常:{}", username, ex.getMessage(), ex);
            return;
        }
        log.info("扫水结束，平台用户:{}，耗时:{}毫秒", username, timerTotal.interval());
    }

    /**
     * 拉取比赛赔率
     * @param username
     * @param ecidCache
     * @param websiteId
     * @param leagueId
     * @param ecid
     * @return
     */
    public JSONArray getEventsForEcid(String username,
                                      ConcurrentHashMap<String, JSONArray> ecidCache,
                                      String websiteId, String leagueId, String ecid) {
        String cacheKey = StringUtils.isNotBlank(ecid) ? ecid : websiteId;

        return ecidCache.computeIfAbsent(cacheKey, key -> {
            try {
                JSONArray events = (JSONArray) handicapApi.eventsOdds(
                        username,
                        websiteId,
                        StringUtils.isNotBlank(ecid) ? leagueId : null,
                        StringUtils.isNotBlank(ecid) ? ecid : null);
                return events != null ? events : new JSONArray();
            } catch (Exception e) {
                log.error("拉取eventsOdds异常: key={}, 用户={}, 网站={}, 联赛={}, ecid={}",
                        key, username, websiteId, leagueId, ecid, e);
                return new JSONArray();
            }
        });
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

    /**
     * 处理事件赔率聚合
     *
     * @param username 用户名
     * @param oddsScan 赔率扫描配置
     * @param profit 利润配置
     * @param interval 时间间隔配置
     * @param limit 限制配置
     * @param oddsRanges 赔率范围列表
     * @param timeFrames 时间段配置列表
     * @param typeFilter 类型过滤器
     * @param websiteA 网站A配置
     * @param websiteB 网站B配置
     * @param eventAJson 网站A事件JSON
     * @param eventBJson 网站B事件JSON
     * @param bindTeamNameA 绑定队伍A名称
     * @param bindTeamNameB 绑定队伍B名称
     * @param websiteIdA 网站A ID
     * @param websiteIdB 网站B ID
     * @param leagueIdA 联赛A ID
     * @param leagueIdB 联赛B ID
     * @param eventIdA 事件A ID
     * @param eventIdB 事件B ID
     * @param isHomeA 是否主队A
     * @param isHomeB 是否主队B
     * @return 扫水结果列表
     */
    public List<SweepwaterDTO> aggregateEventOdds(
            String username, OddsScanDTO oddsScan, ProfitDTO profit,
            IntervalDTO interval, LimitDTO limit, List<OddsRangeDTO> oddsRanges,
            List<TimeFrameDTO> timeFrames, TypeFilterDTO typeFilter,
            WebsiteVO websiteA, WebsiteVO websiteB,
            JSONObject eventAJson, JSONObject eventBJson,
            String bindTeamNameA, String bindTeamNameB,
            String websiteIdA, String websiteIdB,
            String leagueIdA, String leagueIdB,
            String eventIdA, String eventIdB,
            boolean isHomeA, boolean isHomeB) {

        List<SweepwaterDTO> results = new ArrayList<>();
        JSONArray eventsA = eventAJson.getJSONArray("events");
        JSONArray eventsB = eventBJson.getJSONArray("events");

        // 处理网站A的所有事件
        for (Object eventObjA : eventsA) {
            JSONObject eventA = (JSONObject) eventObjA;

            // 处理事件A数据
            EventData processedEventA = processSingleEvent(
                    eventA, websiteA, isHomeA, typeFilter, timeFrames
            );

            // 如果事件不符合时间范围要求则跳过
            if (!processedEventA.isValid()) {
                continue;
            }

            // 处理网站B的所有事件
            for (Object eventObjB : eventsB) {
                JSONObject eventB = (JSONObject) eventObjB;

                // 处理事件B数据
                EventData processedEventB = processSingleEvent(
                        eventB, websiteB, isHomeB, typeFilter, timeFrames
                );

                // 如果事件不符合时间范围要求则跳过
                if (!processedEventB.isValid()) {
                    continue;
                }

                // 检查是否是对立队伍组合
                if (isOppositeTeamCombination(
                        processedEventA.getTeamName(),
                        processedEventB.getTeamName(),
                        bindTeamNameA,
                        bindTeamNameB
                )) {
                    // 处理全场赔率
                    processFullCourtOdds(
                            username, oddsScan, profit, interval, limit, oddsRanges,
                            processedEventA.getFullCourt(), processedEventB.getFullCourt(),
                            "fullCourt",
                            processedEventA.getTeamName(), processedEventB.getTeamName(),
                            eventAJson, eventBJson,
                            websiteIdA, websiteIdB, leagueIdA, leagueIdB,
                            eventIdA, eventIdB, results,
                            processedEventA.getScore(), processedEventB.getScore()
                    );

                    // 处理上半场赔率
                    processFullCourtOdds(
                            username, oddsScan, profit, interval, limit, oddsRanges,
                            processedEventA.getFirstHalf(), processedEventB.getFirstHalf(),
                            "firstHalf",
                            processedEventA.getTeamName(), processedEventB.getTeamName(),
                            eventAJson, eventBJson,
                            websiteIdA, websiteIdB, leagueIdA, leagueIdB,
                            eventIdA, eventIdB, results,
                            processedEventA.getScore(), processedEventB.getScore()
                    );
                }
            }
        }
        return results;
    }

    /**
     * 处理单个事件的数据
     *
     * @param eventJson 事件JSON数据
     * @param website 网站配置
     * @param isHome 是否主队
     * @param typeFilter 类型过滤器
     * @param timeFrames 时间段配置列表
     * @return 处理过的事件数据
     */
    private EventData processSingleEvent(
            JSONObject eventJson, WebsiteVO website,
            boolean isHome, TypeFilterDTO typeFilter,
            List<TimeFrameDTO> timeFrames) {

        // 提取基础信息
        String teamName = eventJson.getStr("name");
        String score = eventJson.getStr("score");
        int reTime = eventJson.getInt("reTime");        // 比赛时长
        String session = eventJson.getStr("session");   // 比赛阶段 1H:上半场，2H:下半场，HT:中场休息

        // 检查时间范围有效性
        boolean isValid = checkTimeFrameValidity(session, reTime, timeFrames);
        if (!isValid) {
            return new EventData(teamName, score, null, null, false);
        }

        // 初始化全场和上半场数据
        JSONObject fullCourt = new JSONObject();
        JSONObject firstHalf = new JSONObject();

        // 处理全场数据
        if (website.getFullCourt() == 1) {
            fullCourt = eventJson.getJSONObject("fullCourt");
            cleanOddsData(fullCourt, website, isHome, typeFilter);
        }

        // 处理上半场数据
        if (website.getFirstHalf() == 1) {
            firstHalf = eventJson.getJSONObject("firstHalf");
            cleanOddsData(firstHalf, website, isHome, typeFilter);
        }

        return new EventData(teamName, score, fullCourt, firstHalf, true);
    }

    /**
     * 检查时间范围有效性
     *
     * @param session 赛事阶段
     * @param reTime 比赛进行时间(秒)
     * @param timeFrames 时间段配置列表
     * @return 是否在有效时间范围内
     */
    private boolean checkTimeFrameValidity(
            String session, int reTime, List<TimeFrameDTO> timeFrames) {

        // 场间休息不处理
        if ("HT".equalsIgnoreCase(session)) {
            return false;
        }

        int courseType;
        if ("1H".equalsIgnoreCase(session)) {
            courseType = 1; // 上半场
        } else if ("2H".equalsIgnoreCase(session)) {
            courseType = 2; // 下半场
        } else {
            // HT 中场休息
            courseType = -1;
        }

        // 非有效阶段直接返回
        if (courseType == -1) {
            return false;
        }

        // 查找对应的时间段配置
        Optional<TimeFrameDTO> timeFrameOpt = timeFrames.stream()
                .filter(w -> w.getBallType() == 1 && w.getCourseType() == courseType)
                .findFirst();

        // 检查时间是否在配置范围内
        if (timeFrameOpt.isPresent()) {
            TimeFrameDTO timeFrame = timeFrameOpt.get();
            if (reTime < timeFrame.getTimeFormSec() || reTime > timeFrame.getTimeToSec()) {
                log.info("当前赛事时间:{}不在[{}-{}]范围内",
                        reTime, timeFrame.getTimeFormSec(), timeFrame.getTimeToSec());
                return false;
            }
        }
        return true;
    }

    /**
     * 清理赔率数据
     *
     * @param oddsData 赔率数据
     * @param website 网站配置
     * @param isHome 是否主队
     * @param typeFilter 类型过滤器
     */
    private void cleanOddsData(
            JSONObject oddsData, WebsiteVO website,
            boolean isHome, TypeFilterDTO typeFilter) {

        // 清理大小球数据
        if ((website.getBigBall() == 0 && isHome) ||
                (website.getSmallBall() == 0 && !isHome)) {
            oddsData.putOpt("overSize", new JSONObject());
        }

        // 清理让球盘数据
        JSONObject letBall = oddsData.getJSONObject("letBall");
        if (letBall != null && !letBall.isEmpty()) {
            // 清理上盘数据
            if (website.getHangingWall() == 0) {
                cleanWallData(letBall, "hanging", oddsData);
            }

            // 清理下盘数据
            if (website.getFootWall() == 0) {
                cleanWallData(letBall, "foot", oddsData);
            }

            // 清理平手盘数据
            if (typeFilter != null &&
                    typeFilter.getFlatPlate() != null &&
                    typeFilter.getFlatPlate() == 1) {
                letBall.remove("0");
            }
        }
    }

    /**
     * 清理特定盘口类型数据
     *
     * @param letBall 让球数据
     * @param wallType 盘口类型 (hanging/foot)
     * @param oddsData 赔率数据
     */
    private void cleanWallData(
            JSONObject letBall, String wallType, JSONObject oddsData) {

        boolean shouldClean = false;
        for (Object value : letBall.values()) {
            JSONObject letBallInfo = JSONUtil.parseObj(value);
            if (letBallInfo.containsKey("wall")) {
                if (wallType.equals(letBallInfo.getStr("wall"))) {
                    shouldClean = true;
                    break;
                }
            }
        }

        if (shouldClean) {
            oddsData.putOpt("letBall", new JSONObject());
        }
    }

    /**
     * 检查是否是对立队伍组合
     *
     * @param teamNameA 队伍A名称
     * @param teamNameB 队伍B名称
     * @param bindNameA 绑定名称A
     * @param bindNameB 绑定名称B
     * @return 是否是对立组合
     */
    private boolean isOppositeTeamCombination(
            String teamNameA, String teamNameB,
            String bindNameA, String bindNameB) {

        return (teamNameA.equals(bindNameA) && !teamNameB.equals(bindNameB)) ||
                (teamNameA.equals(bindNameB) && !teamNameB.equals(bindNameA));
    }

    /**
     * 事件数据封装类
     */
    @Getter
    private static class EventData {
        // Getters
        private final String teamName;
        private final String score;
        private final JSONObject fullCourt;
        private final JSONObject firstHalf;
        private final boolean valid;

        public EventData(String teamName, String score,
                         JSONObject fullCourt, JSONObject firstHalf,
                         boolean valid) {
            this.teamName = teamName;
            this.score = score;
            this.fullCourt = fullCourt;
            this.firstHalf = firstHalf;
            this.valid = valid;
        }

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
                .filter(w -> w.getWebsiteId().equals(websiteIdB))
                .findFirst();

        for (String key : fullCourtA.keySet()) {
            if (fullCourtB.containsKey(key)) {
                if (("win".equals(key) || "draw".equals(key))) {
                    if (!fullCourtA.isNull(key) && StringUtils.isNotBlank(fullCourtA.getStr(key))) {
                        JSONObject valueAJson = fullCourtA.getJSONObject(key);
                        if (valueAJson.containsKey("odds") && StringUtils.isNotBlank(valueAJson.getStr("odds"))) {

                            double valueA = valueAJson.getDouble("odds");
                            if (isInOddsRange(optionalOddsA, valueA)) {
                                log.info("网站A当前赔率赔率:{}不在设定范围内", valueA);
                                continue;
                            }
                            String decimalOddsA = valueAJson.containsKey("decimalOdds") ? valueAJson.getStr("decimalOdds") : null;
                            if (fullCourtB.containsKey(key) && !fullCourtB.getJSONObject(key).isEmpty()) {
                                JSONObject valueBJson = fullCourtB.getJSONObject(key);
                                if (valueBJson.containsKey("odds") && StringUtils.isNotBlank(valueBJson.getStr("odds"))) {

                                    double valueB = valueBJson.getDouble("odds");
                                    if (isInOddsRange(optionalOddsB, valueB)) {
                                        log.info("网站B当前赔率赔率:{}不在设定范围内", valueA);
                                        continue;
                                    }

                                    // 记录网站A的赔率
                                    updateOddsCache(username, valueAJson.getStr("id"), valueA);
                                    // 记录网站B的赔率
                                    updateOddsCache(username, valueBJson.getStr("id"), valueB);
                                    double value = valueA + valueB + 2;
                                    BigDecimal result = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
                                    double finalValue = result.doubleValue();
                                    String decimalOddsB = valueBJson.containsKey("decimalOdds") ? valueBJson.getStr("decimalOdds") : null;
                                    // 判断赔率是否在指定区间内
                                    if (oddsScan.getWaterLevelFrom() <= finalValue && finalValue <= oddsScan.getWaterLevelTo()) {
                                        // 查询缓存看谁的赔率是最新变动的
                                        String oddsKeyA = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, valueAJson.getStr("id"));
                                        String oddsKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, valueBJson.getStr("id"));
                                        Map<String, Boolean> latestChanged = isLatestChanged(oddsKeyA, oddsKeyB);
                                        boolean lastTimeA = latestChanged.get("lastTimeA");
                                        boolean lastTimeB = latestChanged.get("lastTimeB");
                                        SweepwaterDTO sweepwaterDTO = createSweepwaterDTO(valueAJson.getStr("id"), valueBJson.getStr("id"), valueAJson.getStr("selectionId"), valueBJson.getStr("selectionId"), courtType, "draw", eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, nameA, nameB, null, valueA, valueB, finalValue, lastTimeA, lastTimeB, decimalOddsA, decimalOddsB, scoreA, scoreB,
                                                valueAJson.getStr("oddFType"), valueBJson.getStr("oddFType"), valueAJson.getStr("gtype"), valueBJson.getStr("gtype"), valueAJson.getStr("wtype"), valueBJson.getStr("wtype"), valueAJson.getStr("rtype"), valueBJson.getStr("rtype"), valueAJson.getStr("choseTeam"), valueBJson.getStr("choseTeam"), valueAJson.getStr("con"), valueBJson.getStr("con"), valueAJson.getStr("ratio"), valueBJson.getStr("ratio")
                                        );
                                        results.add(sweepwaterDTO);
                                        // String sweepWaterKey = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);
                                        // businessPlatformRedissonClient.getList(sweepWaterKey).add(JSONUtil.toJsonStr(sweepwaterDTO));
                                        // 把投注放在这里的目的是让扫水到数据后马上进行投注，防止因为时间问题导致赔率变更的情况
                                        tryBet(username, sweepwaterDTO);
                                    }
                                    logInfo("平手盘", nameA, key, valueA, nameB, key, valueB, finalValue, oddsScan);
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
                            if (isInOddsRange(optionalOddsA, valueA)) {
                                log.info("网站A当前赔率赔率:{}不在设定范围内", valueA);
                                continue;
                            }
                            String decimalOddsA = valueAJson.containsKey("decimalOdds") ? valueAJson.getStr("decimalOdds") : null;
                            if (letBallB.containsKey(subKey)) {
                                JSONObject valueBJson = letBallB.getJSONObject(subKey);
                                if (valueBJson.containsKey("odds") && StringUtils.isNotBlank(valueBJson.getStr("odds"))) {
                                    double valueB = valueBJson.getDouble("odds");
                                    if (isInOddsRange(optionalOddsB, valueB)) {
                                        log.info("网站B当前赔率赔率:{}不在设定范围内", valueA);
                                        continue;
                                    }
                                    // 记录网站A的赔率
                                    updateOddsCache(username, valueAJson.getStr("id"), valueA);
                                    // 记录网站B的赔率
                                    updateOddsCache(username, valueBJson.getStr("id"), valueB);

                                    double value = valueA + valueB + 2;
                                    BigDecimal result = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
                                    double finalValue = result.doubleValue();
                                    String decimalOddsB = valueBJson.containsKey("decimalOdds") ? valueBJson.getStr("decimalOdds") : null;
                                    // 判断赔率是否在指定区间内
                                    if (oddsScan.getWaterLevelFrom() <= finalValue && finalValue <= oddsScan.getWaterLevelTo()) {
                                        // 查询缓存看谁的赔率是最新变动的
                                        String oddsKeyA = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, valueAJson.getStr("id"));
                                        String oddsKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, valueBJson.getStr("id"));
                                        Map<String, Boolean> latestChanged = isLatestChanged(oddsKeyA, oddsKeyB);
                                        boolean lastTimeA = latestChanged.get("lastTimeA");
                                        boolean lastTimeB = latestChanged.get("lastTimeB");
                                        SweepwaterDTO sweepwaterDTO = createSweepwaterDTO(valueAJson.getStr("id"), valueBJson.getStr("id"), valueAJson.getStr("selectionId"), valueBJson.getStr("selectionId"), courtType, key, eventAJson, eventBJson, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, nameA, nameB, subKey, valueA, valueB, finalValue, lastTimeA, lastTimeB, decimalOddsA, decimalOddsB, scoreA, scoreB,
                                                valueAJson.getStr("oddFType"), valueBJson.getStr("oddFType"), valueAJson.getStr("gtype"), valueBJson.getStr("gtype"), valueAJson.getStr("wtype"), valueBJson.getStr("wtype"), valueAJson.getStr("rtype"), valueBJson.getStr("rtype"), valueAJson.getStr("choseTeam"), valueBJson.getStr("choseTeam"), valueAJson.getStr("con"), valueBJson.getStr("con"), valueAJson.getStr("ratio"), valueBJson.getStr("ratio")
                                                );
                                        results.add(sweepwaterDTO);
                                        // String sweepWaterKey = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);
                                        // businessPlatformRedissonClient.getList(sweepWaterKey).add(JSONUtil.toJsonStr(sweepwaterDTO));
                                        // 把投注放在这里的目的是让扫水到数据后马上进行投注，防止因为时间问题导致赔率变更的情况
                                        log.info("扫水匹配到数据-保存扫水数据");
                                        if ("letBall".equals(key)) {
                                            if (finalValue >= profit.getRollingLetBall()) {
                                                // 满足利润设置的让球盘水位才进行投注
                                                tryBet(username, sweepwaterDTO);
                                            }
                                        } else if ("overSize".equals(key)) {
                                            if (finalValue >= profit.getRollingSize()) {
                                                // 满足利润设置的大小盘水位才进行投注
                                                tryBet(username, sweepwaterDTO);
                                            }
                                        }
                                    }
                                    logInfo("letBall".equals(key) ? "让球盘" : "大小盘", nameA, subKey, valueA, nameB, subKey, valueB, finalValue, oddsScan);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 判断是否在赔率区间
     * @param rangeOpt  赔率范围区间对象
     * @param odds      当前赔率
     * @return
     */
    private boolean isInOddsRange(Optional<OddsRangeDTO> rangeOpt, double odds) {
        return rangeOpt.map(range -> odds >= range.getOddsGreater() && odds <= range.getOddsLess())
                .orElse(false);
    }

    /**
     * 赔率缓存处理
     * @param username      系统用户
     * @param id            赛事id
     * @param newOdds       当前赔率
     */
    private void updateOddsCache(String username, String id, double newOdds) {
        String oddsKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, id);
        RBucket<Object> bucket = businessPlatformRedissonClient.getBucket(oddsKey);
        Object cached = bucket.get();
        long now = System.currentTimeMillis();

        JSONObject oddsJson = new JSONObject();
        oddsJson.putOpt("odds", newOdds);
        oddsJson.putOpt("time", now);

        if (cached == null) {
            bucket.set(oddsJson, Duration.ofHours(2));
        } else {
            JSONObject existing = JSONUtil.parseObj(cached);
            double existingOdds = existing.getDouble("odds");
            if (Math.abs(existingOdds - newOdds) >= 0.00001) {
                bucket.set(oddsJson, Duration.ofHours(2));
            }
        }
    }

    /**
     * 判断两个赔率缓存中，哪一个是最近变动的
     */
    private Map<String, Boolean> isLatestChanged(String oddsKeyA, String oddsKeyB) {
        Object oddsRedisA = businessPlatformRedissonClient.getBucket(oddsKeyA).get();
        Object oddsRedisB = businessPlatformRedissonClient.getBucket(oddsKeyB).get();

        JSONObject oddsJsonA = JSONUtil.parseObj(oddsRedisA);
        JSONObject oddsJsonB = JSONUtil.parseObj(oddsRedisB);

        Long lastOddsTimeA = oddsJsonA.getLong("time", 0L);
        Long lastOddsTimeB = oddsJsonB.getLong("time", 0L);

        Map<String, Boolean> result = new HashMap<>();
        result.put("lastTimeA", lastOddsTimeA > lastOddsTimeB);
        result.put("lastTimeB", lastOddsTimeB > lastOddsTimeA);
        return result;
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
