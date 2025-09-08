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
import com.example.demo.common.utils.ToDayRangeUtil;
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
import io.lettuce.core.RedisException;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    // 用于记录已获取的 ecid 对应事件，避免重复请求远程 API
    private final ConcurrentHashMap<String, CompletableFuture<JSONArray>> ecidFetchFutures = new ConcurrentHashMap<>();
    private final Cache<String, CompletableFuture<JSONArray>> ecidFetchFuturesCacheA =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(5, TimeUnit.SECONDS)  // 缓存 5 秒自动过期
                    .build();
    private final Cache<String, CompletableFuture<JSONArray>> ecidFetchFuturesCacheB =
            CacheBuilder.newBuilder()
                    .expireAfterWrite(5, TimeUnit.SECONDS)  // 缓存 5 秒自动过期
                    .build();

    /**
     * 更新扫水已经投注
     * @param username
     * @param id
     */
    public void setIsBet(String username, String id) {
        final String key = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);
        final RList<String> redisList = businessPlatformRedissonClient.getList(key);

        try {
            // 1. 异步读取Redis列表（带2秒超时）
            List<String> items = redisList.readAllAsync()
                    .toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();

            // 2. 并行查找目标项
            AtomicInteger foundIndex = new AtomicInteger(-1);
            boolean found = IntStream.range(0, items.size()).parallel().anyMatch(i -> {
                try {
                    SweepwaterDTO dto = JSONUtil.toBean(items.get(i), SweepwaterDTO.class);
                    if (id.equals(dto.getId())) {
                        foundIndex.set(i);
                        return true;
                    }
                } catch (Exception e) {
                    log.info("JSON解析失败 [key={}, index={}]", key, i, e);
                }
                return false;
            });

            if (!found) {
                log.info("未找到目标记录 [key={}, id={}]", key, id);
                return;  // 直接退出，不执行后续更新
            }

            // 3. 更新目标项（带重试机制）
            if (foundIndex.get() >= 0) {
                int index = foundIndex.get();
                String originalJson = items.get(index);
                SweepwaterDTO dto = JSONUtil.toBean(originalJson, SweepwaterDTO.class);
                dto.setIsBet(1);
                String updatedJson = JSONUtil.toJsonStr(dto);

                // 带重试的更新逻辑
                int retryCount = 0;
                long waitTime = 100; // 初始等待100ms
                while (retryCount <= 3) { // 最多重试3次
                    try {
                        redisList.setAsync(index, updatedJson)
                                .toCompletableFuture()
                                .orTimeout(1, TimeUnit.SECONDS)
                                .join();
                        log.info("更新成功 [key={}, id={}]", key, id);
                        return;
                    } catch (CompletionException ce) {
                        Throwable cause = ce.getCause();
                        if (cause instanceof TimeoutException) {
                            if (retryCount++ < 3) {
                                log.info("更新超时，准备重试 [key={}, id={}, 第{}/3次]", key, id, retryCount);
                                try {
                                    Thread.sleep(waitTime);
                                    waitTime *= 2; // 指数退避
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    log.info("重试被中断 [key={}]", key);
                                    break;
                                }
                            } else {
                                // 超过重试次数，退出循环
                                break;
                            }
                        } else if (cause instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            log.info("更新操作被中断 [key={}]", key);
                            break;
                        } else {
                            log.info("更新失败 [key={}, id={}]", key, id, ce);
                            break;
                        }
                    } catch (Exception e) {
                        log.info("更新失败 [key={}, id={}]", key, id, e);
                        break;
                    }
                }
                log.info("更新失败，超过最大重试次数 [key={}, id={}]", key, id);
            } else {
                log.info("未找到目标记录 [key={}, id={}]", key, id);
            }
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof TimeoutException) {
                log.info("Redis读取超时 [key={}]", key, ce);
            } else if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                log.info("操作被中断 [key={}]", key);
            } else {
                log.info("Redis读取失败 [key={}]", key, ce);
            }
        } catch (Exception e) {
            log.info("setIsBet执行异常 [key={}]", key, e);
        }
    }

    public List<SweepwaterDTO> getSweepwaters(String username) {
        /*List<String> minuteKeys = ToDayRangeUtil.getRecentMinuteKeys(30);
        for (String minuteKey : minuteKeys) {

        }*/
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
     * 投注扫水 - 主要用于投注账号的扫水，通过读取扫水账号的扫水结果进行对应投注
     * @param sweepwaterUsers
     */
    public void betSweepwater(String username, List<AdminLoginDTO> sweepwaterUsers) {
        TimeInterval timerTotal = DateUtil.timer();
        AdminLoginDTO sweepwaterUser = sweepwaterUsers.get(0);
        String sweepwaterUserName = sweepwaterUser.getUsername();
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
        List<SweepwaterDTO> sweepwaters = getSweepwaters(sweepwaterUserName);
        if (CollUtil.isEmpty(sweepwaters)) {
            log.warn("无公共扫水数据，平台用户:{}", username);
            return;
        }
        for (List<BindLeagueVO> bindLeagues : bindLeagueVOList) {
            for (BindLeagueVO bindLeagueVO : bindLeagues) {
                for (SweepwaterDTO sweepwater : sweepwaters) {
                    if (bindLeagueVO.getWebsiteIdA().contains(sweepwater.getWebsiteIdA())
                            && bindLeagueVO.getWebsiteIdB().contains(sweepwater.getWebsiteIdB())
                            && bindLeagueVO.getLeagueIdA().equals(sweepwater.getLeagueIdA())
                            && bindLeagueVO.getLeagueIdB().equals(sweepwater.getLeagueIdB())
                    ) {
                        for (BindTeamVO bindTeam : bindLeagueVO.getEvents()) {
                            if (bindTeam.getIdA().equals(sweepwater.getEventIdA())) {

                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * TODO 新版扫水
     * 使用专门的扫水账号进行统一扫水，提供公用扫水数据给到其他投注账号
     * @param adminUsers
     * @return
     */
    public void sweepwaterNew(List<AdminLoginDTO> adminUsers, List<AdminLoginDTO> sweepwaterUsers) {
        TimeInterval timerTotal = DateUtil.timer();
        // 最终输出结构
        List<List<BindLeagueVO>> bindLeagueVOAllList = new ArrayList<>();

        // 按 websiteIdA + "_" + websiteIdB 分组
        Map<String, List<BindLeagueVO>> groupMap = new LinkedHashMap<>();

        for (AdminLoginDTO adminUser : adminUsers) {
            List<List<BindLeagueVO>> bindLeagueVOList = bindDictService.getAllBindDict(adminUser.getUsername());

            if (CollUtil.isEmpty(bindLeagueVOList)) {
                log.warn("无球队绑定数据，平台用户:{}", adminUser.getUsername());
                continue;
            }

            for (List<BindLeagueVO> group : bindLeagueVOList) {
                for (BindLeagueVO vo : group) {
                    // 过滤事件（保留 idA 和 idB 都存在的）
                    List<BindTeamVO> filteredEvents = vo.getEvents().stream()
                            .filter(e -> StringUtils.isNotBlank(e.getIdA()) && StringUtils.isNotBlank(e.getIdB()))
                            .collect(Collectors.toList());
                    vo.setEvents(filteredEvents);

                    // 剔除无效 VO
                    if (StringUtils.isBlank(vo.getLeagueIdA()) || StringUtils.isBlank(vo.getLeagueIdB()) || vo.getEvents().isEmpty()) {
                        continue;
                    }

                    // 构造 key 进行分组
                    String key = vo.getWebsiteIdA() + "_" + vo.getWebsiteIdB();
                    groupMap.computeIfAbsent(key, k -> new ArrayList<>()).add(vo);
                }
            }
        }

        // 将分组结果填充到最终结构中，保持 List<List<BindLeagueVO>> 格式
        for (List<BindLeagueVO> groupList : groupMap.values()) {
            if (!groupList.isEmpty()) {
                bindLeagueVOAllList.add(groupList);
            }
        }

        // 扫水专用账号
        String sweepwaterUsername = sweepwaterUsers.get(0).getUsername();
        ExecutorService configExecutor = threadPoolHolder.getConfigExecutor(); // 单独弄个轻量线程池
        // 并行获取配置项
        CompletableFuture<OddsScanDTO> oddsScanFuture = CompletableFuture.supplyAsync(() -> settingsService.getOddsScan(sweepwaterUsername), configExecutor);
        CompletableFuture<ProfitDTO> profitFuture = CompletableFuture.supplyAsync(() -> settingsService.getProfit(sweepwaterUsername), configExecutor);
        CompletableFuture<IntervalDTO> intervalFuture = CompletableFuture.supplyAsync(() -> settingsBetService.getInterval(sweepwaterUsername), configExecutor);
        CompletableFuture<LimitDTO> limitFuture = CompletableFuture.supplyAsync(() -> settingsBetService.getLimit(sweepwaterUsername), configExecutor);
        CompletableFuture<TypeFilterDTO> typeFilterFuture = CompletableFuture.supplyAsync(() -> settingsBetService.getTypeFilter(sweepwaterUsername), configExecutor);
        CompletableFuture<List<OddsRangeDTO>> oddsRangesFuture = CompletableFuture.supplyAsync(() -> settingsFilterService.getOddsRanges(sweepwaterUsername), configExecutor);
        CompletableFuture<List<TimeFrameDTO>> timeFramesFuture = CompletableFuture.supplyAsync(() -> settingsFilterService.getTimeFrames(sweepwaterUsername), configExecutor);
        CompletableFuture<List<WebsiteVO>> websitesFuture = CompletableFuture.supplyAsync(() -> websiteService.getWebsites(sweepwaterUsername), configExecutor);

        try {
            TimeInterval configTimer = DateUtil.timer();
            CompletableFuture.allOf(
                    oddsScanFuture, profitFuture, intervalFuture, limitFuture,
                    typeFilterFuture, oddsRangesFuture, timeFramesFuture, websitesFuture
            ).get(1, TimeUnit.SECONDS); // 最多等1秒
            log.info("sweepwater扫水,获取配置项总耗时: {}ms", configTimer.interval());

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
                log.info("无启用网站");
                return;
            }
            // 转换为 Map<id, WebsiteVO>
            Map<String, WebsiteVO> websiteMap = websites.stream()
                    .collect(Collectors.toMap(WebsiteVO::getId, Function.identity()));

            log.info("sweepwater 开始执行");

            // 联赛级线程池（外层）
            ExecutorService leagueExecutor = threadPoolHolder.getLeagueExecutor();
            // 赛事级线程池（内层）
            ExecutorService eventExecutor = threadPoolHolder.getEventExecutor();
            // 球队赔率级线程池（内层）
            ExecutorService teamOddsExecutor = threadPoolHolder.getTeamOddsExecutor();

            List<CompletableFuture<Void>> leagueFutures = new ArrayList<>();

            for (List<BindLeagueVO> leagueGroup : bindLeagueVOAllList) {
                for (BindLeagueVO bindLeagueVO : leagueGroup) {
                    leagueFutures.add(CompletableFuture.runAsync(() -> {
                        TimeInterval leagueTimer = DateUtil.timer();

                        String websiteIdA = bindLeagueVO.getWebsiteIdA();
                        String websiteIdB = bindLeagueVO.getWebsiteIdB();

                        if (!websiteMap.containsKey(websiteIdA) || !websiteMap.containsKey(websiteIdB)) {
                            log.info("扫水任务 - 网站idA[{}]idB[{}]存在未启用状态", websiteIdA, websiteIdB);
                            return;
                        }

                        if (Thread.currentThread().isInterrupted()) {
                            log.info("联赛任务检测到中断，提前返回");
                            return;
                        }
                        List<CompletableFuture<Void>> eventFutures = new ArrayList<>();

                        List<BindTeamVO> events = bindLeagueVO.getEvents();
                        if (events.size() <= 5) {
                            log.info("扫水任务 - 联赛数量小于5,执行线程池并发模式");
                            // 每场比赛单独任务（保持最大并发）
                            for (BindTeamVO event : events) {
                                eventFutures.add(handleEventAsync(sweepwaterUsername, sweepwaterUsername, bindLeagueVO, event,
                                        oddsScan, profit, interval, limit, oddsRanges, timeFrames, typeFilter,
                                        websiteMap, null, eventExecutor));
                            }
                        } else {
                            log.info("扫水任务 - 联赛数量大于5,执行parallelStream批处理模式");
                            // 批处理方式（一个线程处理一批赛事，内部 parallelStream）
                            eventFutures.add(CompletableFuture.runAsync(() -> {
                                events.parallelStream().forEach(event -> {
                                    handleEventLogic(sweepwaterUsername, sweepwaterUsername, bindLeagueVO, event,
                                            oddsScan, profit, interval, limit, oddsRanges, timeFrames, typeFilter,
                                            websiteMap, null);
                                });
                            }, eventExecutor));
                        }

                        // 等待当前联赛所有事件处理完
                        try {
                            CompletableFuture.allOf(eventFutures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            log.info("联赛任务被中断，退出", ie);
                            Thread.currentThread().interrupt(); // 重新设置中断状态
                        } catch (TimeoutException te) {
                            log.info("联赛任务超时，平台用户:{}，联赛:{}-{}", sweepwaterUsername, bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB());
                        } catch (Exception e) {
                            log.info("联赛任务异常，平台用户:{}，联赛:{}-{}，异常:{}", sweepwaterUsername,
                                    bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB(), e.getMessage(), e);
                        }
                        log.info("sweepwater扫水-联赛级任务,平台用户:{}耗时:{}ms,联赛:{}-{}",
                                sweepwaterUsername, leagueTimer.interval(), bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB());
                    }, leagueExecutor));
                }
            }

            // 等待所有联赛级任务执行完毕
            try {
                CompletableFuture.allOf(leagueFutures.toArray(new CompletableFuture[0])).get(30, TimeUnit.MINUTES);
            } catch (TimeoutException te) {
                log.info("扫水主流程执行超时，平台用户:{}", sweepwaterUsername);
            } catch (Exception e) {
                log.info("扫水主流程执行异常，平台用户:{}，异常信息:{}", sweepwaterUsername, e.getMessage(), e);
            } finally {
                // 使用复用线程池就不需要关闭操作
                /*PriorityTaskExecutor.shutdownExecutor(leagueExecutor);
                PriorityTaskExecutor.shutdownExecutor(eventExecutor);
                PriorityTaskExecutor.shutdownExecutor(teamOddsExecutor);*/
            }
        } catch (TimeoutException te) {
            log.info("获取配置超时，平台用户:{}", sweepwaterUsername);
            return;
        } catch (Exception ex) {
            log.info("获取配置失败，平台用户:{}，异常:{}", sweepwaterUsername, ex.getMessage(), ex);
            return;
        }
        log.info("sweepwater扫水-结束，平台用户:{}，耗时:{}毫秒", sweepwaterUsername, timerTotal.interval());
    }

    /**
     * 获取已绑定的球队字典进行扫水比对
     * @param username
     * @return
     */
    public void sweepwater(String username, List<AdminLoginDTO> sweepwaterUsers, String roundId) {
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
            TimeInterval configTimer = DateUtil.timer();
            CompletableFuture.allOf(
                    oddsScanFuture, profitFuture, intervalFuture, limitFuture,
                    typeFilterFuture, oddsRangesFuture, timeFramesFuture, websitesFuture
            ).get(1, TimeUnit.SECONDS); // 最多等1秒
            log.info("sweepwater扫水-平台用户:{},获取配置项总耗时: {}ms", username, configTimer.interval());

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
            // ConcurrentHashMap<String, CompletableFuture<JSONArray>> ecidFetchFuturesA = ecidFetchFutures;
            // ConcurrentHashMap<String, CompletableFuture<JSONArray>> ecidFetchFuturesB = ecidFetchFutures;

            log.info("sweepwater 开始执行，平台用户:{}", username);

            // 联赛级线程池（外层）
            ExecutorService leagueExecutor = threadPoolHolder.getLeagueExecutor();
            // 赛事级线程池（内层）
            ExecutorService eventExecutor = threadPoolHolder.getEventExecutor();
            // 球队赔率级线程池（内层）
            ExecutorService teamOddsExecutor = threadPoolHolder.getTeamOddsExecutor();

            List<CompletableFuture<Void>> leagueFutures = new ArrayList<>();

            for (List<BindLeagueVO> leagueGroup : bindLeagueVOList) {
                for (BindLeagueVO bindLeagueVO : leagueGroup) {
                    leagueFutures.add(CompletableFuture.runAsync(() -> {
                        TimeInterval leagueTimer = DateUtil.timer();

                        String websiteIdA = bindLeagueVO.getWebsiteIdA();
                        String websiteIdB = bindLeagueVO.getWebsiteIdB();

                        if (!websiteMap.containsKey(websiteIdA) || !websiteMap.containsKey(websiteIdB)) {
                            log.info("扫水任务 - 网站idA[{}]idB[{}]存在未启用状态", websiteIdA, websiteIdB);
                            return;
                        }

                        if (Thread.currentThread().isInterrupted()) {
                            log.info("联赛任务检测到中断，提前返回");
                            return;
                        }
                        List<CompletableFuture<Void>> eventFutures = new ArrayList<>();

                        List<BindTeamVO> events = bindLeagueVO.getEvents();
                        if (events.size() <= 5) {
                            log.info("扫水任务 - 联赛数量小于5,执行线程池并发模式");
                            // 每场比赛单独任务（保持最大并发）
                            for (BindTeamVO event : events) {
                                eventFutures.add(handleEventAsync(username, sweepwaterUsername, bindLeagueVO, event,
                                        oddsScan, profit, interval, limit, oddsRanges, timeFrames, typeFilter,
                                        websiteMap, roundId, eventExecutor));
                            }
                        } else {
                            log.info("扫水任务 - 联赛数量大于5,执行parallelStream批处理模式");
                            // 批处理方式（一个线程处理一批赛事，内部 parallelStream）
                            eventFutures.add(CompletableFuture.runAsync(() -> {
                                events.parallelStream().forEach(event -> {
                                    handleEventLogic(username, sweepwaterUsername, bindLeagueVO, event,
                                            oddsScan, profit, interval, limit, oddsRanges, timeFrames, typeFilter,
                                            websiteMap, roundId);
                                });
                            }, eventExecutor));
                        }

                        // 等待当前联赛所有事件处理完
                        try {
                            CompletableFuture.allOf(eventFutures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            log.info("联赛任务被中断，退出", ie);
                            Thread.currentThread().interrupt(); // 重新设置中断状态
                        } catch (TimeoutException te) {
                            log.info("联赛任务超时，平台用户:{}，联赛:{}-{}", username, bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB());
                        } catch (Exception e) {
                            log.info("联赛任务异常，平台用户:{}，联赛:{}-{}，异常:{}", username,
                                    bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB(), e.getMessage(), e);
                        }
                        log.info("sweepwater扫水-联赛级任务,平台用户:{}耗时:{}ms,联赛:{}-{}",
                                username, leagueTimer.interval(), bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB());
                    }, leagueExecutor));
                }
            }

            // 等待所有联赛级任务执行完毕
            try {
                CompletableFuture.allOf(leagueFutures.toArray(new CompletableFuture[0])).get(30, TimeUnit.MINUTES);
            } catch (TimeoutException te) {
                log.info("扫水主流程执行超时，平台用户:{}", username);
            } catch (Exception e) {
                log.info("扫水主流程执行异常，平台用户:{}，异常信息:{}", username, e.getMessage(), e);
            } finally {
                // 使用复用线程池就不需要关闭操作
                /*PriorityTaskExecutor.shutdownExecutor(leagueExecutor);
                PriorityTaskExecutor.shutdownExecutor(eventExecutor);
                PriorityTaskExecutor.shutdownExecutor(teamOddsExecutor);*/
            }
        } catch (TimeoutException te) {
            log.info("获取配置超时，平台用户:{}", username);
            return;
        } catch (Exception ex) {
            log.info("获取配置失败，平台用户:{}，异常:{}", username, ex.getMessage(), ex);
            return;
        }
        log.info("sweepwater扫水-结束,平台用户:{},轮次id:{},耗时:{}毫秒", username, roundId, timerTotal.interval());
    }

    // todo 每场比赛独立处理的包装
    private CompletableFuture<Void> handleEventAsync(
            String username, String sweepwaterUsername,
            BindLeagueVO bindLeagueVO, BindTeamVO event,
            OddsScanDTO oddsScan, ProfitDTO profit, IntervalDTO interval,
            LimitDTO limit, List<OddsRangeDTO> oddsRanges,
            List<TimeFrameDTO> timeFrames, TypeFilterDTO typeFilter,
            Map<String, WebsiteVO> websiteMap,
            String roundId,
            ExecutorService eventExecutor
    ) {
        return CompletableFuture.runAsync(() -> {
            handleEventLogic(username, sweepwaterUsername, bindLeagueVO, event,
                    oddsScan, profit, interval, limit, oddsRanges, timeFrames, typeFilter,
                    websiteMap, roundId);
        }, eventExecutor).orTimeout(10, TimeUnit.SECONDS).exceptionally(ex -> {
            log.info("sweepwater扫水-事件任务异常，平台用户:{}, 网站A:{}-网站B:{},联赛:{}-{}，异常:",
                    username,
                    WebsiteType.getById(bindLeagueVO.getWebsiteIdA()).getDescription(),
                    WebsiteType.getById(bindLeagueVO.getWebsiteIdB()).getDescription(),
                    bindLeagueVO.getLeagueNameA(), bindLeagueVO.getLeagueNameB(), ex);
            return null;
        });
    }

    // todo 实际赛事赔率处理逻辑
    private void handleEventLogic(
            String username, String sweepwaterUsername,
            BindLeagueVO bindLeagueVO, BindTeamVO event,
            OddsScanDTO oddsScan, ProfitDTO profit, IntervalDTO interval,
            LimitDTO limit, List<OddsRangeDTO> oddsRanges,
            List<TimeFrameDTO> timeFrames, TypeFilterDTO typeFilter,
            Map<String, WebsiteVO> websiteMap, String roundId
    ) {
        try {
            if (Thread.currentThread().isInterrupted()) {
                log.info("球队任务检测到中断，提前返回");
                return;
            }
            String websiteIdA = bindLeagueVO.getWebsiteIdA();
            String websiteIdB = bindLeagueVO.getWebsiteIdB();
            String ecidA = event.getEcidA();
            String ecidB = event.getEcidB();

            // 投注次数限制key
            String limitKeyA = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, username, event.getIdA());
            boolean betLimitA = betService.getBetLimit(limitKeyA, limit);

            // 投注次数限制key
            String limitKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, username, event.getIdB());
            boolean betLimitB = betService.getBetLimit(limitKeyB, limit);
            if (betLimitA && betLimitB) {
                log.info("扫水,网站A:{}-联赛:{},和网站B:{}-联赛:{},的投注总次数都达到限制,不进行扫水和后续操作", WebsiteType.getById(websiteIdA).getDescription(), bindLeagueVO.getLeagueNameA(), WebsiteType.getById(websiteIdB).getDescription(), bindLeagueVO.getLeagueNameB());
                return ;
            }

            log.info("获取赛事数据,平台用户:{},网站A:{},网站B:{}", username,
                    WebsiteType.getById(websiteIdA).getDescription(),
                    WebsiteType.getById(websiteIdB).getDescription());

            TimeInterval getEventsTimer = DateUtil.timer();
            CompletableFuture<JSONArray> futureA = getEventsForEcidAsync(
                    sweepwaterUsername, websiteIdA,
                    bindLeagueVO.getLeagueIdA(), event.getIdA(), roundId, threadPoolHolder.getTeamOddsExecutor());

            CompletableFuture<JSONArray> futureB = getEventsForEcidAsync(
                    sweepwaterUsername, websiteIdB,
                    bindLeagueVO.getLeagueIdB(), event.getIdB(), roundId, threadPoolHolder.getTeamOddsExecutor());

            CompletableFuture.allOf(futureA, futureB).join();

            JSONArray eventsA = futureA.get();
            JSONArray eventsB = futureB.get();

            log.info("平台用户:{},获取网站A:{}和网站B:{}赔率总耗时:{}ms",
                    username, WebsiteType.getById(websiteIdA).getDescription(),
                    WebsiteType.getById(websiteIdB).getDescription(), getEventsTimer.interval());

            if (eventsA.isEmpty()) {
                log.info("扫水,平台用户:{},网站A:{},获取赛事id:{},名称:{},球队id:{},名称:{},赔率失败, 退出",
                        username,
                        WebsiteType.getById(websiteIdA).getDescription(),
                        bindLeagueVO.getLeagueIdA(),
                        bindLeagueVO.getLeagueNameA(),
                        event.getIdA(),
                        event.getNameA()
                );
                return;
            }
            if (eventsB.isEmpty()) {
                log.info("扫水,平台用户:{},网站B:{},获取赛事id:{},名称:{},球队id:{},名称:{},获取赔率失败, 退出",
                        username,
                        WebsiteType.getById(websiteIdB).getDescription(),
                        bindLeagueVO.getLeagueIdB(),
                        bindLeagueVO.getLeagueNameB(),
                        event.getIdB(),
                        event.getNameB()
                );
                return;
            }

            // 提取赛事id
            List<String> idAs = bindLeagueVO.getEvents().stream()
                    .map(BindTeamVO::getIdA)   // 提取 idA
                    .filter(Objects::nonNull) // 可选：过滤掉 null
                    .distinct()               // 去重
                    .toList();
            // 平台绑定球队赛事对应获取盘口赛事列表
            JSONObject eventAJson = findEventByLeagueId(eventsA, bindLeagueVO.getLeagueIdA(), idAs);
            if (eventAJson == null) {
                log.info("扫水, 平台用户: {}, 网站A: {}, 在盘口赛事中未找到对应联绑定的赛事id:{},名称:{},球队id:{},名称:{}, 退出",
                        username,
                        WebsiteType.getById(websiteIdA).getDescription(),
                        bindLeagueVO.getLeagueIdA(),
                        bindLeagueVO.getLeagueNameA(),
                        event.getIdA(),
                        event.getNameA()
                );
                return;
            }

            // 提取赛事id
            List<String> idBs = bindLeagueVO.getEvents().stream()
                    .map(BindTeamVO::getIdB)   // 提取 idB
                    .filter(Objects::nonNull) // 可选：过滤掉 null
                    .distinct()               // 去重
                    .toList();
            JSONObject eventBJson = findEventByLeagueId(eventsB, bindLeagueVO.getLeagueIdB(), idBs);
            if (eventBJson == null) {
                log.info("扫水, 平台用户: {}, 网站B: {}, 在盘口赛事中未找到对应联绑定的赛事id:{},名称:{},球队id:{},名称:{}, 退出",
                        username,
                        WebsiteType.getById(websiteIdB).getDescription(),
                        bindLeagueVO.getLeagueIdB(),
                        bindLeagueVO.getLeagueNameB(),
                        event.getIdB(),
                        event.getNameB()
                );
                return;
            }

            TimeInterval oddsTimer = DateUtil.timer();
            aggregateEventOdds(username, sweepwaterUsername, oddsScan, profit, interval, limit,
                    oddsRanges, timeFrames, typeFilter,
                    websiteMap.get(websiteIdA), websiteMap.get(websiteIdB),
                    eventAJson, eventBJson,
                    event.getNameA(), event.getNameB(),
                    websiteIdA, websiteIdB,
                    bindLeagueVO.getLeagueIdA(), bindLeagueVO.getLeagueIdB(),
                    event.getIdA(), event.getIdB(),
                    event.getIsHomeA(), event.getIsHomeB());

            log.info("sweepwater扫水-聚合赛事赔率,平台用户:{},耗时:{}ms,联赛:{}-{},球队:{}-{}",
                    username, oddsTimer.interval(),
                    bindLeagueVO.getLeagueNameA(), bindLeagueVO.getLeagueNameB(),
                    event.getNameA(), event.getNameB());

        } catch (Exception ex) {
            log.error("sweepwater扫水-事件处理异常，平台用户:{}, 联赛:{}-{}, 球队:{}-{}，异常:",
                    username, bindLeagueVO.getLeagueNameA(), bindLeagueVO.getLeagueNameB(),
                    event.getNameA(), event.getNameB(), ex);
        }
    }

    /**
     * 拉取比赛赔率-同步获取-效率较低
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

        if (ecidCache.containsKey(cacheKey)) {
            log.info("从缓存中获取赔率: 平台用户={}, 网站={}, key={}, 联赛={}, ecid={}",
                    username, WebsiteType.getById(websiteId).getDescription(), cacheKey, leagueId, ecid);
        }

        return ecidCache.computeIfAbsent(cacheKey, key -> {
            log.info("首次拉取赔率，通过 API 获取: 用户={}, 网站={}, key={}, 联赛={}, ecid={}",
                    username, WebsiteType.getById(websiteId).getDescription(), key, leagueId, ecid);
            try {
                JSONArray events = (JSONArray) handicapApi.eventsOdds(
                        username,
                        websiteId,
                        StringUtils.isNotBlank(ecid) ? leagueId : null,
                        StringUtils.isNotBlank(ecid) ? ecid : null);
                return events != null ? events : new JSONArray();
            } catch (Exception e) {
                log.info("拉取eventsOdds异常: key={}, 用户={}, 网站={}, 联赛={}, ecid={}",
                        key, username, websiteId, leagueId, ecid, e);
                return new JSONArray();
            }
        });
    }

    /**
     * 使用异步缓存保存每个 key 的请求任务，避免重复请求
     * @param username 用户名
     * @param websiteId 网站ID
     * @param leagueId 联赛ID
     * @param id        赛事ID
     * @param roundId 轮次id
     * @param teamOddsExecutor 用于请求的线程池
     * @return CompletableFuture<JSONArray>
     */
    public CompletableFuture<JSONArray> getEventsForEcidAsync(
            String username,
            String websiteId, String leagueId, String id, String roundId,
            ExecutorService teamOddsExecutor) {

        WebsiteType website = WebsiteType.getById(websiteId);

        String cacheKey;
        if (website == WebsiteType.SBO) {
            // 盛帆：必须区分 ecid 缓存
            cacheKey = roundId + "-" + websiteId + "-" + id;
        } else {
            // 其他网站：整联赛缓存即可
            cacheKey = roundId + "-" + websiteId;
        }

        if (ecidFetchFutures.containsKey(cacheKey)) {
            log.info("从缓存中获取赔率: 平台用户={}, 网站={}, key={}, 联赛={}, ecid={}",
                    username, website.getDescription(), cacheKey, leagueId, id);
        }

        return ecidFetchFutures.computeIfAbsent(cacheKey, key ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        log.info("首次拉取赔率，通过 API 获取: 用户={}, 网站={}, key={}, 联赛={}, ecid={}",
                                username, website.getDescription(), key, leagueId, id);

                        JSONArray events;
                        if (website == WebsiteType.SBO) {
                            // 盛帆：单赛事拉取
                            events = (JSONArray) handicapApi.eventsOdds(
                                    username, websiteId, null, id);
                        } else {
                            // 其他网站平博和新二：一次拉所有联赛赔率
                            events = (JSONArray) handicapApi.eventsOdds(
                                    username, websiteId, leagueId, null);
                        }

                        return events != null ? events : new JSONArray();
                    } catch (Exception e) {
                        log.error("拉取eventsOdds异常: key={}, 用户={}, 网站={}, 联赛={}, ecid={}",
                                key, username, websiteId, leagueId, id, e);
                        return new JSONArray();
                    }
                }, teamOddsExecutor)
        );
    }

    /**
     * 清空缓存
     */
    public void clearCache(ConcurrentHashMap<String, CompletableFuture<JSONArray>> ecidCache) {
        ecidCache.clear();
    }

    /**
     * 清理当期轮次缓存赔率信息
     * @param roundId
     */
    public void clearCacheForRound(String roundId) {
        log.info("sweepwater扫水-进行清理缓存操作,轮次id:{},准备清理,本轮次网站赔率缓存数:{}", roundId, ecidFetchFutures.size());
        ecidFetchFutures.keySet().removeIf(key -> key.startsWith(roundId + "-"));
        log.info("sweepwater扫水-进行清理缓存操作,轮次id:{},已清理,本轮次网站赔率剩余缓存数:{}", roundId, ecidFetchFutures.size());
    }

    private JSONObject findEventByLeagueId(JSONArray events, String leagueId, List<String> ids) {
        log.info("查找联赛: 联赛={}, leagueId={}", events, leagueId);
        for (Object eventObj : events) {
            JSONObject eventJson = (JSONObject) eventObj;
            if (leagueId.equals(eventJson.getStr("id"))) {
                JSONArray eventArray = eventJson.getJSONArray("events");
                if (eventArray != null) {
                    // 移除 id 不在 ids 列表里的赛事
                    eventArray.removeIf(event -> {
                        JSONObject eventItem = (JSONObject) event;
                        return !ids.contains(eventItem.getStr("id"));
                    });
                }

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
            String username, String sweepwaterUsername, OddsScanDTO oddsScan, ProfitDTO profit,
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
                log.info("扫水,网站A:{}-赛事:{},不符合设置的时间范围:{},跳过", WebsiteType.getById(websiteIdA).getDescription(), eventAJson.getStr("league"), JSONUtil.parseArray(timeFrames));
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
                    log.info("扫水,网站B:{}-赛事:{},不符合设置的时间范围:{},跳过", WebsiteType.getById(websiteIdB).getDescription(), eventBJson.getStr("league"), JSONUtil.parseArray(timeFrames));
                    continue;
                }

                // 检查是否是对立队伍组合
                if (isOppositeTeamCombination(
                        processedEventA.getTeamName(),
                        processedEventB.getTeamName(),
                        bindTeamNameA,
                        bindTeamNameB
                )) {
                    log.info("扫水,网站A:{}-赛事:{}-队伍:{},网站B:{}-赛事:{}-队伍:{},队伍组合符合设置的对立队伍", WebsiteType.getById(websiteIdA).getDescription(), eventAJson.getStr("league"), processedEventA.getTeamName(), WebsiteType.getById(websiteIdB).getDescription(), eventBJson.getStr("league"), processedEventB.getTeamName());
                    // 处理全场赔率
                    processFullCourtOdds(
                            username, sweepwaterUsername, oddsScan, profit, interval, limit, oddsRanges,
                            processedEventA.getFullCourt(), processedEventB.getFullCourt(),
                            "fullCourt",
                            processedEventA.getTeamName(), processedEventB.getTeamName(),
                            eventAJson, eventBJson,
                            eventA, eventB,
                            websiteIdA, websiteIdB, leagueIdA, leagueIdB,
                            eventIdA, eventIdB, results,
                            processedEventA.getScore(), processedEventB.getScore()
                    );

                    // 处理上半场赔率
                    processFullCourtOdds(
                            username, sweepwaterUsername, oddsScan, profit, interval, limit, oddsRanges,
                            processedEventA.getFirstHalf(), processedEventB.getFirstHalf(),
                            "firstHalf",
                            processedEventA.getTeamName(), processedEventB.getTeamName(),
                            eventAJson, eventBJson,
                            eventA, eventB,
                            websiteIdA, websiteIdB, leagueIdA, leagueIdB,
                            eventIdA, eventIdB, results,
                            processedEventA.getScore(), processedEventB.getScore()
                    );
                } else {
                    log.info("扫水,网站A:{}-赛事:{}-队伍:{},网站B:{}-赛事:{}-队伍:{},队伍组合不符合设置的对立队伍,跳过", WebsiteType.getById(websiteIdA).getDescription(), eventAJson.getStr("league"), processedEventA.getTeamName(), WebsiteType.getById(websiteIdB).getDescription(), eventBJson.getStr("league"), processedEventB.getTeamName());
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

        if (timeFrames == null || timeFrames.isEmpty()) {
            return true;
        }
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
    private static boolean isOppositeTeamCombination(
            String teamNameA, String teamNameB,
            String bindNameA, String bindNameB) {

        return (teamNameA.equals(bindNameA) && !teamNameB.equals(bindNameB)) ||
                (teamNameA.equals(bindNameB) && !teamNameB.equals(bindNameA));
    }

    public static void main(String[] args) {
        String[] teamNameAs = Arrays.asList("北部精神", "曼利联").toArray(new String[0]);
        String[] teamNameBs = Arrays.asList("思比瑞特", "曼立联", "圣乔治城", "芒特德瑞特城流浪者", "卧龙岗狼队", "圣乔治").toArray(new String[0]);
        for (String teamNameA : teamNameAs) {
            for (String teamNameB : teamNameBs) {
                System.out.println(isOppositeTeamCombination(teamNameA, teamNameB, teamNameA, teamNameB)); // false
            }
        }
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
    private void processFullCourtOdds(String username, String sweepwaterUsername, OddsScanDTO oddsScan, ProfitDTO profit, IntervalDTO interval, LimitDTO limit, List<OddsRangeDTO> oddsRanges,
                                      JSONObject fullCourtA, JSONObject fullCourtB, String courtType,
                                      String nameA, String nameB, JSONObject eventAJson, JSONObject eventBJson, JSONObject teamA, JSONObject teamB,
                                      String websiteIdA, String websiteIdB, String leagueIdA, String leagueIdB,
                                      String eventIdA, String eventIdB, List<SweepwaterDTO> results, String scoreA, String scoreB) {
        Optional<OddsRangeDTO> optionalOddsA = oddsRanges.stream()
                .filter(w -> w.getWebsiteId().equals(websiteIdA))
                .findFirst();
        Optional<OddsRangeDTO> optionalOddsB = oddsRanges.stream()
                .filter(w -> w.getWebsiteId().equals(websiteIdB))
                .findFirst();
        BetAmountDTO amountDTO = settingsService.getBetAmount(username);
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
                                    // updateOddsCache(username, valueAJson.getStr("id"), valueA);
                                    // 记录网站B的赔率
                                    // updateOddsCache(username, valueBJson.getStr("id"), valueB);
                                    double value = valueA + valueB + 2;
                                    BigDecimal result = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
                                    double finalValue = result.doubleValue();
                                    String decimalOddsB = valueBJson.containsKey("decimalOdds") ? valueBJson.getStr("decimalOdds") : null;
                                    // 判断赔率是否在指定区间内
                                    if (oddsScan.getWaterLevelFrom() <= finalValue && finalValue <= oddsScan.getWaterLevelTo()) {
                                        LocalDateTime now = LocalDateTime.now();
                                        JSONObject betInfoA = null;
                                        JSONObject betInfoB = null;
                                        try {
                                            betInfoA = buildBetInfo(websiteIdA, eventAJson.getJSONArray("events"), teamA, eventAJson.getStr("league"), key, valueAJson, teamA.getBool("isHome"), amountDTO);
                                            betInfoB = buildBetInfo(websiteIdB, eventBJson.getJSONArray("events"), teamB, eventBJson.getStr("league"), key, valueBJson, teamB.getBool("isHome"), amountDTO);
                                        } catch (Exception e) {
                                            log.info("通过赔率自己解析betInfo失败: ", e);
                                        }
                                        // 查询缓存看谁的赔率是最新变动的
                                        String oddsIdKeyA = "";
                                        String oddsIdKeyB = "";
                                        if (WebsiteType.ZHIBO.getId().equals(websiteIdA)) {
                                            oddsIdKeyA = valueAJson.getStr("id");
                                        } else if (WebsiteType.PINGBO.getId().equals(websiteIdA)) {
                                            int lastPipeIndex = valueAJson.getStr("id").lastIndexOf("|");
                                            String suffix = "";
                                            if (teamA.getBool("isHome")) {
                                                suffix = "H";
                                            } else {
                                                suffix = "C";
                                            }
                                            oddsIdKeyA = valueAJson.getStr("id").substring(0, lastPipeIndex + 1) + suffix;
                                        } else {
                                            // 新二网站
                                            oddsIdKeyA = valueAJson.getStr("id") + valueAJson.getStr("choseTeam");
                                        }
                                        if (WebsiteType.ZHIBO.getId().equals(websiteIdB)) {
                                            oddsIdKeyB = valueBJson.getStr("id");
                                        } else if (WebsiteType.PINGBO.getId().equals(websiteIdB)) {
                                            int lastPipeIndex = valueBJson.getStr("id").lastIndexOf("|");
                                            String suffix = "";
                                            if (teamB.getBool("isHome")) {
                                                suffix = "H";
                                            } else {
                                                suffix = "C";
                                            }
                                            oddsIdKeyB = valueBJson.getStr("id").substring(0, lastPipeIndex + 1) + suffix;
                                        } else {
                                            // 新二网站
                                            oddsIdKeyB = valueBJson.getStr("id") + valueBJson.getStr("choseTeam");
                                        }
                                        /*String oddsKeyA = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, sweepwaterUsername, oddsIdKeyA);
                                        String oddsKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, sweepwaterUsername, oddsIdKeyB);
                                        Map<String, Boolean> latestChanged = isLatestChanged(oddsKeyA, oddsKeyB);
                                        boolean lastTimeA = latestChanged.get("lastTimeA");
                                        boolean lastTimeB = latestChanged.get("lastTimeB");*/
                                        String handicapA = valueAJson.containsKey("handicap") ? valueAJson.getStr("handicap") : "";
                                        String handicapB = valueBJson.containsKey("handicap") ? valueBJson.getStr("handicap") : "";
                                        SweepwaterDTO sweepwaterDTO = createSweepwaterDTO(username, valueAJson.getStr("id"), valueBJson.getStr("id"), now, valueAJson.getStr("selectionId"), valueBJson.getStr("selectionId"), courtType, "draw", eventAJson, eventBJson, teamA, teamB, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, nameA, nameB, handicapA, handicapB, valueA, valueB, finalValue, decimalOddsA, decimalOddsB, scoreA, scoreB,
                                                valueAJson.getStr("oddFType"), valueBJson.getStr("oddFType"), valueAJson.getStr("gtype"), valueBJson.getStr("gtype"), valueAJson.getStr("wtype"), valueBJson.getStr("wtype"), valueAJson.getStr("rtype"), valueBJson.getStr("rtype"), valueAJson.getStr("choseTeam"), valueBJson.getStr("choseTeam"), valueAJson.getStr("con"), valueBJson.getStr("con"), valueAJson.getStr("ratio"), valueBJson.getStr("ratio"),
                                                betInfoA, betInfoB
                                        );
                                        results.add(sweepwaterDTO);
                                        // 生成格式为 "HHmm"（小时+分钟） todo 以分钟为单位进行切割扫水列表，分key保存
                                        // String minuteKey = now.format(DateTimeFormatter.ofPattern("HHmm"));
                                        // String sweepWaterKey = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username, minuteKey);
                                        saveSweepwater(username, sweepwaterDTO);
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
                                    // updateOddsCache(username, valueAJson.getStr("id"), valueA);
                                    // 记录网站B的赔率
                                    // updateOddsCache(username, valueBJson.getStr("id"), valueB);

                                    double value = valueA + valueB + 2;
                                    BigDecimal result = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
                                    double finalValue = result.doubleValue();
                                    String decimalOddsB = valueBJson.containsKey("decimalOdds") ? valueBJson.getStr("decimalOdds") : null;
                                    // 判断赔率是否在指定区间内
                                    if (oddsScan.getWaterLevelFrom() <= finalValue && finalValue <= oddsScan.getWaterLevelTo()) {
                                        LocalDateTime now = LocalDateTime.now();
                                        JSONObject betInfoA = null;
                                        JSONObject betInfoB = null;
                                        try {
                                            betInfoA = buildBetInfo(websiteIdA, eventAJson.getJSONArray("events"), teamA, eventAJson.getStr("league"), key, valueAJson, teamA.getBool("isHome"), amountDTO);
                                            betInfoB = buildBetInfo(websiteIdB, eventBJson.getJSONArray("events"), teamB, eventBJson.getStr("league"), key, valueBJson, teamB.getBool("isHome"), amountDTO);
                                        } catch (Exception e) {
                                            log.info("通过赔率手动解析betInfo失败: ", e);
                                        }
                                        // 查询缓存看谁的赔率是最新变动的
                                        String oddsIdKeyA = "";
                                        String oddsIdKeyB = "";
                                        if (WebsiteType.ZHIBO.getId().equals(websiteIdA)) {
                                            oddsIdKeyA = valueAJson.getStr("id");
                                        } else if (WebsiteType.PINGBO.getId().equals(websiteIdA)) {
                                            int lastPipeIndex = valueAJson.getStr("id").lastIndexOf("|");
                                            String suffix = "";
                                            if (teamA.getBool("isHome")) {
                                                suffix = "H";
                                            } else {
                                                suffix = "C";
                                            }
                                            oddsIdKeyA = valueAJson.getStr("id").substring(0, lastPipeIndex + 1) + suffix;
                                        } else if (WebsiteType.XINBAO.getId().equals(websiteIdA)) {
                                            // 新二网站
                                            oddsIdKeyA = valueAJson.getStr("id") + valueAJson.getStr("choseTeam");
                                        } else if (WebsiteType.SBO.getId().equals(websiteIdA)) {
                                            // 盛帆网站
                                            String suffix = "";
                                            if (teamA.getBool("isHome")) {
                                                suffix = "H";
                                            } else {
                                                suffix = "C";
                                            }
                                            oddsIdKeyA = valueAJson.getStr("id") + suffix;
                                        }
                                        if (WebsiteType.ZHIBO.getId().equals(websiteIdB)) {
                                            oddsIdKeyB = valueBJson.getStr("id");
                                        } else if (WebsiteType.PINGBO.getId().equals(websiteIdB)) {
                                            int lastPipeIndex = valueBJson.getStr("id").lastIndexOf("|");
                                            String suffix = "";
                                            if (teamB.getBool("isHome")) {
                                                suffix = "H";
                                            } else {
                                                suffix = "C";
                                            }
                                            oddsIdKeyB = valueBJson.getStr("id").substring(0, lastPipeIndex + 1) + suffix;
                                        } else if (WebsiteType.XINBAO.getId().equals(websiteIdA)) {
                                            // 新二网站
                                            oddsIdKeyB = valueBJson.getStr("id") + valueBJson.getStr("choseTeam");
                                        } else if (WebsiteType.SBO.getId().equals(websiteIdA)) {
                                            // 盛帆网站
                                            String suffix = "";
                                            if (teamB.getBool("isHome")) {
                                                suffix = "H";
                                            } else {
                                                suffix = "C";
                                            }
                                            oddsIdKeyB = valueBJson.getStr("id") + suffix;
                                        }
                                        /*String oddsKeyA = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, sweepwaterUsername, oddsIdKeyA);
                                        String oddsKeyB = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, sweepwaterUsername, oddsIdKeyB);
                                        Map<String, Boolean> latestChanged = isLatestChanged(oddsKeyA, oddsKeyB);
                                        boolean lastTimeA = latestChanged.get("lastTimeA");
                                        boolean lastTimeB = latestChanged.get("lastTimeB");*/
                                        String handicapA = valueAJson.containsKey("handicap") ? valueAJson.getStr("handicap") : "";
                                        String handicapB = valueBJson.containsKey("handicap") ? valueBJson.getStr("handicap") : "";
                                        SweepwaterDTO sweepwaterDTO = createSweepwaterDTO(username, valueAJson.getStr("id"), valueBJson.getStr("id"), now, valueAJson.getStr("selectionId"), valueBJson.getStr("selectionId"), courtType, key, eventAJson, eventBJson, teamA, teamB, websiteIdA, websiteIdB, leagueIdA, leagueIdB, eventIdA, eventIdB, nameA, nameB, handicapA, handicapB, valueA, valueB, finalValue, decimalOddsA, decimalOddsB, scoreA, scoreB,
                                                valueAJson.getStr("oddFType"), valueBJson.getStr("oddFType"), valueAJson.getStr("gtype"), valueBJson.getStr("gtype"), valueAJson.getStr("wtype"), valueBJson.getStr("wtype"), valueAJson.getStr("rtype"), valueBJson.getStr("rtype"), valueAJson.getStr("choseTeam"), valueBJson.getStr("choseTeam"), valueAJson.getStr("con"), valueBJson.getStr("con"), valueAJson.getStr("ratio"), valueBJson.getStr("ratio"),
                                                betInfoA, betInfoB
                                                );
                                        results.add(sweepwaterDTO);
                                        // 生成格式为 "HHmm"（小时+分钟） todo 以分钟为单位进行切割扫水列表，分key保存
                                        // String minuteKey = now.format(DateTimeFormatter.ofPattern("HHmm"));
                                        // String sweepWaterKey = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username, minuteKey);
                                        saveSweepwater(username, sweepwaterDTO);
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
     * 扫水数据写入后进行长度判断 + 裁剪逻辑
     * @param username
     * @param dto
     */
    private void saveSweepwater(String username, SweepwaterDTO dto) {
        String sweepWaterKey = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);
        RList<String> sweepList = businessPlatformRedissonClient.getList(sweepWaterKey);
        // 写入扫水数据
        sweepList.add(JSONUtil.toJsonStr(dto));
        // 最大保留条数
        int maxSize = 1000;
        // 缓冲区（减少频繁 trim）
        int buffer = 100;
        // 超过阈值才裁剪
        if (sweepList.size() > maxSize + buffer) {
            sweepList.trim(sweepList.size() - maxSize, sweepList.size() - 1);
        }
    }

    /**
     * 根据对比的赔率相关信息进行手动配置投注预览信息
     * @param websiteId     网站id
     * @param teams         从盘口获取解析后的联赛下所有球队赔率
     * @param teamsOdds     从盘口获取解析后的联赛指定球队赔率
     * @param leagueName    联赛名
     * @param key           letBall:让球盘，overSize:大小盘
     * @param oddsJson      投注的最终解析的赔率json
     * @param isHome        是否是主队
     * @param amount        软件设置中的投注金额
     * @return
     */
    public JSONObject buildBetInfo(String websiteId, JSONArray teams, JSONObject teamsOdds, String leagueName, String key, JSONObject oddsJson, boolean isHome, BetAmountDTO amount) {
        JSONObject betInfo = new JSONObject();
        log.info("[手动设置投注信息][网站:{}][teams={}][teamsOdds={}][leagueName={}][key={}][oddsJson={}][isHome={}]", WebsiteType.getById(websiteId).getDescription(), teams, teamsOdds, leagueName, key, oddsJson, isHome);
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            // eg.平博赔率的id是1611238334|0|2|1|1|-1.0，截取第一个赛事id=1611238334
            String[] parts = oddsJson.getStr("id").split("\\|");  // 按 | 分割
            String firstId = parts[0];
            String handicap = parts[parts.length - 1];
            JSONObject homeTeam = new JSONObject();
            JSONObject awayTeam = new JSONObject();
            for (Object team : teams) {
                JSONObject teamJson = JSONUtil.parseObj(team);
                if (teamJson.getStr("id").equals(firstId) && teamJson.getBool("isHome")) {
                    homeTeam = teamJson;
                } else if (teamJson.getStr("id").equals(firstId) && !teamJson.getBool("isHome")) {
                    awayTeam = teamJson;
                }
            }
            String marketName = "";
            if ("letBall".equals(key)) {
                // 让球盘
                String wall = oddsJson.containsKey("wall") ? oddsJson.getStr("wall") : null;
                if (StringUtils.isNotBlank(wall)) {
                    if ("hanging".equals(wall)) {
                        // 让球盘
                        marketName = homeTeam.getStr("name");
                    } else if ("foot".equals(wall)) {
                        // 让球盘
                        marketName = awayTeam.getStr("name");
                    }
                }
            } else if ("overSize".equals(key)) {
                // 大小盘
                if (isHome) {
                    marketName = "大盘";
                } else {
                    marketName = "小盘";
                }
            }
            betInfo.putOpt("league", leagueName);
            betInfo.putOpt("team", homeTeam.getStr("name") + " -vs- " + awayTeam.getStr("name"));
            betInfo.putOpt("marketTypeName", "");
            betInfo.putOpt("marketName", marketName);
            betInfo.putOpt("odds", marketName + " " + handicap + " @ " + oddsJson.getStr("odds"));
            betInfo.putOpt("handicap", handicap);
            betInfo.putOpt("amount", amount.getAmountPingBo());
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            String id = teamsOdds.getStr("id");
            JSONObject homeTeam = new JSONObject();
            JSONObject awayTeam = new JSONObject();
            for (Object object : teams) {
                JSONObject teamJson = JSONUtil.parseObj(object);
                if (teamJson.getStr("id").equals(id) && teamJson.getBool("isHome")) {
                    homeTeam = teamJson;
                } else if (teamJson.getStr("id").equals(id) && !teamJson.getBool("isHome")) {
                    awayTeam = teamJson;
                }
            }
            String marketName = "";
            if ("letBall".equals(key)) {
                // 让球盘
                String wall = oddsJson.containsKey("wall") ? oddsJson.getStr("wall") : null;
                if (StringUtils.isNotBlank(wall)) {
                    if ("hanging".equals(wall)) {
                        // 让球盘
                        marketName = homeTeam.getStr("name");
                    } else if ("foot".equals(wall)) {
                        // 让球盘
                        marketName = awayTeam.getStr("name");
                    }
                }
            } else if ("overSize".equals(key)) {
                // 大小盘
                if (isHome) {
                    marketName = "大盘";
                } else {
                    marketName = "小盘";
                }
            }
            String handicap = oddsJson.containsKey("handicap") ? oddsJson.getStr("handicap") : null;
            betInfo.putOpt("league", leagueName);
            betInfo.putOpt("team", homeTeam.getStr("name") + " -vs- " + awayTeam.getStr("name"));
            betInfo.putOpt("marketTypeName", "");
            betInfo.putOpt("marketName", marketName);
            betInfo.putOpt("odds", marketName + " " + handicap + " @ " + oddsJson.getStr("odds"));
            betInfo.putOpt("handicap", handicap);
            betInfo.putOpt("amount", amount.getAmountZhiBo());
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            JSONObject homeTeam = new JSONObject();
            JSONObject awayTeam = new JSONObject();
            String eventId = teamsOdds.getStr("id");
            for (Object object : teams) {
                JSONObject teamJson = JSONUtil.parseObj(object);
                if (!teamJson.getStr("id").equals(eventId)) {
                    continue;
                }
                if (teamJson.getBool("isHome")) {
                    homeTeam = teamJson;
                } else if (!teamJson.getBool("isHome")) {
                    awayTeam = teamJson;
                }
            }
            String marketName = "";
            if ("letBall".equals(key)) {
                // 让球盘
                String wall = oddsJson.containsKey("wall") ? oddsJson.getStr("wall") : null;
                if (StringUtils.isNotBlank(wall)) {
                    if ("hanging".equals(wall)) {
                        // 让球盘
                        marketName = homeTeam.getStr("name");
                    } else if ("foot".equals(wall)) {
                        // 让球盘
                        marketName = awayTeam.getStr("name");
                    }
                }
            } else if ("overSize".equals(key)) {
                // 大小盘
                if (isHome) {
                    marketName = "大盘";
                } else {
                    marketName = "小盘";
                }
            }
            String handicap = oddsJson.containsKey("handicap") ? oddsJson.getStr("handicap") : null;
            betInfo.putOpt("league", leagueName);
            betInfo.putOpt("team", homeTeam.getStr("name") + " -vs- " + awayTeam.getStr("name"));
            betInfo.putOpt("marketTypeName", "");
            betInfo.putOpt("marketName", marketName);
            betInfo.putOpt("odds", marketName + " " + handicap + " @ " + oddsJson.getStr("odds"));
            betInfo.putOpt("handicap", handicap);
            betInfo.putOpt("amount", amount.getAmountXinEr());
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            JSONObject homeTeam = new JSONObject();
            JSONObject awayTeam = new JSONObject();
            String eventId = teamsOdds.getStr("id");
            for (Object object : teams) {
                JSONObject teamJson = JSONUtil.parseObj(object);
                if (!teamJson.getStr("id").equals(eventId)) {
                    continue;
                }
                if (teamJson.getBool("isHome")) {
                    homeTeam = teamJson;
                } else if (!teamJson.getBool("isHome")) {
                    awayTeam = teamJson;
                }
            }
            String marketName = "";
            if ("letBall".equals(key)) {
                // 让球盘
                String wall = oddsJson.containsKey("wall") ? oddsJson.getStr("wall") : null;
                if (StringUtils.isNotBlank(wall)) {
                    if ("hanging".equals(wall)) {
                        // 让球盘
                        marketName = homeTeam.getStr("name");
                    } else if ("foot".equals(wall)) {
                        // 让球盘
                        marketName = awayTeam.getStr("name");
                    }
                }
            } else if ("overSize".equals(key)) {
                // 大小盘
                if (isHome) {
                    marketName = "大盘";
                } else {
                    marketName = "小盘";
                }
            }
            String handicap = oddsJson.containsKey("handicap") ? oddsJson.getStr("handicap") : null;
            betInfo.putOpt("league", leagueName);
            betInfo.putOpt("team", homeTeam.getStr("name") + " -vs- " + awayTeam.getStr("name"));
            betInfo.putOpt("teamVSH", homeTeam.getStr("name"));
            betInfo.putOpt("teamVSA", awayTeam.getStr("name"));
            betInfo.putOpt("marketTypeName", "");
            betInfo.putOpt("marketName", marketName);
            betInfo.putOpt("odds", marketName + " " + handicap + " @ " + oddsJson.getStr("odds"));
            betInfo.putOpt("handicap", handicap);
            betInfo.putOpt("amount", amount.getAmountXinEr());
        }

        return betInfo;
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

        log.info("查询两个队的赔率时间-oddsKeyA:{}=={}, oddsKeyB:{}=={}", oddsKeyA, lastOddsTimeA, oddsKeyB, lastOddsTimeB);
        Map<String, Boolean> result = new HashMap<>();
        result.put("lastTimeA", lastOddsTimeA > lastOddsTimeB);
        result.put("lastTimeB", lastOddsTimeB > lastOddsTimeA);
        return result;
    }

    // 创建 SweepwaterDTO 对象的简化方法
    private SweepwaterDTO createSweepwaterDTO(String username, String oddsIdA, String oddsIdB, LocalDateTime now, String selectionIdA, String selectionIdB, String courtType, String handicapType, JSONObject eventAJson, JSONObject eventBJson, JSONObject teamA, JSONObject teamB,
                                                     String websiteIdA, String websiteIdB, String leagueIdA, String leagueIdB, String eventIdA, String eventIdB, String nameA, String nameB, String handicapA, String handicapB,
                                                     double valueA, double valueB, double value, String decimalOddsA, String decimalOddsB, String scoreA, String scoreB,
                                                     String strongA, String strongB, String gTypeA, String gTypeB, String wTypeA, String wTypeB, String rTypeA, String rTypeB, String choseTeamA, String choseTeamB, String conA, String conB, String ratioA, String ratioB,
                                                     JSONObject betInfoA, JSONObject betInfoB
    ) {
        boolean lastTimeA = true;
        boolean lastTimeB = true;
        List<SweepwaterDTO> sweepwaters = new ArrayList<>(getSweepwaters(username));
        // 如果存在扫水，倒叙遍历找到对应联赛和队伍的扫水信息，查看对应赔率字段odds是否变动，变动则为true，反之false。遍历对比时通过websiteIdA,websiteIdB,type,handicapType,oddsIdA,oddsIdB这些字段判断是否为对应的扫水数据
        // 从后往前找最近的匹配记录
        for (int i = sweepwaters.size() - 1; i >= 0; i--) {
            SweepwaterDTO old = sweepwaters.get(i);
            boolean baseMatch = Objects.equals(old.getWebsiteIdA(), websiteIdA)
                    && Objects.equals(old.getWebsiteIdB(), websiteIdB)
                    && Objects.equals(old.getType(), courtType)
                    && Objects.equals(old.getHandicapType(), handicapType)
                    && Objects.equals(old.getTeamA(), nameA)
                    && Objects.equals(old.getTeamB(), nameB);

            // 特殊情况，如果网站是平博，那么对应的oddsId需要把最后一个|的值删掉后再做对比
            String oldOddsIdA = old.getOddsIdA();
            String oldOddsIdB = old.getOddsIdB();
            String currentOddsIdA = oddsIdA;
            String currentOddsIdB = oddsIdB;

            if (WebsiteType.PINGBO.getId().equals(websiteIdA)) {
                int idx = currentOddsIdA.lastIndexOf("|");
                currentOddsIdA = idx != -1 ? currentOddsIdA.substring(0, idx + 1) : currentOddsIdA;
                int oldIdx = oldOddsIdA.lastIndexOf("|");
                oldOddsIdA = oldIdx != -1 ? oldOddsIdA.substring(0, oldIdx + 1) : oldOddsIdA;
            }
            if (WebsiteType.PINGBO.getId().equals(websiteIdB)) {
                int idx = currentOddsIdB.lastIndexOf("|");
                currentOddsIdB = idx != -1 ? currentOddsIdB.substring(0, idx + 1) : currentOddsIdB;
                int oldIdx = oldOddsIdB.lastIndexOf("|");
                oldOddsIdB = oldIdx != -1 ? oldOddsIdB.substring(0, oldIdx + 1) : oldOddsIdB;
            }

            if (baseMatch && Objects.equals(oldOddsIdA, currentOddsIdA) && Objects.equals(oldOddsIdB, currentOddsIdB)) {
                lastTimeA = old.getOddsA() != null && !String.format("%.2f", valueA).equals(old.getOddsA());
                lastTimeB = old.getOddsB() != null && !String.format("%.2f", valueB).equals(old.getOddsB());
                break; // ✅ 提前退出
            }
        }

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
        sweepwaterDTO.setTeamA(nameA);
        sweepwaterDTO.setTeamB(nameB);
        sweepwaterDTO.setReTimeA(teamA.getStr("reTime"));
        sweepwaterDTO.setReTimeB(teamB.getStr("reTime"));
        sweepwaterDTO.setIsHomeA(teamA.getBool("isHome"));
        sweepwaterDTO.setIsHomeB(teamB.getBool("isHome"));
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
        sweepwaterDTO.setHandicapA(handicapA);
        sweepwaterDTO.setHandicapB(handicapB);
        sweepwaterDTO.setScoreA(scoreA);
        sweepwaterDTO.setScoreB(scoreB);
        sweepwaterDTO.setBetInfoA(betInfoA);
        sweepwaterDTO.setBetInfoB(betInfoB);
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

        sweepwaterDTO.setTeamVSHA(betInfoA != null ? betInfoA.getStr("teamVSH") : null);
        sweepwaterDTO.setTeamVSAA(betInfoA != null ? betInfoA.getStr("teamVSA") : null);
        sweepwaterDTO.setTeamVSHB(betInfoB != null ? betInfoB.getStr("teamVSH") : null);
        sweepwaterDTO.setTeamVSAB(betInfoB != null ? betInfoB.getStr("teamVSA") : null);

        sweepwaterDTO.setCreateTime(LocalDateTimeUtil.format(now, DatePattern.NORM_DATETIME_PATTERN));

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
        /*log.info("扫水匹配到数据-保存扫水数据");
        String sweepWaterKey = KeyUtil.genKey(RedisConstants.SWEEPWATER_PREFIX, username);
        businessPlatformRedissonClient.getList(sweepWaterKey).add(JSONUtil.toJsonStr(sweepwaterDTO));*/
        // 只要扫水有结果就执行下注
        log.info("扫水匹配到数据-进行投注");
        betService.bet(username);
    }

    // 提取的日志输出方法
    private static void logInfo(String handicapType, String nameA, String key, double value1, String nameB, String key2, double value2, double value, OddsScanDTO oddsScanDTO) {
        log.info("比对扫描中,队伍[{}]的{}[{}]赔率是[{}],对比队伍[{}]的{}[{}]赔率[{}],相加结果赔率是[{}],系统设置的区间是[{}]到[{}]", nameA, handicapType, key, value1, nameB, handicapType, key2, value2, value, oddsScanDTO.getWaterLevelFrom(), oddsScanDTO.getWaterLevelTo());
    }
}
