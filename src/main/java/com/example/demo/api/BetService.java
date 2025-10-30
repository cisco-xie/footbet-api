package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.config.SuccessBasedLimitManager;
import com.example.demo.core.result.PageResult;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import com.example.demo.model.dto.settings.*;
import com.example.demo.model.dto.sweepwater.SweepwaterDTO;
import com.example.demo.model.vo.WebsiteVO;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class BetService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    @Resource
    private AdminService adminService;
    @Resource
    private HandicapApi handicapApi;
    @Resource
    private SweepwaterService sweepwaterService;
    @Resource
    private SettingsBetService settingsBetService;
    @Resource
    private SettingsService settingsService;
    @Resource
    private WebsiteService websiteService;
    @Resource
    private SuccessBasedLimitManager limitManager;

    // 全局存储处理过的 ID
    private static final Set<String> processedIds = ConcurrentHashMap.newKeySet();

    /**
     * 清空实时投注
     * @param username
     */
    public void betClear(String username) {
        // 删除下注明细
        String betPattern = KeyUtil.genKey(RedisConstants.PLATFORM_BET_PREFIX, username, "realtime", "*");
        businessPlatformRedissonClient.getKeys().deleteByPattern(betPattern);

        // 删除实时索引
        String indexPattern = KeyUtil.genKey("INDEX", username, "realtime");
        businessPlatformRedissonClient.getKeys().deleteByPattern(indexPattern);

        log.info("清理下注缓存完成，用户:{}，pattern:{} 和 {}", username, betPattern, indexPattern);
    }

    /**
     * 查询是否存在新的投注
     * 根据调用方提供的 lastSeenTotal（之前看到的 index.size()），返回从 lastSeenTotal 开始到当前总数的所有“新”投注。
     * - lastSeenTotal 表示上次已读的 index.size()（例如：上次 total 为 100，则 new 区间是 [100, currentTotal-1]）
     * - 如果 lastSeenTotal < 0 或 null，则视为 0（返回全部）
     */
    public boolean getNewRealtimeBetsSince(String username, Integer lastSeenTotal) {
        int pageNum = 1;
        int pageSize = 50000000;
        String indexKey = KeyUtil.genKey("INDEX", username, "realtime");
        RList<String> index = businessPlatformRedissonClient.getList(indexKey);

        int currentTotal = index.size();

        int prevTotal = (lastSeenTotal == null || lastSeenTotal < 0) ? 0 : lastSeenTotal;

        // 没有新数据
        if (currentTotal <= prevTotal) return false;

        int start = currentTotal - pageNum * pageSize;
        if (start < 0) start = 0;
        int end = currentTotal - (pageNum - 1) * pageSize - 1;
        if (end < 0) {
            return false;
        }

        // 批量读取新增的投注
        List<String> newKeys = index.range(start, end);
        if (newKeys.isEmpty()) return false;

        RBatch batch = businessPlatformRedissonClient.createBatch();
        List<RFuture<Object>> futures = new ArrayList<>();
        for (String key : newKeys) {
            futures.add(batch.getBucket(key).getAsync());
        }
        batch.execute();

        int currentSuccess = 0;
        for (RFuture<Object> future : futures) {
            try {
                Object json = future.toCompletableFuture().join();
                if (json != null) {
                    SweepwaterBetDTO dto = JSONUtil.toBean((String) json, SweepwaterBetDTO.class);
                    if ((dto.getBetSuccessA() != null && dto.getBetSuccessA())
                            || (dto.getBetSuccessB() != null && dto.getBetSuccessB())) {
                        // 找到至少一个成功投注即返回 true
                        currentSuccess += 1;
                    }
                }
            } catch (Exception e) {
                log.warn("读取实时投注单缓存失败", e);
            }
        }

        // 所有成功投注数量校验是否大于前端传入的数量
        return currentSuccess > prevTotal;
    }

    /**
     * 获取实时投注记录
     * @param username
     * @return
     */
    public PageResult<SweepwaterBetDTO> getRealTimeBets(
            String username, String teamName, Integer pageNum, Integer pageSize) {

        if (pageNum == null || pageNum < 1) pageNum = 1;
        if (pageSize == null || pageSize < 1) pageSize = 10;

        // 实时索引 key
        String indexKey = KeyUtil.genKey("INDEX", username, "realtime");
        RList<String> index = businessPlatformRedissonClient.getList(indexKey);

        int total = index.size();
        if (total == 0) {
            return new PageResult<>(Collections.emptyList(), 0L, pageNum, pageSize);
        }

        // 倒序分页：最新数据在后，所以从 total - 1 开始往前切
        int start = total - pageNum * pageSize;
        if (start < 0) start = 0;
        int end = total - (pageNum - 1) * pageSize - 1;
        if (end < 0) {
            return new PageResult<>(Collections.emptyList(), (long) total, pageNum, pageSize);
        }

        // 一次性取出切片
        List<String> pageKeys = index.range(start, end);

        // 倒序，保证最新的数据在前
        Collections.reverse(pageKeys);

        // 批量读取
        RBatch batch = businessPlatformRedissonClient.createBatch();
        List<RFuture<Object>> futures = new ArrayList<>();
        for (String key : pageKeys) {
            futures.add(batch.getBucket(key).getAsync());
        }
        batch.execute();

        List<SweepwaterBetDTO> pageData = new ArrayList<>();
        for (RFuture<Object> future : futures) {
            try {
                Object json = future.toCompletableFuture().join();
                if (json != null) {
                    SweepwaterBetDTO dto = JSONUtil.toBean((String) json, SweepwaterBetDTO.class);
                    if (StringUtils.isBlank(teamName) ||
                            (dto.getTeam() != null && dto.getTeam().contains(teamName))) {
                        pageData.add(dto);
                    }
                }
            } catch (Exception e) {
                log.warn("读取实时投注单缓存失败", e);
            }
        }

        return new PageResult<>(pageData, (long) total, pageNum, pageSize);
    }

    /**
     * 获取历史投注记录
     * @param username
     * @param date
     * @return
     */
    public PageResult<SweepwaterBetDTO> getBets(
            String username, String teamName, String date, String success,
            Integer pageNum, Integer pageSize) {

        if (StringUtils.isBlank(date)) {
            date = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN);
        }
        if (pageNum == null || pageNum < 1) pageNum = 1;
        if (pageSize == null || pageSize < 1) pageSize = 10;

        String indexKey = KeyUtil.genKey("INDEX", username, date);
        RList<String> index = businessPlatformRedissonClient.getList(indexKey);
        int totalIndex = index.size();
        if (totalIndex == 0) {
            return new PageResult<>(Collections.emptyList(), 0L, pageNum, pageSize);
        }

        boolean needFilter = StringUtils.isNotBlank(teamName) ||
                (StringUtils.isNotBlank(success) && !"all".equalsIgnoreCase(success));

        // 不需要过滤：直接返回当前页数据
        if (!needFilter) {
            int start = totalIndex - pageNum * pageSize;
            int end = totalIndex - (pageNum - 1) * pageSize - 1;
            if (start < 0) start = 0;
            if (end < 0) return new PageResult<>(Collections.emptyList(), (long) totalIndex, pageNum, pageSize);

            List<String> pageKeys = index.range(start, end);
            Collections.reverse(pageKeys);

            RBatch batch = businessPlatformRedissonClient.createBatch();
            List<RFuture<Object>> futures = new ArrayList<>();
            for (String key : pageKeys) {
                futures.add(batch.getBucket(key).getAsync());
            }
            batch.execute();

            List<SweepwaterBetDTO> pageData = new ArrayList<>();
            for (RFuture<Object> future : futures) {
                try {
                    Object json = future.toCompletableFuture().join();
                    if (json != null) {
                        SweepwaterBetDTO dto = JSONUtil.toBean((String) json, SweepwaterBetDTO.class);
                        pageData.add(dto);
                    }
                } catch (Exception e) {
                    log.warn("读取投注单缓存失败", e);
                }
            }

            return new PageResult<>(pageData, (long) totalIndex, pageNum, pageSize);
        }

        // 需要过滤：扫描整个索引获取所有匹配
        List<SweepwaterBetDTO> filteredList = new ArrayList<>();
        int batchSize = 200; // 每批读取数量
        int start = totalIndex - 1;

        while (start >= 0) {
            int batchStart = Math.max(0, start - batchSize + 1);
            List<String> keys = index.range(batchStart, start);
            Collections.reverse(keys);

            RBatch batch = businessPlatformRedissonClient.createBatch();
            List<RFuture<Object>> futures = new ArrayList<>();
            for (String key : keys) {
                futures.add(batch.getBucket(key).getAsync());
            }
            batch.execute();

            for (RFuture<Object> future : futures) {
                try {
                    Object json = future.toCompletableFuture().join();
                    if (json != null) {
                        SweepwaterBetDTO dto = JSONUtil.toBean((String) json, SweepwaterBetDTO.class);

                        boolean matchTeam = StringUtils.isBlank(teamName)
                                || (dto.getTeam() != null && dto.getTeam().contains(teamName));

                        boolean matchSuccess = true;
                        if (StringUtils.isNotBlank(success) && !"all".equalsIgnoreCase(success)) {
                            boolean betSuccess = Boolean.TRUE.equals(dto.getBetSuccessA())
                                    || Boolean.TRUE.equals(dto.getBetSuccessB());
                            boolean betSimulate = (dto.getSimulateBetA() != null && dto.getSimulateBetA() == 1)
                                    || (dto.getSimulateBetB() != null && dto.getSimulateBetB() == 1);
                            if ("success".equalsIgnoreCase(success)) matchSuccess = betSuccess;
                            else if ("fail".equalsIgnoreCase(success)) matchSuccess = !betSuccess;
                            else if ("simulate".equalsIgnoreCase(success)) matchSuccess = betSimulate;
                        }

                        if (matchTeam && matchSuccess) {
                            filteredList.add(dto);
                        }
                    }
                } catch (Exception e) {
                    log.warn("读取投注单缓存失败", e);
                }
            }

            start = batchStart - 1;
        }

        // 分页截取
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(pageNum * pageSize, filteredList.size());
        List<SweepwaterBetDTO> pageData = fromIndex >= filteredList.size()
                ? Collections.emptyList()
                : filteredList.subList(fromIndex, toIndex);

        return new PageResult<>(pageData, (long) filteredList.size(), pageNum, pageSize);
    }

    /**
     * 投注
     * @param username
     */
    public void bet(String username) {
        TimeInterval timerTotal = DateUtil.timer();
        List<SweepwaterDTO> sweepwaters = new ArrayList<>(sweepwaterService.getSweepwaters(username));
        if (CollUtil.isEmpty(sweepwaters)) {
            return;
        }
        // ✅ 安全过滤已投注的
        sweepwaters.removeIf(s -> s.getIsBet() == 1);
        LimitDTO limitDTO = settingsBetService.getLimit(username);
        IntervalDTO intervalDTO = settingsBetService.getInterval(username);
        OptimizingDTO optimizing = settingsBetService.getOptimizing(username);
        BetAmountDTO amountDTO = settingsService.getBetAmount(username);
        AdminLoginDTO admin = adminService.getAdmin(username);
        ProfitDTO profit = settingsService.getProfit(username);
        sweepwaters.removeIf(sweepwater -> {
            try {
                double water = Double.parseDouble(sweepwater.getWater());
                if ("letBall".equals(sweepwater.getHandicapType())) {
                    return water < profit.getRollingLetBall();
                } else if ("overSize".equals(sweepwater.getHandicapType())) {
                    return water < profit.getRollingSize();
                }
            } catch (NumberFormatException e) {
                log.info("水位格式异常: {}, 已跳过该条投注", sweepwater.getWater());
                return true; // 移除格式错误的
            }
            return false;
        });

        if (sweepwaters.isEmpty()) {
            return ;
        }
        // CPU核数*4或者最大100线程
        int cpuCoreCount = Math.min(Runtime.getRuntime().availableProcessors() * 4, 100);
        // 核心线程数就是内部BindLeagueVO数量，最大线程数取个合理值
        int corePoolSize = Math.min(sweepwaters.size(), cpuCoreCount);
        int maxPoolSize = Math.max(sweepwaters.size(), cpuCoreCount);

        // 合理限制并发线程数，比如最多10个并发
        ExecutorService betExecutor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactoryBuilder().setNameFormat("bet-task-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // ✅ 单边投注控制
        boolean isUnilateral = limitDTO.getUnilateralBet() != null && limitDTO.getUnilateralBet() == 1;

        List<WebsiteVO> websites = websiteService.getWebsites(username);
        for (SweepwaterDTO sweepwaterDTO : sweepwaters) {
            // 30秒投注限制不需要
            /*LocalDateTime createTime = LocalDateTime.parse(sweepwaterDTO.getCreateTime(), DateTimeFormatter.ofPattern(DatePattern.NORM_DATETIME_MS_PATTERN));
            long seconds = Duration.between(createTime, LocalDateTime.now()).getSeconds();
            if (seconds > 30) {
                log.info("赔率数据过期({}秒前)，放弃处理投注", seconds);
                continue; // 直接退出方法
            }*/
            // 如果已经处理过，直接跳过
            if (!processedIds.add(sweepwaterDTO.getId())) {
                continue;
            }

            if (sweepwaterDTO.getLastOddsTimeA() == sweepwaterDTO.getLastOddsTimeB()) {
                // 如果两个都是旧或者新,则不进行投注,不需要在前端显示
                continue; // 直接跳过
            }

            processedIds.add(sweepwaterDTO.getId());
            SweepwaterBetDTO dto = new SweepwaterBetDTO();
            BeanUtils.copyProperties(sweepwaterDTO, dto);
            dto.setIsUnilateral(isUnilateral);
            dto.setLastOddsTimeA(sweepwaterDTO.getLastOddsTimeA());
            dto.setLastOddsTimeB(sweepwaterDTO.getLastOddsTimeB());
            dto.setSweepwaterCreateTime(sweepwaterDTO.getCreateTime());

            // 单边投注如果选中的网站赔率新旧不满足选边则不显示
            if (isUnilateral) {
                if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 1
                        && dto.getLastOddsTimeA()
                        && limitDTO.getWebsiteLimit().equals(dto.getWebsiteIdA())) {
                    // 单边旧投注,当前网站赔率是最新的，直接跳出不投注
                    continue;
                } else if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 2
                        && !dto.getLastOddsTimeA()
                        && limitDTO.getWebsiteLimit().equals(dto.getWebsiteIdA())) {
                    // 单边新投注,当前网站赔率不是最新的，直接跳出不投注
                    continue;
                } else if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 1
                        && dto.getLastOddsTimeB()
                        && limitDTO.getWebsiteLimit().equals(dto.getWebsiteIdB())) {
                    // 单边旧投注,当前网站赔率是最新的，直接跳出不投注
                    continue;
                } else if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 2
                        && !dto.getLastOddsTimeB()
                        && limitDTO.getWebsiteLimit().equals(dto.getWebsiteIdB())) {
                    // 单边新投注,当前网站赔率不是最新的，直接跳出不投注
                    continue;
                }
            } else {
                // 双边投注
                if (limitDTO.getBothSideOption() != null && limitDTO.getBothSideOption() == 1
                        && dto.getLastOddsTimeA()
                        && limitDTO.getWebsiteLimit().equals(dto.getWebsiteIdA())) {
                    // 双边旧投注,当前网站赔率是最新的，直接跳出不投注
                    continue;
                } else if (limitDTO.getBothSideOption() != null && limitDTO.getBothSideOption() == 2
                        && !dto.getLastOddsTimeA()
                        && limitDTO.getWebsiteLimit().equals(dto.getWebsiteIdA())) {
                    // 双边新投注,当前网站赔率不是最新的，直接跳出不投注
                    continue;
                } else if (limitDTO.getBothSideOption() != null && limitDTO.getBothSideOption() == 1
                        && dto.getLastOddsTimeB()
                        && limitDTO.getWebsiteLimit().equals(dto.getWebsiteIdB())) {
                    // 双边旧投注,当前网站赔率是最新的，直接跳出不投注
                    continue;
                } else if (limitDTO.getBothSideOption() != null && limitDTO.getBothSideOption() == 2
                        && !dto.getLastOddsTimeB()
                        && limitDTO.getWebsiteLimit().equals(dto.getWebsiteIdB())) {
                    // 双边新投注,当前网站赔率不是最新的，直接跳出不投注
                    continue;
                }
            }

            int rollingOrderA = websites.stream()
                    .filter(w -> w.getId().equals(dto.getWebsiteIdA()))
                    .map(WebsiteVO::getRollingOrder)
                    .findFirst()
                    .orElse(0);

            int rollingOrderB = websites.stream()
                    .filter(w -> w.getId().equals(dto.getWebsiteIdB()))
                    .map(WebsiteVO::getRollingOrder)
                    .findFirst()
                    .orElse(0);

            dto.setSimulateBetA(admin.getSimulateBet());
            dto.setSimulateBetB(admin.getSimulateBet());

            // 校验投注间隔key
            String intervalKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_INTERVAL_PREFIX, username, String.valueOf(admin.getSimulateBet()), dto.getEventIdA(), dto.getEventIdB());
            // 投注次数限制key
            String limitKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, username, String.valueOf(admin.getSimulateBet()), dto.getEventIdA(), dto.getEventIdB());

            // 这里是校验间隔时间
            long intervalMillis = intervalDTO.getBetSuccessSec() * 1000L;

            // 1. 强制预检查
            SuccessBasedLimitManager.EnforcementResult checkResult = limitManager.preCheckAndReserve(
                    limitKey, intervalKey, dto.getScoreA(), limitDTO, intervalMillis);

            // 快速检查：如果明显不满足条件，直接返回
            if (!checkResult.isSuccess()) {
                log.info("快速检查不满足: 用户 {}, 联赛:{}, 比分 {}, 原因: {}",
                        username, dto.getLeague(), dto.getScoreA(), checkResult.getFailReason());
                continue;
            }
            // 保存reservationId，用于后续确认或回滚
            String reservationId = checkResult.getReservationId();

            // 并行获取 A/B 投注预览
            CompletableFuture<JSONObject> previewA = CompletableFuture.supplyAsync(() ->
                            buildBetInfo(username, dto.getTeamA(), dto.getWebsiteIdA(), buildBetParams(dto, amountDTO, true), dto),
                    betExecutor
            );
            CompletableFuture<JSONObject> previewB = CompletableFuture.supplyAsync(() ->
                            buildBetInfo(username, dto.getTeamB(), dto.getWebsiteIdB(), buildBetParams(dto, amountDTO, false), dto),
                    betExecutor
            );

            JSONObject betPreviewA = previewA.join();
            JSONObject betPreviewB = previewB.join();

            if (betPreviewA == null || betPreviewB == null) {
                // 回滚投注次数
                limitManager.rollbackReservation(limitKey, reservationId);
                log.info("用户 {} 赛事A={} 赛事B={} 投注预览失败，预览信息A={}, 预览信息B={}", username, dto.getLeagueNameA(), dto.getLeagueNameB(), betPreviewA, betPreviewB);
                continue; // 跳过
            }
            log.info("用户 {} 赛事A={} 赛事B={} 投注预览成功，预览信息A={}, 预览信息B={}", username, dto.getLeagueNameA(), dto.getLeagueNameB(), betPreviewA, betPreviewB);

            BigDecimal oddsA = betPreviewA.getJSONObject("betInfo").getBigDecimal("oddsValue");
            BigDecimal oddsB = betPreviewB.getJSONObject("betInfo").getBigDecimal("oddsValue");

            // 计算初始水位（两边相加）
            BigDecimal water = oddsA.add(oddsB);

            // 一方赔率为负数，则在结果上加 2,如果两个都是负数，就等于加4
            if (oddsA.compareTo(BigDecimal.ZERO) < 0) {
                water = water.add(BigDecimal.valueOf(2));
            }
            if (oddsB.compareTo(BigDecimal.ZERO) < 0) {
                water = water.add(BigDecimal.valueOf(2));
            }
            // ✅ 仅保留 3 位小数（不四舍五入）
            water = water.setScale(3, RoundingMode.DOWN);
            dto.setOddsA(oddsA);
            dto.setOddsB(oddsB);
            dto.setWater(String.valueOf(water));

            boolean valid = false;
            if ("letBall".equals(dto.getHandicapType())) {
                valid = water.compareTo(BigDecimal.valueOf(profit.getRollingLetBall())) >= 0;
            } else if ("overSize".equals(dto.getHandicapType())) {
                valid = water.compareTo(BigDecimal.valueOf(profit.getRollingSize())) >= 0;
            }

            if (!valid) {
                log.info("赛事预览水位不足，id={}，A={}, B={}, 总和={}，不投注", dto.getId(), oddsA, oddsB, water);

                String betTime = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN);
                dto.setCreateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                dto.setBetSuccessA(false);
                dto.setBetSuccessB(false);
                dto.setBetInfoA(betPreviewA.getJSONObject("betInfo"));
                dto.setBetInfoB(betPreviewB.getJSONObject("betInfo"));
                dto.setBetTimeA(betTime);
                dto.setBetTimeB(betTime);

                // 设置扫水已投注
                sweepwaterService.setIsBet(username, sweepwaterDTO.getId());

                String json = JSONUtil.toJsonStr(dto);
                String date = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN);
                // 保存投注信息作为历史记录
                String key = KeyUtil.genKey(
                        RedisConstants.PLATFORM_BET_PREFIX,
                        username,
                        date,
                        dto.getId()
                );
                businessPlatformRedissonClient.getBucket(key).set(json);
                // 维护索引
                String indexKey = KeyUtil.genKey("INDEX", username,
                        date
                );
                businessPlatformRedissonClient.getList(indexKey).add(key);

                // 保存投注信息作为实时记录
                String realTimeKey = KeyUtil.genKey(
                        RedisConstants.PLATFORM_BET_PREFIX,
                        username,
                        "realtime",
                        dto.getId()
                );
                businessPlatformRedissonClient.getBucket(realTimeKey).set(json);
                // 实时索引
                String realTimeIndexKey = KeyUtil.genKey("INDEX", username, "realtime");
                businessPlatformRedissonClient.getList(realTimeIndexKey).add(realTimeKey);

                // 回滚投注次数
                limitManager.rollbackReservation(limitKey, reservationId);
                continue; // 直接跳过
            }
            log.info("【水位调试】id={}, 赔率A={}, 赔率B={}, 计算水位(截断3位)={}, profit.rollingLetBall={}, profit.rollingSize={}",
                    dto.getId(), oddsA, oddsB, water, profit.getRollingLetBall(), profit.getRollingSize());
            JSONObject successA = new JSONObject();
            JSONObject successB = new JSONObject();

            try {
                if (rollingOrderA == rollingOrderB) {
                    // 并行执行
                    CompletableFuture<JSONObject> betA = CompletableFuture.supplyAsync(() ->
                                    tryBet(username, dto.getEventIdA(), dto.getWebsiteIdA(), true, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, optimizing, admin.getSimulateBet(), betPreviewA),
                            betExecutor
                    );
                    CompletableFuture<JSONObject> betB = CompletableFuture.supplyAsync(() ->
                                    tryBet(username, dto.getEventIdB(), dto.getWebsiteIdB(), false, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, optimizing, admin.getSimulateBet(), betPreviewB),
                            betExecutor
                    );
                    CompletableFuture.allOf(betA, betB).join();
                    successA = betA.join();
                    successB = betB.join();
                } else if (rollingOrderA < rollingOrderB) {
                    // A 优先
                    successA = tryBet(username, dto.getEventIdA(), dto.getWebsiteIdA(), true, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, optimizing, admin.getSimulateBet(), betPreviewA);
                    if (successA.getBool("success")) {
                        successB = tryBet(username, dto.getEventIdB(), dto.getWebsiteIdB(), false, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, optimizing, admin.getSimulateBet(), betPreviewB);
                    }
                } else {
                    // B 优先
                    successB = tryBet(username, dto.getEventIdB(), dto.getWebsiteIdB(), false, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, optimizing, admin.getSimulateBet(), betPreviewB);
                    if (successB.getBool("success")) {
                        successA = tryBet(username, dto.getEventIdA(), dto.getWebsiteIdA(), true, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, optimizing, admin.getSimulateBet(), betPreviewA);
                    }
                }
            } catch (Exception e) {
                // 回滚投注次数
                limitManager.rollbackReservation(limitKey, reservationId);
                log.error("sweepwater={} 投注异常", dto.getId(), e);
            }

            dto.setBetSuccessA(successA.getBool("success", false));
            dto.setBetSuccessB(successB.getBool("success", false));

            // 计算初始水位（两边相加）
            BigDecimal finalWater = dto.getOddsA().add(dto.getOddsB());

            // 一方赔率为负数，则在结果上加 2,如果两个都是负数，就等于加4
            if (dto.getOddsA().compareTo(BigDecimal.ZERO) < 0) {
                finalWater = finalWater.add(BigDecimal.valueOf(2));
            }
            if (dto.getOddsB().compareTo(BigDecimal.ZERO) < 0) {
                finalWater = finalWater.add(BigDecimal.valueOf(2));
            }
            // ✅ 仅保留 3 位小数（不四舍五入）
            finalWater = finalWater.setScale(3, RoundingMode.DOWN);
            // 更新最终水位
            dto.setWater(String.valueOf(finalWater));
            log.info("id:{} 赔率a:{} 赔率b：{} 最终水位: {}", dto.getId(), dto.getOddsA(), dto.getOddsB(), finalWater);

            // 有一个执行了投注则缓存
            if (successA.getBool("isBet", false) || successB.getBool("isBet", false)) {
                // 记录投注成功到限流管理器（关键步骤）
                limitManager.confirmSuccess(limitKey, reservationId);
                dto.setCreateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
                // 设置扫水已投注
                sweepwaterService.setIsBet(username, sweepwaterDTO.getId());

                String json = JSONUtil.toJsonStr(dto);
                String date = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN);
                // 保存投注信息作为历史记录
                String key = KeyUtil.genKey(
                        RedisConstants.PLATFORM_BET_PREFIX,
                        username,
                        date,
                        dto.getId()
                );
                businessPlatformRedissonClient.getBucket(key).set(json);
                // 维护索引
                String indexKey = KeyUtil.genKey("INDEX", username,
                        date
                        );
                businessPlatformRedissonClient.getList(indexKey).add(key);

                // 保存投注信息作为实时记录
                String realTimeKey = KeyUtil.genKey(
                        RedisConstants.PLATFORM_BET_PREFIX,
                        username,
                        "realtime",
                        dto.getId()
                );
                businessPlatformRedissonClient.getBucket(realTimeKey).set(json);
                // 实时索引
                String realTimeIndexKey = KeyUtil.genKey("INDEX", username, "realtime");
                businessPlatformRedissonClient.getList(realTimeIndexKey).add(realTimeKey);
            } else {
                // 没有一个成功就回滚投注次数
                limitManager.rollbackReservation(limitKey, reservationId);
            }
        }

        // 最后关闭线程池
        PriorityTaskExecutor.shutdownExecutor(betExecutor);

        log.info("投注结束，总花费:{}毫秒", timerTotal.interval());
    }

    @FunctionalInterface
    public interface RetryableTask {
        boolean execute() throws Exception;
    }

    /**
     * 重试机制
     * @param maxAttempts
     * @param task
     * @return
     */
    private boolean retry(int maxAttempts, RetryableTask task) {
        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                if (task.execute()) {
                    return true;
                }
            } catch (Exception e) {
                log.info("投注第{}次尝试失败：", attempt + 1, e);
            }
            attempt++;
            try {
                // 加入一点延迟，避免连续快速失败
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    /**
     * 执行投注
     * @param username
     * @param eventId
     * @param websiteId
     * @param isA
     * @param dto
     * @param limitDTO
     * @param intervalDTO
     * @param amountDTO
     * @param simulateBet   是否模拟投注
     * @return
     */
    private JSONObject tryBet(String username,
                           String eventId,
                           String websiteId,
                           boolean isA,
                           boolean isUnilateral,
                           SweepwaterBetDTO dto,
                           LimitDTO limitDTO,
                           IntervalDTO intervalDTO,
                           BetAmountDTO amountDTO,
                           OptimizingDTO optimizing,
                           Integer simulateBet,
                           JSONObject betPreviewOpt) {
        JSONObject result = new JSONObject();

        if (dto.getLastOddsTimeA() == dto.getLastOddsTimeB()) {
            // 如果两个都是旧或者新,则不进行投注,不需要在前端显示
            result.putOpt("isBet", false);
            result.putOpt("success", false);
            return result;
        }
        String score = isA ? dto.getScoreA() : dto.getScoreB();
        String betTeamName = isA ? dto.getTeamA() : dto.getTeamB();
        // 构建投注参数并调用投注接口
        JSONObject params = buildBetParams(dto, amountDTO, isA);

        // 投注（带重试机制,默认重试0次,也就是不重试）
        int maxRetry = 0;
        int attempt = 0;
        boolean success = false;

        if (null != limitDTO.getRetry() && 1 == limitDTO.getRetry()) {
            // 获取重试次数
            maxRetry = limitDTO.getRetryCount();
        }
        log.info("执行投注逻辑，网站:{}, 需重试次数:{}", WebsiteType.getById(websiteId).getDescription(), maxRetry);
        while (attempt <= maxRetry && !success) {
            attempt++;
            try {
                boolean lastOddsTime = isA ? dto.getLastOddsTimeA() : dto.getLastOddsTimeB();

                if (simulateBet == 1) {
                    // 模拟投注
                    if (isUnilateral) {
                        if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 1
                                && lastOddsTime) {
                            // 单边旧投注,当前网站赔率是最新的，直接跳出不投注
                            result.putOpt("isBet", false);
                            result.putOpt("success", false);
                        } else if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 2
                                && !lastOddsTime) {
                            // 单边新投注,当前网站赔率不是最新的，直接跳出不投注
                            result.putOpt("isBet", false);
                            result.putOpt("success", false);
                        } else {
                            if (isA) {
                                dto.setBetTimeA(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                            } else {
                                dto.setBetTimeB(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                            }
                            result.putOpt("isBet", true);
                            result.putOpt("success", true);
                        }
                    } else {
                        // 双边投注
                        if (isA) {
                            dto.setBetTimeA(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                        } else {
                            dto.setBetTimeB(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                        }
                        result.putOpt("isBet", true);
                        result.putOpt("success", true);
                    }
                    return result;
                }
                if (isUnilateral) {
                    if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 1 && lastOddsTime) {
                        // 单边旧投注,当前网站赔率是最新的，直接跳出不投注
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        log.info("不满足单边投注旧， 网站:{}", WebsiteType.getById(websiteId).getDescription());
                        return result;
                    } else if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 2 && !lastOddsTime) {
                        // 单边新投注,当前网站赔率不是最新的，直接跳出不投注
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        log.info("不满足单边投注新， 网站:{}", WebsiteType.getById(websiteId).getDescription());
                        return result;
                    } else {
                        log.info("满足单边投注， 网站:{}", WebsiteType.getById(websiteId).getDescription());
                    }

                    if (optimizing.getContrastNum() != null && optimizing.getContrastNum() == 1) {
                        // 开启了不投注对头单，意思是只要投了某一队，后续就不会再投注另一队
                        String successKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_SUCCESS_PREFIX, username, websiteId, eventId);
                        List<String> successList = businessPlatformRedissonClient.getList(successKey);
                        if (successList != null && !successList.isEmpty()) {
                            JSONObject jsonObject = JSONUtil.parseObj(successList.get(0));
                            if (!jsonObject.getStr("betTeamName").equals(betTeamName)) {
                                // 必须投注同一支队伍
                                result.putOpt("isBet", false);
                                result.putOpt("success", false);
                                return result;
                            }
                        }
                    }
                }

                // 投注预览
                JSONObject betPreview = new JSONObject();
                try {
                    log.info("用户 {}, 网站:{} 进入最终投注预览", username, WebsiteType.getById(websiteId).getDescription());
                    betPreview = buildBetInfo(username, betTeamName, websiteId, params, dto);
                    log.info("用户 {}, 网站:{} 进入最终投注预览，betPreview={}", username, WebsiteType.getById(websiteId).getDescription(), betPreview);
                    if (betPreview == null || betPreview.isEmpty()) {
                    /*log.info("用户 {}, 网站:{} 投注预览失败，eventId={}", username, WebsiteType.getById(websiteId).getDescription(), eventId);
                    result.putOpt("isBet", false);
                    result.putOpt("success", false);
                    // 撤销预占额度
                    rollbackBetLimit(limitKey, score);
                    // 撤销预占间隔
                    releaseIntervalKey(intervalKey);
                    return result;*/
                        betPreview = betPreviewOpt;
                    }
                /*else {
                    log.info("用户 {}, 网站:{} 投注预览成功，eventId={}, isA={}, 预览结果={}, 原本手动解析的betInfo={}", username, WebsiteType.getById(websiteId).getDescription(), eventId, isA, betPreview, isA ? dto.getBetInfoA() : dto.getBetInfoB());
                    if (isA) {
                        dto.setBetInfoA(betPreview.getJSONObject("betInfo"));
                    } else {
                        dto.setBetInfoB(betPreview.getJSONObject("betInfo"));
                    }
                }*/
                } catch (Exception e) {
                    betPreview = betPreviewOpt;
                }
                JSONObject betInfo = betPreview.getJSONObject("betInfo");
                // 更新赔率
                BigDecimal odds = betInfo.getBigDecimal("oddsValue");
                if (isA) {
                    dto.setOddsA(odds);
                } else {
                    dto.setOddsB(odds);
                }

                // 新二投注前获取一下赔率列表,为了确认需要投注的分数盘还存在
                if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                    String oddsId = isA ? dto.getOddsIdA() : dto.getOddsIdB();
                    String leagueId = isA ? dto.getLeagueIdA() : dto.getLeagueIdB();
                    String league = isA ? dto.getLeagueNameA() : dto.getLeagueNameB();
                    String teamName = isA ? dto.getTeamA() : dto.getTeamB();
                    String handicap = isA ? getHandicapRange(dto.getHandicapA()) : getHandicapRange(dto.getHandicapB());
                    if (!getHandicapRange(betInfo.getStr("handicap")).equals(handicap)) {
                        log.info("投注前, 用户 {}, 网站:{} 投注预览的盘口分:{}和扫水时需要投注的盘口分:{}不匹配，联赛={}, 球队名={}", username, websiteId, getHandicapRange(betInfo.getStr("handicap")), handicap, league, teamName);
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    }
                    // 新二：一次拉整个联赛赔率
                    JSONArray leagues = (JSONArray) handicapApi.eventsOdds(username, websiteId, leagueId, eventId);
                    if (leagues == null || leagues.isEmpty()) {
                        log.info("投注前, 用户 {}, 网站:{} 获取赔率列表失败，联赛={}, 球队名={}", username, websiteId, league, teamName);
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    }
                    JSONObject teamEvent = findEventByLeagueId(leagues, leagueId, teamName);
                    if (teamEvent == null || teamEvent.isEmpty()) {
                        log.info("投注前, 用户 {}, 网站:{} 获取指定赔率信息失败，联赛={}, 球队名={}", username, websiteId, league, teamName);
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    }
                    JSONObject typeJson = teamEvent.getJSONObject(dto.getType());
                    if (typeJson == null || typeJson.isEmpty()) {
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    }
                    JSONObject handicapOdds = typeJson.getJSONObject(dto.getHandicapType());
                    if (!handicapOdds.containsKey(handicap)) {
                        log.info("投注前, 用户 {}, 网站:{} 获取指定赔率信息检查需要投注的盘:{}不存在，联赛={}, 球队名={}", username, websiteId, handicap, league, teamName);
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    }
                    if (!oddsId.equals(handicapOdds.getJSONObject(handicap).getStr("id"))) {
                        log.info("投注前, 用户 {}, 网站:{} 获取指定赔率信息检查需要投注的盘:{}存在,但是oddsId不一致,投注的oddsId:{},检测的oddsId:{},联赛={}, 球队名={}", username, websiteId, handicap, oddsId, handicapOdds.getJSONObject(handicap).getStr("id"), league, teamName);
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    }
                }

                // 投注
                log.info("用户 {}, 网站:{} 开始投注，eventId={}, isA={}, 投注参数={}", username, WebsiteType.getById(websiteId).getDescription(), eventId, isA, params);
                Object betResult = handicapApi.bet(username, websiteId, params, betPreview.getJSONObject("betInfo"), betPreview.getJSONObject("betPreview"));
                if (isA) {
                    dto.setBetTimeA(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                } else {
                    dto.setBetTimeB(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                }
                log.info("用户 {}, 网站:{} 开始投注，eventId={}, isA={}, 投注结果={}", username, WebsiteType.getById(websiteId).getDescription(), eventId, isA, betResult);
                if (betResult != null && parseBetSuccess(betResult, dto, isA)) {
                    // 异步写入 Redis 列表
                    CompletableFuture.runAsync(() -> {
                        try {
                            String successKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_SUCCESS_PREFIX, username, websiteId, eventId);
                            // 投注成功则记录

                            // 处理单个结果和拆分结果的情况
                            JSONObject betInfoToStore = null;
                            if (betResult instanceof JSONObject) {
                                betInfoToStore = ((JSONObject) betResult).getJSONObject("betInfo");
                            } else if (betResult instanceof List) {
                                List<JSONObject> betResults = (List<JSONObject>) betResult;
                                if (!betResults.isEmpty()) {
                                    // 取第一个结果的betInfo
                                    betInfoToStore = betResults.get(0).getJSONObject("betInfo");
                                }
                            }

                            if (betInfoToStore != null) {
                                businessPlatformRedissonClient.getList(successKey).add(betInfoToStore);
                            }
                        } catch (Exception e) {
                            log.info("异步记录投注成功失败:", e);
                        }
                    });
                    result.putOpt("isBet", true);
                    result.putOpt("success", true);
                    result.putOpt("betInfo", JSONUtil.parseObj(betResult).getJSONObject("betInfo"));
                    success = true;
                } else if (betResult != null) {
                    result.putOpt("isBet", true);
                    result.putOpt("success", false);
                    result.putOpt("betInfo", JSONUtil.parseObj(betResult).getJSONObject("betInfo"));
                } else {
                    result.putOpt("isBet", true);
                    result.putOpt("success", false);
                    result.putOpt("betInfo", null);
                }
            } catch (Exception e) {
                log.info("用户 {}, 网站:{}, 投注尝试第 {} 次失败：{}", username, WebsiteType.getById(websiteId).getDescription(), attempt, e.getMessage(), e);
                // 短暂等待再重试
                /*try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }*/
            }
        }
        return result;
    }

    /**
     * 替换handicap符号
     * @param handicap
     * @return
     */
    public static String getHandicapRange(String handicap) {
        // 先移除负号
        String withoutNegative = handicap.replace("-", "");
        // 再替换斜杠
        return withoutNegative.replaceAll(" / ", "-");
    }

    /**
     * 根据联赛id匹配对应联赛，再根据球队赛事id匹配对应球队赛事
     * @param leagueList
     * @param leagueId
     * @param teamName
     * @return
     */
    private JSONObject findEventByLeagueId(JSONArray leagueList, String leagueId, String teamName) {

        for (Object eventObj : leagueList) {
            JSONObject event = (JSONObject) eventObj;

            // 1) 先定位到目标联赛
            if (!leagueId.equals(event.getStr("id"))) {
                continue;
            }

            // 2) 在联赛 events 中找到对应球队
            JSONArray eventArray = event.getJSONArray("events");
            if (eventArray == null || eventArray.isEmpty()) {
                return null;
            }

            for (Object obj : eventArray) {
                JSONObject teamEvent = (JSONObject) obj;
                if (teamName.equals(teamEvent.getStr("name"))) {
                    return teamEvent; // ✅ 直接返回匹配球队的 event
                }
            }

            return null; // 找到联赛但没匹配球队
        }

        return null; // 没找到联赛
    }

    /**
     * 检测并设置间隔时间
     * @param intervalKey
     * @param intervalMillis
     * @return
     */
    public boolean tryLockIntervalKey(String intervalKey, long intervalMillis) {
        RBucket<String> bucket = businessPlatformRedissonClient.getBucket(intervalKey);
        String lastTimeStr = bucket.get();
        long now = System.currentTimeMillis();

        Long lastTime = null;
        if (lastTimeStr != null) {
            try {
                lastTime = Long.parseLong(lastTimeStr);
            } catch (NumberFormatException e) {
                log.warn("投注间隔键解析失败：key={}, value={}", intervalKey, lastTimeStr);
            }
        }

        if (lastTime != null && now - lastTime < intervalMillis) {
            return false;
        }

        // 设置新时间并返回成功
        bucket.set(String.valueOf(now), Duration.ofHours(24)); // 存成字符串
        log.info("设置投注间隔时间：key={}, now={}, 上次时间={}", intervalKey, now, lastTime);
        return true;
    }

    /**
     * 投注失败时释放投注时间
     * @param intervalKey
     */
    public void releaseIntervalKey(String intervalKey) {
        businessPlatformRedissonClient.getBucket(intervalKey).delete();
    }

    /**
     * 进行投注预览
     * @param username
     * @param websiteId
     * @param params
     * @return
     */
    public JSONObject buildBetInfo(String username, String betTeamName, String websiteId, JSONObject params, SweepwaterBetDTO sweepwaterBetDTO) {
        JSONObject betPreviewResult = new JSONObject();
        JSONObject betInfo = new JSONObject();

        // 重试10次
        int maxRetry = 10;
        int attempt = 0;
        JSONObject betPreviewJson = null;

        // 重试机制
        // while (attempt < maxRetry) {
            Object betPreview = handicapApi.betPreview(username, websiteId, params);
            if (betPreview == null) {
                attempt++;
                log.info("[网站:{}][投注预览重试] 第 {} 次失败：返回为 null", WebsiteType.getById(websiteId).getDescription(), attempt);
                try {
                    Thread.sleep(300); // 添加延迟
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("投注预览重试等待被中断，终止重试流程");
                    return null;
                }
                // continue;
                return null;
            }

            betPreviewJson = JSONUtil.parseObj(betPreview);
            Boolean success = betPreviewJson.getBool("success", false);
            if (!success) {
                attempt++;
                log.info("[网站:{}][投注预览重试] 第 {} 次失败：success=false，返回信息：{}", WebsiteType.getById(websiteId).getDescription(), attempt, betPreviewJson);
                // continue;
                return null;
            }
            // 成功跳出循环
            // break;
        // }

        // 如果超过重试次数仍失败
        if (betPreviewJson == null) {
            log.warn("网站:{},投注预览重试10次依然失败", WebsiteType.getById(websiteId).getDescription());
            return null;
        }

        betPreviewResult.putOpt("betPreview", betPreviewJson);

        BigDecimal maxBet = BigDecimal.ZERO;
        BigDecimal minBet = BigDecimal.ZERO;
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            JSONArray data = betPreviewJson.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                return null;
            }
            for (Object obj : data) {
                JSONObject objJson = JSONUtil.parseObj(obj);
                betInfo.putOpt("league", objJson.getStr("league"));
                betInfo.putOpt("team", objJson.getStr("homeTeam") + " -vs- " + objJson.getStr("awayTeam"));
                betInfo.putOpt("marketTypeName", "");
                betInfo.putOpt("marketName", objJson.getStr("selection"));
                betInfo.putOpt("odds", objJson.getStr("selection") + " " + objJson.getStr("handicap"));
                betInfo.putOpt("oddsValue", objJson.getStr("odds"));
                betInfo.putOpt("handicap", objJson.getStr("handicap"));
                betInfo.putOpt("amount", params.getStr("stake"));
                betInfo.putOpt("betTeamName", betTeamName);
                maxBet = objJson.getBigDecimal("maxStake");
                minBet = objJson.getBigDecimal("minStake");
            }
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            JSONObject data = betPreviewJson.getJSONObject("data");
            JSONObject betTicket = data.getJSONObject("betTicket");
            betInfo.putOpt("league", data.getStr("leagueName"));
            betInfo.putOpt("team", data.getStr("eventName"));
            betInfo.putOpt("marketTypeName", data.getStr("marketTypeName"));
            betInfo.putOpt("marketName", data.getStr("name"));
            betInfo.putOpt("odds", data.getStr("name") + " " + betTicket.getStr("handicap"));
            betInfo.putOpt("oddsValue", betTicket.getStr("odds"));
            betInfo.putOpt("handicap", data.getStr("handicap"));
            betInfo.putOpt("amount", params.getStr("stake"));
            betInfo.putOpt("betTeamName", betTeamName);
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            JSONObject serverresponse = betPreviewJson.getJSONObject("data");
            String fastCheck = serverresponse.getStr("fast_check");
            String marketName = "";
            String marketTypeName = "";
            if (fastCheck.contains("REH")) {
                marketName = serverresponse.getStr("team_name_h");
                marketTypeName = "让球盘";
            } else if (fastCheck.contains("REC")) {
                marketName = serverresponse.getStr("team_name_c");
                marketTypeName = "让球盘";
            } else if (fastCheck.contains("ROUC")) {
                marketName = "大盘";
                marketTypeName = "大小盘";
            } else if (fastCheck.contains("ROUH")) {
                marketName = "小盘";
                marketTypeName = "大小盘";
            }
            betInfo.putOpt("league", serverresponse.getStr("league_name"));
            betInfo.putOpt("team", serverresponse.getStr("team_name_h") + " -vs- " + serverresponse.getStr("team_name_c"));
            betInfo.putOpt("marketTypeName", marketTypeName);
            betInfo.putOpt("marketName", marketName);
            betInfo.putOpt("odds", marketName + " " + serverresponse.getStr("spread"));
            betInfo.putOpt("oddsValue", serverresponse.getStr("ioratio"));
            betInfo.putOpt("handicap", serverresponse.getStr("spread"));
            betInfo.putOpt("amount", params.getStr("golds"));
            betInfo.putOpt("betTeamName", betTeamName);
            maxBet = serverresponse.getBigDecimal("gold_gmax");
            minBet = serverresponse.getBigDecimal("gold_gmin");
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            JSONObject oddsInfo = betPreviewJson.getJSONObject("data").getJSONObject("oddsInfo");
            String firstKey = oddsInfo.keySet().iterator().next();  // 拿第一个 key
            JSONObject odd = oddsInfo.getJSONObject(firstKey);
            String marketName = "";
            String marketTypeName = "";
            if ("letBall".equals(sweepwaterBetDTO.getHandicapType())) {
                marketTypeName = "让球盘";
                if ("h".equals(params.getStr("option"))) {
                    // 主队
                    marketName = null != sweepwaterBetDTO.getTeamVSHA() ? sweepwaterBetDTO.getTeamVSHA() : sweepwaterBetDTO.getTeamVSHB();
                } else {
                    // 客队
                    marketName = null != sweepwaterBetDTO.getTeamVSAB() ? sweepwaterBetDTO.getTeamVSAB() : sweepwaterBetDTO.getTeamVSAA();
                }
            } else if ("overSize".equals(sweepwaterBetDTO.getHandicapType())) {
                marketTypeName = "大小盘";
                if ("h".equals(params.getStr("option"))) {
                    // 主队
                    marketName = "大盘";
                } else {
                    // 客队
                    marketName = "小盘";
                }
            }
            String teamH = null != sweepwaterBetDTO.getTeamVSHA() ? sweepwaterBetDTO.getTeamVSHA() : sweepwaterBetDTO.getTeamVSHB();
            String teamA = null != sweepwaterBetDTO.getTeamVSAB() ? sweepwaterBetDTO.getTeamVSAB() : sweepwaterBetDTO.getTeamVSAA();
            betInfo.putOpt("league", params.getStr("league"));
            betInfo.putOpt("team", teamH + " -vs- " + teamA);
            betInfo.putOpt("marketTypeName", marketTypeName);
            betInfo.putOpt("marketName", marketName);
            betInfo.putOpt("odds", marketName + " " + odd.getStr("point"));
            betInfo.putOpt("oddsValue", odd.getStr("price"));
            betInfo.putOpt("handicap", odd.getStr("point"));
            betInfo.putOpt("amount", params.getStr("stake"));
            betInfo.putOpt("uid", odd.getStr("uid"));
            betInfo.putOpt("betTeamName", betTeamName);
            maxBet = betPreviewJson.getJSONObject("data").getBigDecimal("maxBet");
            minBet = betPreviewJson.getJSONObject("data").getBigDecimal("minBet");
        }

        betPreviewResult.putOpt("betInfo", betInfo);
        betPreviewResult.putOpt("maxBet", maxBet);
        betPreviewResult.putOpt("minBet", minBet);
        return betPreviewResult;
    }

    /**
     * 校验比分限制和总投注限制
     * 下注前检查 + 锁定额度
     * @param limitKey
     * @param score
     * @param limitDTO
     * @return
     */
    private boolean tryReserveBetLimit(String limitKey, String score, LimitDTO limitDTO) {
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

            if (scoreCount >= limitDTO.getBetLimitScore() || totalCount >= limitDTO.getBetLimitGame()) {
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

    /**
     * 获取当前联赛总投注次数
     * @param limitKey
     * @param limitDTO
     * @return
     */
    public boolean getBetLimit(String limitKey, LimitDTO limitDTO) {
        try {
            RBucket<Object> bucket = businessPlatformRedissonClient.getBucket(limitKey);
            JSONObject counterJson = Optional.ofNullable(bucket.get())
                    .map(JSONUtil::parseObj)
                    .orElse(new JSONObject());

            int totalCount = counterJson.values().stream()
                    .filter(val -> val instanceof Integer)
                    .mapToInt(val -> (Integer) val)
                    .sum();

            if (totalCount >= limitDTO.getBetLimitGame()) {
                return true;
            }

            return false;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 投注失败后撤销配额
     * @param limitKey
     * @param score
     */
    private void rollbackBetLimit(String limitKey, String score) {
        String lockKey = "lock:betlimit:" + limitKey;
        RLock lock = businessPlatformRedissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(2, 5, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("回滚比分限制锁失败，limitKey={}", limitKey);
                return;
            }

            RBucket<Object> bucket = businessPlatformRedissonClient.getBucket(limitKey);
            JSONObject counterJson = Optional.ofNullable(bucket.get())
                    .map(JSONUtil::parseObj)
                    .orElse(new JSONObject());

            int scoreCount = counterJson.getInt(score, 0);
            if (scoreCount > 0) {
                counterJson.putOpt(score, scoreCount - 1);
                bucket.set(counterJson, Duration.ofHours(24));
            }

        } catch (Exception e) {
            log.error("回滚比分限制失败，limitKey={}, score={}", limitKey, score, e);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    /**
     * 构建投注参数
     */
    private JSONObject buildBetParams(SweepwaterBetDTO sweepwaterDTO, BetAmountDTO amount, boolean isA) {
        JSONObject params = new JSONObject();
        String websiteId = isA ? sweepwaterDTO.getWebsiteIdA() : sweepwaterDTO.getWebsiteIdB();

        if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            params.putOpt("marketSelectionId", isA ? sweepwaterDTO.getOddsIdA() : sweepwaterDTO.getOddsIdB());
            params.putOpt("stake", amount.getAmountZhiBo());
            params.putOpt("odds", isA ? sweepwaterDTO.getOddsA() : sweepwaterDTO.getOddsB());
            params.putOpt("decimalOdds", isA ? sweepwaterDTO.getDecimalOddsA() : sweepwaterDTO.getDecimalOddsB());
            params.putOpt("handicap", isA ? sweepwaterDTO.getHandicapA() : sweepwaterDTO.getHandicapB());
            params.putOpt("score", isA ? sweepwaterDTO.getScoreA() : sweepwaterDTO.getScoreB());
        } else if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            params.putOpt("stake", amount.getAmountPingBo());
            params.putOpt("odds", isA ? sweepwaterDTO.getOddsA() : sweepwaterDTO.getOddsB());
            params.putOpt("oddsId", isA ? sweepwaterDTO.getOddsIdA() : sweepwaterDTO.getOddsIdB());
            params.putOpt("selectionId", isA ? sweepwaterDTO.getSelectionIdA() : sweepwaterDTO.getSelectionIdB());
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            params.putOpt("gid", isA ? sweepwaterDTO.getOddsIdA() : sweepwaterDTO.getOddsIdB());
            params.putOpt("golds", amount.getAmountXinEr());
            params.putOpt("oddFType", isA ? sweepwaterDTO.getStrongA() : sweepwaterDTO.getStrongB());
            params.putOpt("gtype", isA ? sweepwaterDTO.getGTypeA() : sweepwaterDTO.getGTypeB());
            params.putOpt("wtype", isA ? sweepwaterDTO.getWTypeA() : sweepwaterDTO.getWTypeB());
            params.putOpt("rtype", isA ? sweepwaterDTO.getRTypeA() : sweepwaterDTO.getRTypeB());
            params.putOpt("choseTeam", isA ? sweepwaterDTO.getChoseTeamA() : sweepwaterDTO.getChoseTeamB());
            params.putOpt("ioratio", isA ? sweepwaterDTO.getOddsA() : sweepwaterDTO.getOddsB());
            params.putOpt("con", isA ? sweepwaterDTO.getConA() : sweepwaterDTO.getConB());
            params.putOpt("ratio", isA ? sweepwaterDTO.getRatioA() : sweepwaterDTO.getRatioB());
            params.putOpt("autoOdd", "N");  // 是否接受更好的赔率
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            params.putOpt("stake", amount.getAmountSbo());
            params.putOpt("league", isA ? sweepwaterDTO.getLeagueNameA() : sweepwaterDTO.getLeagueNameB());
            params.putOpt("team", isA ? sweepwaterDTO.getTeamA() : sweepwaterDTO.getTeamB());
            params.putOpt("eventId", isA ? sweepwaterDTO.getEventIdA() : sweepwaterDTO.getEventIdB());
            params.putOpt("marketType", "letBall".equals(sweepwaterDTO.getHandicapType()) ? 1 : 3);     // 让球盘是1，大小盘是3
            params.putOpt("oddsId", isA ? sweepwaterDTO.getOddsIdA() : sweepwaterDTO.getOddsIdB());
            params.putOpt("option", isA ? (sweepwaterDTO.getIsHomeA() ? "h" : "a") : (sweepwaterDTO.getIsHomeB() ? "h" : "a"));
        }
        return params;
    }

    /**
     * 解析投注返回
     */
    private boolean parseBetSuccess(Object betResult, SweepwaterBetDTO sweepwaterDTO, boolean isA) {
        // 处理单个投注结果和拆分投注结果
        if (betResult instanceof JSONObject) {
            return parseSingleBetResult((JSONObject) betResult, sweepwaterDTO, isA);
        } else if (betResult instanceof List) {
            return parseSplitBetResults((List<JSONObject>) betResult, sweepwaterDTO, isA);
        }
        return false;
    }

    /**
     * 处理单个投注结果
     */
    private boolean parseSingleBetResult(JSONObject betResultJson, SweepwaterBetDTO sweepwaterDTO, boolean isA) {
        if (!betResultJson.getBool("success", false)) {
            return false;
        }

        String betId = extractBetId(betResultJson, isA ? sweepwaterDTO.getWebsiteIdA() : sweepwaterDTO.getWebsiteIdB());
        String account = betResultJson.getStr("account");
        String accountId = betResultJson.getStr("accountId");

        if (StringUtils.isNotBlank(betId)) {
            setBetInfoToDTO(sweepwaterDTO, isA, betId, account, accountId);
            return true;
        }
        return false;
    }

    /**
     * 处理拆分投注结果
     */
    private boolean parseSplitBetResults(List<JSONObject> betResults, SweepwaterBetDTO sweepwaterDTO, boolean isA) {
        if (betResults == null || betResults.isEmpty()) {
            return false;
        }

        String websiteId = isA ? sweepwaterDTO.getWebsiteIdA() : sweepwaterDTO.getWebsiteIdB();
        List<String> betIds = new ArrayList<>();
        String account = null;
        String accountId = null;
        boolean hasSuccess = false;

        // 遍历所有拆分投注结果
        for (JSONObject betResult : betResults) {
            if (betResult.getBool("success", false)) {
                hasSuccess = true;

                String singleBetId = extractBetId(betResult, websiteId);
                if (StringUtils.isNotBlank(singleBetId)) {
                    betIds.add(singleBetId);
                }

                // 取第一个成功的账号信息（所有拆分投注应该使用同一个账号）
                if (account == null) {
                    account = betResult.getStr("account");
                    accountId = betResult.getStr("accountId");
                }
            } else {
                log.warn("拆分投注中有一个失败: {}", betResult);
            }
        }

        if (hasSuccess && !betIds.isEmpty()) {
            // 将所有投注ID用逗号分隔存储
            String combinedBetId = String.join(",", betIds);
            setBetInfoToDTO(sweepwaterDTO, isA, combinedBetId, account, accountId);
            return true;
        }
        return false;
    }

    /**
     * 从投注结果中提取投注ID
     */
    private String extractBetId(JSONObject betResultJson, String websiteId) {
        if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            return betResultJson.getJSONObject("data").getJSONObject("betInfo").getStr("betId");
        } else if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            return betResultJson.getJSONArray("response").getJSONObject(0).getStr("wagerId");
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            return betResultJson.getJSONObject("data").getJSONObject("serverresponse").getStr("ticket_id");
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            return betResultJson.getJSONObject("data").getStr("transId");
        }
        return null;
    }

    /**
     * 设置投注信息到DTO
     */
    private void setBetInfoToDTO(SweepwaterBetDTO sweepwaterDTO, boolean isA, String betId, String account, String accountId) {
        if (isA) {
            sweepwaterDTO.setBetIdA(betId);
            sweepwaterDTO.setBetAccountA(account);
            sweepwaterDTO.setBetAccountIdA(accountId);
        } else {
            sweepwaterDTO.setBetIdB(betId);
            sweepwaterDTO.setBetAccountB(account);
            sweepwaterDTO.setBetAccountIdB(accountId);
        }
    }

    /**
     * 获取盘口历史未结投注单进行保存
     * @param username
     */
    public void unsettledBet(String username, boolean isRealTime) {
        // 计时开始
        TimeInterval timerTotal = DateUtil.timer();

        // 1. 获取当天所有投注记录
        List<SweepwaterBetDTO> bets = isRealTime ? getRealTimeBets(username, null, 1, 99999).getRecords() : getBets(username, null, null, null, 1, 99999).getRecords();
        if (CollUtil.isEmpty(bets)) {
            log.info("没有投注记录，直接返回");
            return;
        }

        // 2. 根据投注条数和 CPU 动态构造线程池
        int cpuCoreCount  = Math.min(Runtime.getRuntime().availableProcessors() * 4, 100);
        int corePoolSize  = Math.min(bets.size(), cpuCoreCount);
        int maxPoolSize   = Math.max(bets.size(), cpuCoreCount);
        ExecutorService betExecutor = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactoryBuilder().setNameFormat("unsettled-task-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 并发安全地存放各网站的未结注单列表 key是：网站ID_账户ID
        Map<String, JSONArray> unsettleds = new ConcurrentHashMap<>();
        // 3. 为每条 bet 并发创建任务，同时发 A、B 两个网站请求
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (SweepwaterBetDTO bet : bets) {
            String keyA = bet.getWebsiteIdA() + "_" + bet.getBetAccountIdA();
            String keyB = bet.getWebsiteIdB() + "_" + bet.getBetAccountIdB();
            // 网站A
            if (!StringUtils.isBlank(bet.getBetAccountIdA()) && bet.getBetSuccessA()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    unsettleds.computeIfAbsent(keyA, key -> {
                        Object unsettledA = handicapApi.unsettled(username, bet.getWebsiteIdA(), bet.getBetAccountIdA());
                        if (unsettledA != null) {
                            JSONArray jsonArray = JSONUtil.parseArray(unsettledA);
                            log.info("获取到网站A:{} 账户:{} 未结注单 {} 条", bet.getWebsiteIdA(), bet.getBetAccountIdA(), jsonArray.size());
                            return jsonArray;
                        }
                        return new JSONArray(); // 注意：防止 null，返回空的
                    });
                }, betExecutor));
            }

            // 网站B
            if (!StringUtils.isBlank(bet.getBetAccountIdB()) && bet.getBetSuccessB()) {
                futures.add(CompletableFuture.runAsync(() -> {
                    unsettleds.computeIfAbsent(keyB, key -> {
                        Object unsettledB = handicapApi.unsettled(username, bet.getWebsiteIdB(), bet.getBetAccountIdB());
                        if (unsettledB != null) {
                            JSONArray jsonArray = JSONUtil.parseArray(unsettledB);
                            log.info("获取到网站B:{} 账户:{} 未结注单 {} 条", bet.getWebsiteIdB(), bet.getBetAccountIdB(), jsonArray.size());
                            return jsonArray;
                        }
                        return new JSONArray();
                    });
                }, betExecutor));
            }
        }

        // 等待所有并发任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        // 优雅关闭线程池
        PriorityTaskExecutor.shutdownExecutor(betExecutor);

        if (unsettleds.isEmpty()) {
            log.info("未获取到任何未结注单，退出");
            return;
        }

        // 4. 同步遍历 bets，匹配未结注单并批量写回 Redis
        RBatch batch = businessPlatformRedissonClient.createBatch();
        for (SweepwaterBetDTO bet : bets) {
            boolean updated = false;
            if (bet.getBetInfoA() == null) {
                JSONArray arrA = unsettleds.get(bet.getWebsiteIdA() + "_" + bet.getBetAccountIdA());
                if (arrA != null) {
                    for (Object o : arrA) {
                        JSONObject j = JSONUtil.parseObj(o);
                        if (j.getStr("betId").contains(bet.getBetIdA())) {
                            bet.setBetInfoA(j);
                            updated = true;
                            break;
                        }
                    }
                }
            }

            if (bet.getBetInfoB() == null) {
                JSONArray arrB = unsettleds.get(bet.getWebsiteIdB() + "_" + bet.getBetAccountIdB());
                if (arrB != null) {
                    for (Object o : arrB) {
                        JSONObject j = JSONUtil.parseObj(o);
                        if (j.getStr("betId").contains(bet.getBetIdB())) {
                            bet.setBetInfoB(j);
                            updated = true;
                            break;
                        }
                    }
                }
            }
            if (updated) {
                String key = null;
                if (isRealTime) {
                    key = KeyUtil.genKey(
                            RedisConstants.PLATFORM_BET_PREFIX,
                            username,
                            LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN),
                            bet.getId()
                    );
                } else {
                    key = KeyUtil.genKey(
                            RedisConstants.PLATFORM_BET_PREFIX,
                            username,
                            "realtime",
                            bet.getId()
                    );
                }
                batch.getBucket(key).setAsync(JSONUtil.toJsonStr(bet));
            }
        }
        // 一次性执行所有 Redis 写入
        batch.execute();

        log.info("获取未结注单结束，总耗时 {} ms", timerTotal.interval());
    }

}
