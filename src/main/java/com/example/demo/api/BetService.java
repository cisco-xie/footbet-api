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
import com.example.demo.core.result.PageResult;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import com.example.demo.model.dto.settings.BetAmountDTO;
import com.example.demo.model.dto.settings.IntervalDTO;
import com.example.demo.model.dto.settings.LimitDTO;
import com.example.demo.model.dto.settings.ProfitDTO;
import com.example.demo.model.dto.sweepwater.SweepwaterDTO;
import com.example.demo.model.vo.WebsiteVO;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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

    /**
     * 清空实时投注
     * @param username
     */
    public void betClear(String username) {
        String pattern = KeyUtil.genKey(RedisConstants.PLATFORM_BET_PREFIX, username, "realtime", "*", "*");
        businessPlatformRedissonClient.getKeys().deleteByPattern(pattern);
    }

    /**
     * 获取实时投注记录
     * @param username
     * @return
     */
    public PageResult<SweepwaterBetDTO> getRealTimeBets(
            String username, String teamName, Integer pageNum, Integer pageSize) {

        // 当前日期
        String date = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN);

        if (pageNum == null || pageNum < 1) pageNum = 1;
        if (pageSize == null || pageSize < 1) pageSize = 10;

        // 实时索引 key
        String indexKey = KeyUtil.genKey("INDEX", username, "realtime", date);
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
                            if ("success".equalsIgnoreCase(success)) matchSuccess = betSuccess;
                            else if ("fail".equalsIgnoreCase(success)) matchSuccess = !betSuccess;
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
            SweepwaterBetDTO dto = BeanUtil.copyProperties(sweepwaterDTO, SweepwaterBetDTO.class);
            dto.setIsUnilateral(isUnilateral);
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

            int simulateBetA = websites.stream()
                    .filter(w -> w.getId().equals(dto.getWebsiteIdA()))
                    .map(WebsiteVO::getSimulateBet)
                    .findFirst()
                    .orElse(0);

            int simulateBetB = websites.stream()
                    .filter(w -> w.getId().equals(dto.getWebsiteIdB()))
                    .map(WebsiteVO::getSimulateBet)
                    .findFirst()
                    .orElse(0);

            // 是否模拟投注
            int simulateA;
            if (admin.getSimulateBet() == 1) {
                simulateA = 1;
            } else {
                if (simulateBetA == 1) {
                    simulateA = 1;
                } else {
                    simulateA = 0;
                }
            }

            // 是否模拟投注
            int simulateB;
            if (admin.getSimulateBet() == 1) {
                simulateB = 1;
            } else {
                if (simulateBetB == 1) {
                    simulateB = 1;
                } else {
                    simulateB = 0;
                }
            }
            JSONObject successA = new JSONObject();
            JSONObject successB = new JSONObject();
            try {
                if (rollingOrderA == rollingOrderB) {
                    // 并行执行
                    CompletableFuture<JSONObject> betA = CompletableFuture.supplyAsync(() ->
                                    tryBet(username, dto.getEventIdA(), dto.getWebsiteIdA(), true, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, simulateA),
                            betExecutor
                    );
                    CompletableFuture<JSONObject> betB = CompletableFuture.supplyAsync(() ->
                                    tryBet(username, dto.getEventIdB(), dto.getWebsiteIdB(), false, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, simulateB),
                            betExecutor
                    );
                    CompletableFuture.allOf(betA, betB).join();
                    successA = betA.join();
                    successB = betB.join();
                } else if (rollingOrderA < rollingOrderB) {
                    // A 优先
                    successA = tryBet(username, dto.getEventIdA(), dto.getWebsiteIdA(), true, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, simulateA);
                    if (successA.getBool("success")) {
                        successB = tryBet(username, dto.getEventIdB(), dto.getWebsiteIdB(), false, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, simulateB);
                    }
                } else {
                    // B 优先
                    successB = tryBet(username, dto.getEventIdB(), dto.getWebsiteIdB(), false, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, simulateA);
                    if (successB.getBool("success")) {
                        successA = tryBet(username, dto.getEventIdA(), dto.getWebsiteIdA(), true, isUnilateral, dto, limitDTO, intervalDTO, amountDTO, simulateB);
                    }
                }
            } catch (Exception e) {
                log.error("sweepwater={} 投注异常", dto.getId(), e);
            }

            // 设置已投注
            sweepwaterService.setIsBet(username, dto.getId());

            dto.setBetSuccessA(successA.getBool("success"));
            dto.setBetSuccessB(successB.getBool("success"));
            
            // 有一个执行了投注则缓存
            if (successA.getBool("isBet") || successB.getBool("isBet")) {
                String date = LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN);
                // 保存投注信息作为历史记录
                String key = KeyUtil.genKey(
                        RedisConstants.PLATFORM_BET_PREFIX,
                        username,
                        date,
                        dto.getId()
                );
                businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(dto));

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
                        date,
                        dto.getId()
                );
                businessPlatformRedissonClient.getBucket(realTimeKey).set(JSONUtil.toJsonStr(dto));

                // 实时索引
                String realTimeIndexKey = KeyUtil.genKey("INDEX", username, "realtime",
                        date);
                businessPlatformRedissonClient.getList(realTimeIndexKey).add(realTimeKey);
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
                           Integer simulateBet) {
        JSONObject result = new JSONObject();
        String score = isA ? dto.getScoreA() : dto.getScoreB();
        // 构建投注参数并调用投注接口
        JSONObject params = buildBetParams(dto, amountDTO, isA);

        // 校验投注间隔key
        String intervalKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_INTERVAL_PREFIX, username, eventId);
        // 投注次数限制key
        String limitKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_LIMIT_PREFIX, username, eventId);

        // 投注（带重试机制,默认重试0次,也就是不重试）
        int maxRetry = 0;
        int attempt = 0;
        boolean success = false;

        if (null != limitDTO.getRetry() && 1 == limitDTO.getRetry()) {
            // 获取重试次数
            maxRetry = limitDTO.getRetryCount();
        }
        while (attempt <= maxRetry && !success) {
            attempt++;
            try {
                if (isA) {
                    dto.setBetTimeA(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                } else {
                    dto.setBetTimeB(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                }

                if (dto.getLastOddsTimeA() == dto.getLastOddsTimeB()) {
                    // 如果两个都是旧或者新,则不进行投注,需要在前端显示
                    result.putOpt("isBet", true);
                    result.putOpt("success", false);
                    return result;
                }
                boolean lastOddsTime = isA ? dto.getLastOddsTimeA() : dto.getLastOddsTimeB();
                if (isUnilateral) {
                    if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 1 && lastOddsTime) {
                        // 单边旧投注,当前网站赔率是最新的，直接跳出不投注
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    } else if (limitDTO.getUnilateralBetType() != null && limitDTO.getUnilateralBetType() == 2 && !lastOddsTime) {
                        // 单边新投注,当前网站赔率不是最新的，直接跳出不投注
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    }

                    if (limitDTO.getWebsiteLimit() != null && !limitDTO.getWebsiteLimit().contains(websiteId)) {
                        // 单边投注，当前网站不是所指定的网站，直接跳出不投注
                        result.putOpt("isBet", false);
                        result.putOpt("success", false);
                        return result;
                    }
                }

                // 这里是校验间隔时间
                long intervalMillis = intervalDTO.getBetSuccessSec() * 1000L;

                if (!tryLockIntervalKey(intervalKey, intervalMillis)) {
                    log.info("投注间隔时间未到, 用户 {}, 网站:{}：key={}", username, WebsiteType.getById(websiteId).getDescription(), intervalKey);
                    result.putOpt("isBet", false);
                    result.putOpt("success", false);
                    return result;
                }

                // 校验投注次数限制
                // 投注前锁定次数（确保不会超过）
                if (!tryReserveBetLimit(limitKey, score, limitDTO)) {
                    log.info("用户 {}, 网站:{} 当前比分 {} 投注次数超限，eventId={}", username, WebsiteType.getById(websiteId).getDescription(), score, eventId);
                    result.putOpt("isBet", false);
                    result.putOpt("success", false);
                    return result;
                }

                if (simulateBet == 1) {
                    // 模拟投注
                    result.putOpt("isBet", true);
                    result.putOpt("success", false);
                    return result;
                }
                // 投注预览
                JSONObject betPreview = buildBetInfo(username, websiteId, params, dto);
                if (betPreview == null) {
                    log.info("用户 {}, 网站:{} 投注预览失败，eventId={}", username, WebsiteType.getById(websiteId).getDescription(), eventId);
                    result.putOpt("isBet", false);
                    result.putOpt("success", false);
                    return result;
                } else {
                    log.info("用户 {}, 网站:{} 投注预览成功，eventId={}, isA={}, 预览结果={}, 原本手动解析的betInfo={}", username, WebsiteType.getById(websiteId).getDescription(), eventId, isA, betPreview, isA ? dto.getBetInfoA() : dto.getBetInfoB());
                    if (isA) {
                        dto.setBetInfoA(betPreview.getJSONObject("betInfo"));
                    } else {
                        dto.setBetInfoB(betPreview.getJSONObject("betInfo"));
                    }
                }

                // 投注
                Object betResult = handicapApi.bet(username, websiteId, params, betPreview.getJSONObject("betInfo"), betPreview.getJSONObject("betPreview"));

                if (isA) {
                    dto.setBetTimeA(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                } else {
                    dto.setBetTimeB(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_TIME_PATTERN));
                }
                if (betResult != null && parseBetSuccess(betResult, dto, isA)) {
                    // 投注成功则记录投注时间
                    businessPlatformRedissonClient.getBucket(intervalKey).set(System.currentTimeMillis(), Duration.ofHours(24));
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
                log.warn("用户 {}, 网站:{}, 投注尝试第 {} 次失败：{}", username, WebsiteType.getById(websiteId).getDescription(), attempt, e.getMessage(), e);
                // 短暂等待再重试
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (!success) {
            // 撤销预占额度
            rollbackBetLimit(limitKey, score);
            // 撤销预占间隔
            releaseIntervalKey(intervalKey);
            log.error("用户 {}, 网站:{}, 投注失败，已重试 {} 次，eventId={}", username, WebsiteType.getById(websiteId).getDescription(), maxRetry, eventId);
        }
        return result;
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
    public JSONObject buildBetInfo(String username, String websiteId, JSONObject params, SweepwaterBetDTO sweepwaterBetDTO) {
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
                betInfo.putOpt("odds", objJson.getStr("selection") + " " + objJson.getStr("handicap") + " @ " + objJson.getStr("odds"));
                betInfo.putOpt("handicap", objJson.getStr("handicap"));
                betInfo.putOpt("amount", params.getStr("stake"));
            }
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            JSONObject data = betPreviewJson.getJSONObject("data");
            JSONObject betTicket = data.getJSONObject("betTicket");
            betInfo.putOpt("league", data.getStr("leagueName"));
            betInfo.putOpt("team", data.getStr("eventName"));
            betInfo.putOpt("marketTypeName", data.getStr("marketTypeName"));
            betInfo.putOpt("marketName", data.getStr("name"));
            betInfo.putOpt("odds", data.getStr("name") + " " + betTicket.getStr("handicap") + " @ " + betTicket.getStr("odds"));
            betInfo.putOpt("handicap", data.getStr("handicap"));
            betInfo.putOpt("amount", params.getStr("stake"));
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
            betInfo.putOpt("odds", marketName + " " + serverresponse.getStr("spread") + " @ " + serverresponse.getStr("ioratio"));
            betInfo.putOpt("handicap", serverresponse.getStr("spread"));
            betInfo.putOpt("amount", params.getStr("golds"));
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
            betInfo.putOpt("odds", marketName + " " + odd.getStr("point") + " @ " + odd.getStr("price"));
            betInfo.putOpt("handicap", odd.getStr("point"));
            betInfo.putOpt("amount", params.getStr("stake"));
            betInfo.putOpt("uid", odd.getStr("uid"));
        }

        betPreviewResult.putOpt("betInfo", betInfo);
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
            params.putOpt("autoOdd", "Y");
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
        JSONObject betResultJson = JSONUtil.parseObj(betResult);
        if (!betResultJson.getBool("success", false)) {
            return false;
        }

        String betId = null;
        String account = betResultJson.getStr("account");
        String accountId = betResultJson.getStr("accountId");
        String websiteId = isA ? sweepwaterDTO.getWebsiteIdA() : sweepwaterDTO.getWebsiteIdB();

        // 投注成功后就用betId去盘口投注历史接口中查找当前的投注单，进行保存
        if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            betId = betResultJson.getJSONObject("data").getJSONObject("betInfo").getStr("betId");
        } else if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            betId = betResultJson.getJSONArray("response").getJSONObject(0).getStr("wagerId");
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            betId = betResultJson.getJSONObject("data").getJSONObject("serverresponse").getStr("ticket_id");
        } else if (WebsiteType.SBO.getId().equals(websiteId)) {
            betId = betResultJson.getJSONObject("data").getStr("transId");
        }

        if (StringUtils.isNotBlank(betId)) {
            if (isA) {
                sweepwaterDTO.setBetIdA(betId);
                sweepwaterDTO.setBetAccountA(account);
                sweepwaterDTO.setBetAccountIdA(accountId);
            } else {
                sweepwaterDTO.setBetIdB(betId);
                sweepwaterDTO.setBetAccountB(account);
                sweepwaterDTO.setBetAccountIdB(accountId);
            }
            return true;
        }
        return false;
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
                            LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATE_PATTERN),
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
