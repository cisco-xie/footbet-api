package com.example.demo.config;

import cn.hutool.core.util.IdUtil;
import com.example.demo.model.dto.settings.LimitDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * SuccessBasedLimitManager - 完整可生产级别实现（基于 reservationId 的精确预占/确认/回滚）
 *
 * 核心改进：
 * 1. 为 intervalKey 添加预占机制，防止时间间隔竞态
 * 2. 统一的预占、确认、回滚逻辑，涵盖次数限制和时间间隔
 * 3. 完整的 reservationId 追踪，确保操作的精确性
 *
 * 设计说明：
 * - 每个预占操作同时预留额度（limitKey）和时间间隔（intervalKey）
 * - 确认成功时同时确认额度和时间间隔
 * - 回滚时同时回滚额度和时间间隔预留
 * - 使用统一的 reservationId 管理所有预占资源
 */
@Slf4j
@Component
public class SuccessBasedLimitManager {

    // ==================== 存储结构 ====================

    /**
     * 限制状态存储（key: limitKey）
     * 值: MatchLimitState（包含按 reservationId 的预留/确认记录）
     */
    private final ConcurrentHashMap<String, MatchLimitState> limitStates = new ConcurrentHashMap<>();

    /**
     * 时间间隔状态存储（key: intervalKey）
     * 值: IntervalLimitState（包含按 reservationId 的时间间隔预留/确认记录）
     */
    private final ConcurrentHashMap<String, IntervalLimitState> intervalStates = new ConcurrentHashMap<>();

    /**
     * 最后投注时间存储（key: intervalKey -> lastConfirmedTimestampMillis）
     * 仅在 confirmSuccess 时写入，用于时间间隔检查（interval 策略）。
     */
    private final ConcurrentHashMap<String, Long> lastBetTimes = new ConcurrentHashMap<>();

    /**
     * 全局锁存储 - 确保每个 key 有唯一的锁对象
     */
    private final ConcurrentHashMap<String, ReentrantLock> globalLocks = new ConcurrentHashMap<>();

    // ==================== 配置项 ====================

    private final long DEFAULT_CLEANUP_HOURS = 6;
    private final boolean ENABLE_MONITOR_LOG = true;
    private final long MONITOR_INTERVAL_MINUTES = 5;
    private final long LOCK_TIMEOUT_MS = 5000;
    private final long RESERVATION_TIMEOUT_MS = 60_000L;

    private final ScheduledExecutorService scheduledExecutor;

    public SuccessBasedLimitManager() {
        this.scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "SuccessBasedLimitManager-Scheduler");
            t.setDaemon(true);
            return t;
        });

        this.scheduledExecutor.scheduleAtFixedRate(this::cleanupExpiredData,
                DEFAULT_CLEANUP_HOURS, DEFAULT_CLEANUP_HOURS, TimeUnit.HOURS);

        if (ENABLE_MONITOR_LOG) {
            this.scheduledExecutor.scheduleAtFixedRate(this::printLimitStatus,
                    MONITOR_INTERVAL_MINUTES, MONITOR_INTERVAL_MINUTES, TimeUnit.MINUTES);
        }

        log.info("🎯 SuccessBasedLimitManager 初始化完成 - 清理间隔: {}小时, 监控间隔: {}分钟, 锁超时: {}ms, 预留超时: {}ms",
                DEFAULT_CLEANUP_HOURS, MONITOR_INTERVAL_MINUTES, LOCK_TIMEOUT_MS, RESERVATION_TIMEOUT_MS);
    }

    // ==================== 核心 API ====================

    /**
     * 强制预检查并预留额度和时间间隔 - 在投注前调用
     *
     * 核心改进：同时预占额度（limitKey）和时间间隔（intervalKey）
     *
     * 执行流程：
     * 1. 按全局顺序获取 limitKey 和 intervalKey 的锁
     * 2. 检查时间间隔是否满足（考虑已预留的时间间隔）
     * 3. 检查次数限制是否满足（考虑已预留的额度）
     * 4. 生成唯一 reservationId
     * 5. 同时预留额度和时间间隔
     * 6. 返回包含 reservationId 的结果
     *
     * @param limitKey 限制 key（赛事级）
     * @param intervalKey 间隔 key（用于时间间隔控制）
     * @param score 比分
     * @param limitDTO 次数限制配置
     * @param intervalMillis 间隔要求（毫秒）
     * @return EnforcementResult（成功时包含 reservationId）
     */
    public EnforcementResult preCheckAndReserve(String limitKey, String intervalKey,
                                                String score, LimitDTO limitDTO,
                                                long intervalMillis) {
        // 锁顺序：按全局字符串自然顺序（避免死锁）
        String[] lockKeys = getOrderedLockKeys(limitKey, intervalKey);

        log.info("投注次数间隔限制 🔒 线程 [{}] 开始获取锁: {}", Thread.currentThread().getName(), Arrays.toString(lockKeys));
        lockAll(lockKeys);

        try {
            long now = System.currentTimeMillis();

            // 1. 严格时间间隔检查（考虑已预留的时间间隔）
            TimeCheckResult timeCheck = checkTimeIntervalStrict(intervalKey, intervalMillis, now);
            if (!timeCheck.isPassed()) {
                log.info("投注次数间隔限制 ⏰ 时间间隔阻止投注: intervalKey={}, 需要{}ms, 实际{}ms, 还需等待{}ms",
                        intervalKey, intervalMillis, timeCheck.getActualInterval(),
                        intervalMillis - timeCheck.getActualInterval());
                return EnforcementResult.failed(EnforcementResult.FailReason.TIME_INTERVAL);
            }

            // 2. 严格次数限制检查（考虑已预留的额度）
            MatchLimitState limitState = limitStates.computeIfAbsent(limitKey, k -> new MatchLimitState());
            int currentScoreCount = limitState.getCurrentScoreCount(score);
            int currentTotalCount = limitState.getCurrentTotalCount();

            if (currentScoreCount >= limitDTO.getBetLimitScore()) {
                log.info("投注次数间隔限制 🔢 比分次数阻止投注: limitKey={}, score={}, 当前={}, 限制={}",
                        limitKey, score, currentScoreCount, limitDTO.getBetLimitScore());
                return EnforcementResult.failed(EnforcementResult.FailReason.SCORE_LIMIT);
            }
            if (currentTotalCount >= limitDTO.getBetLimitGame()) {
                log.info("投注次数间隔限制 🔢 总次数阻止投注: limitKey={}, 当前={}, 限制={}",
                        limitKey, currentTotalCount, limitDTO.getBetLimitGame());
                return EnforcementResult.failed(EnforcementResult.FailReason.TOTAL_LIMIT);
            }

            // 3. 生成 reservationId 并同时预留额度和时间间隔
            String reservationId = IdUtil.fastSimpleUUID();

            // 预留额度
            boolean limitReserved = limitState.reserve(reservationId, score, intervalKey, now);
            limitState.lastAccessTime = now;

            // 预留时间间隔
            IntervalLimitState intervalState = intervalStates.computeIfAbsent(intervalKey, k -> new IntervalLimitState());
            boolean intervalReserved = intervalState.reserve(reservationId, now);
            intervalState.lastAccessTime = now;

            if (!limitReserved || !intervalReserved) {
                // 极小概率冲突 - 生成新的 reservationId 重试
                reservationId = IdUtil.fastSimpleUUID();
                limitState.reserve(reservationId, score, intervalKey, now);
                intervalState.reserve(reservationId, now);
            }

            log.info("投注次数间隔限制 ✅ 预占成功 - limitKey={}, intervalKey={}, score={}, reservationId={}, 比分次数={}/{}, 总次数={}/{}",
                    limitKey, intervalKey, score, reservationId,
                    currentScoreCount + 1, limitDTO.getBetLimitScore(),
                    currentTotalCount + 1, limitDTO.getBetLimitGame());

            return EnforcementResult.success(reservationId);
        } catch (Exception e) {
            log.error("投注次数间隔限制 ❌ 预检查过程异常: limitKey={}, intervalKey={}, error={}",
                    limitKey, intervalKey, e.getMessage(), e);
            return EnforcementResult.failed(EnforcementResult.FailReason.SYSTEM_ERROR);
        } finally {
            unlockAll(lockKeys);
            log.info("投注次数间隔限制 🔒 线程 [{}] 释放锁完成", Thread.currentThread().getName());
        }
    }

    /**
     * 确认投注成功（按 reservationId 精确确认）
     *
     * 核心改进：同时确认额度和时间间隔
     *
     * 执行流程：
     * 1. 根据 reservationId 查找对应的 limitKey 和 intervalKey
     * 2. 按全局顺序获取锁
     * 3. 确认额度预留
     * 4. 确认时间间隔预留并记录最后投注时间
     * 5. 清理预留记录
     *
     * @param limitKey 赛事 key
     * @param reservationId 预占 id
     */
    public void confirmSuccess(String limitKey, String reservationId) {
        if (reservationId == null) {
            log.warn("投注次数间隔限制 confirmSuccess called with null reservationId for limitKey={}", limitKey);
            return;
        }

        // 先获取 limitKey 锁来查找 intervalKey
        String[] initialLockKeys = getOrderedLockKeys(limitKey);
        lockAll(initialLockKeys);

        String intervalKey = null;
        try {
            MatchLimitState limitState = limitStates.get(limitKey);
            if (limitState != null) {
                intervalKey = limitState.getIntervalKeyForReservation(reservationId);
            }
        } finally {
            unlockAll(initialLockKeys);
        }

        if (intervalKey == null) {
            log.warn("投注次数间隔限制 confirmSuccess 未找到 reservation 对应的 intervalKey - limitKey={}, reservationId={}",
                    limitKey, reservationId);
            return;
        }

        // 获取所有相关锁进行确认操作
        String[] lockKeys = getOrderedLockKeys(limitKey, intervalKey);
        lockAll(lockKeys);
        try {
            long now = System.currentTimeMillis();

            // 确认额度预留
            MatchLimitState limitState = limitStates.get(limitKey);
            boolean limitConfirmed = false;
            if (limitState != null) {
                limitConfirmed = limitState.confirmByReservation(reservationId, now);
                limitState.lastAccessTime = now;
            }

            // 确认时间间隔预留
            IntervalLimitState intervalState = intervalStates.get(intervalKey);
            boolean intervalConfirmed = false;
            if (intervalState != null) {
                intervalConfirmed = intervalState.confirmByReservation(reservationId, now);
                intervalState.lastAccessTime = now;
            }

            if (limitConfirmed && intervalConfirmed) {
                // 记录最后投注时间
                lastBetTimes.put(intervalKey, now);
                log.info("投注次数间隔限制 ✅ 确认 reservation 成功 - limitKey={}, intervalKey={}, reservationId={}",
                        limitKey, intervalKey, reservationId);
            } else {
                log.warn("投注次数间隔限制 confirmSuccess 确认失败 - limitKey={}, intervalKey={}, reservationId={}, limitConfirmed={}, intervalConfirmed={}",
                        limitKey, intervalKey, reservationId, limitConfirmed, intervalConfirmed);
            }
        } finally {
            unlockAll(lockKeys);
        }
    }

    /**
     * 回滚预留（按 reservationId 精确回滚）
     *
     * 核心改进：同时回滚额度和时间间隔
     *
     * @param limitKey 赛事 key
     * @param reservationId 预占 id
     */
    public void rollbackReservation(String limitKey, String reservationId) {
        if (reservationId == null) {
            log.warn("投注次数间隔限制 rollbackReservation called with null reservationId for limitKey={}", limitKey);
            return;
        }

        // 先获取 limitKey 锁来查找 intervalKey
        String[] initialLockKeys = getOrderedLockKeys(limitKey);
        lockAll(initialLockKeys);

        String intervalKey = null;
        try {
            MatchLimitState limitState = limitStates.get(limitKey);
            if (limitState != null) {
                intervalKey = limitState.getIntervalKeyForReservation(reservationId);
            }
        } finally {
            unlockAll(initialLockKeys);
        }

        if (intervalKey == null) {
            log.warn("投注次数间隔限制 rollbackReservation 未找到 reservation 对应的 intervalKey - limitKey={}, reservationId={}",
                    limitKey, reservationId);
            return;
        }

        // 获取所有相关锁进行回滚操作
        String[] lockKeys = getOrderedLockKeys(limitKey, intervalKey);
        lockAll(lockKeys);
        try {
            long now = System.currentTimeMillis();

            // 回滚额度预留
            MatchLimitState limitState = limitStates.get(limitKey);
            boolean limitRolledback = false;
            if (limitState != null) {
                limitRolledback = limitState.rollbackByReservation(reservationId, now);
                limitState.lastAccessTime = now;
            }

            // 回滚时间间隔预留
            IntervalLimitState intervalState = intervalStates.get(intervalKey);
            boolean intervalRolledback = false;
            if (intervalState != null) {
                intervalRolledback = intervalState.rollbackByReservation(reservationId, now);
                intervalState.lastAccessTime = now;
            }

            if (limitRolledback && intervalRolledback) {
                log.info("投注次数间隔限制 ↩️ 回滚 reservation 成功 - limitKey={}, intervalKey={}, reservationId={}",
                        limitKey, intervalKey, reservationId);
            } else {
                log.warn("投注次数间隔限制 rollbackReservation 回滚失败 - limitKey={}, intervalKey={}, reservationId={}, limitRolledback={}, intervalRolledback={}",
                        limitKey, intervalKey, reservationId, limitRolledback, intervalRolledback);
            }
        } finally {
            unlockAll(lockKeys);
        }
    }

    // ==================== 向后兼容的旧接口（按 intervalKey/score 确认或回滚） ====================

    /**
     * 老签名：confirmSuccess(limitKey, intervalKey, score)
     * 兼容实现：在 limitKey 的 reservations 中查找第一个匹配 intervalKey 且 score 相符的 reservation 并确认。
     */
    public void confirmSuccess(String limitKey, String intervalKey, String score) {
        String[] lockKeys = getOrderedLockKeys(limitKey, intervalKey);
        lockAll(lockKeys);
        try {
            MatchLimitState limitState = limitStates.get(limitKey);
            if (limitState == null) {
                log.warn("投注次数间隔限制 兼容 confirmSuccess 未找到 state: limitKey={}", limitKey);
                return;
            }

            // 查找匹配的 reservationId（按插入顺序最先匹配）
            String found = null;
            for (MatchLimitState.ReservationInfo r : limitState.getAllReservationsInInsertionOrder()) {
                if (intervalKey.equals(r.intervalKey) && Objects.equals(r.score, score)) {
                    found = r.reservationId;
                    break;
                }
            }

            if (found != null) {
                long now = System.currentTimeMillis();
                // 调用基于 reservationId 的确认
                boolean limitConfirmed = limitState.confirmByReservation(found, now);
                limitState.lastAccessTime = now;

                // 确认时间间隔预留
                IntervalLimitState intervalState = intervalStates.get(intervalKey);
                boolean intervalConfirmed = false;
                if (intervalState != null) {
                    intervalConfirmed = intervalState.confirmByReservation(found, now);
                    intervalState.lastAccessTime = now;
                }

                if (limitConfirmed && intervalConfirmed) {
                    lastBetTimes.put(intervalKey, now);
                    log.info("投注次数间隔限制 ✅ 兼容 confirmSuccess 成功 - limitKey={}, intervalKey={}, reservationId={}",
                            limitKey, intervalKey, found);
                }
            } else {
                log.warn("投注次数间隔限制 兼容 confirmSuccess 未找到匹配 reservation - limitKey={}, intervalKey={}, score={}",
                        limitKey, intervalKey, score);
            }

        } finally {
            unlockAll(lockKeys);
        }
    }

    /**
     * 老签名：rollbackReservation(limitKey, intervalKey, score)
     * 兼容实现：查找并回滚第一个匹配 intervalKey 且 score 相符的 reservation。
     */
    public void rollbackReservation(String limitKey, String intervalKey, String score) {
        String[] lockKeys = getOrderedLockKeys(limitKey, intervalKey);
        lockAll(lockKeys);
        try {
            MatchLimitState limitState = limitStates.get(limitKey);
            if (limitState == null) {
                log.warn("投注次数间隔限制 兼容 rollbackReservation 未找到 state: limitKey={}", limitKey);
                return;
            }

            String found = null;
            for (MatchLimitState.ReservationInfo r : limitState.getAllReservationsInInsertionOrder()) {
                if (intervalKey.equals(r.intervalKey) && Objects.equals(r.score, score)) {
                    found = r.reservationId;
                    break;
                }
            }

            if (found != null) {
                long now = System.currentTimeMillis();
                boolean limitRolledback = limitState.rollbackByReservation(found, now);
                limitState.lastAccessTime = now;

                // 回滚时间间隔预留
                IntervalLimitState intervalState = intervalStates.get(intervalKey);
                boolean intervalRolledback = false;
                if (intervalState != null) {
                    intervalRolledback = intervalState.rollbackByReservation(found, now);
                    intervalState.lastAccessTime = now;
                }

                if (limitRolledback && intervalRolledback) {
                    log.info("投注次数间隔限制 ↩️ 兼容 rollbackReservation 成功 - limitKey={}, intervalKey={}, reservationId={}",
                            limitKey, intervalKey, found);
                }
            } else {
                log.warn("投注次数间隔限制 兼容 rollbackReservation 未找到匹配 reservation - limitKey={}, intervalKey={}, score={}",
                        limitKey, intervalKey, score);
            }

        } finally {
            unlockAll(lockKeys);
        }
    }

    // ==================== 无锁快速 API（UI 友好） ====================

    /**
     * 无锁快速检查 - 仅用于 UI/提前判断，不做强制控制
     */
    public boolean canBet(String limitKey, String score, LimitDTO limitDTO) {
        MatchLimitState state = limitStates.get(limitKey);
        if (state == null) return true;
        return state.canBet(score, limitDTO);
    }

    /**
     * 无锁快速检查时间间隔 - 仅用于 UI/提前判断
     */
    public boolean checkInterval(String intervalKey, long intervalMillis) {
        Long last = lastBetTimes.get(intervalKey);
        if (last == null) return true;
        return System.currentTimeMillis() - last >= intervalMillis;
    }

    // ==================== 监控 / 调试 API ====================

    /**
     * 定时打印所有限制key的详细状态
     */
    public void printLimitStatus() {
        try {
            log.info("📊 ========== 投注次数间隔限制 限流管理器状态监控开始 ==========");
            printBasicStatistics();
            printDetailedLimitStates();
            printIntervalStates();
            printLockStatistics();
            log.info("📊 ========== 投注次数间隔限制 限流管理器状态监控结束 ==========");
        } catch (Exception e) {
            log.error("投注次数间隔限制 打印限流状态异常", e);
        }
    }

    /**
     * 手动触发状态打印
     */
    public void triggerManualPrint() {
        log.info("投注次数间隔限制 🔄 手动触发状态打印...");
        printLimitStatus();
    }

    /**
     * 获取指定limitKey的详细状态
     */
    public Map<String, Object> getKeyDetail(String limitKey) {
        Map<String, Object> detail = new HashMap<>();
        MatchLimitState state = limitStates.get(limitKey);
        if (state == null) {
            detail.put("exists", false);
            return detail;
        }
        detail.put("exists", true);
        detail.put("lastAccessTime", new Date(state.lastAccessTime));
        detail.put("confirmedTotal", state.confirmedTotal.get());
        detail.put("reservedTotal", state.reservedTotal.get());
        detail.put("confirmedScores", new HashMap<>(state.confirmedScores));
        detail.put("reservedScores", new HashMap<>(state.reservedCountByScore));
        detail.put("reservations", state.getAllReservationsInInsertionOrder().stream()
                .map(r -> Map.of("reservationId", r.reservationId, "score", r.score, "intervalKey", r.intervalKey, "time", new Date(r.time)))
                .collect(Collectors.toList()));
        return detail;
    }

    /**
     * 获取所有limitKey的列表
     */
    public List<String> getAllLimitKeys() {
        return new ArrayList<>(limitStates.keySet());
    }

    /**
     * 搜索包含特定关键词的limitKey
     */
    public List<String> searchLimitKeys(String keyword) {
        return limitStates.keySet().stream()
                .filter(key -> key.contains(keyword))
                .collect(Collectors.toList());
    }

    /**
     * 调试特定key的锁和状态
     */
    public void debugKey(String limitKey, String intervalKey) {
        log.info("🐛 ========== 调试Key: {} ==========", limitKey);

        // 检查锁对象
        ReentrantLock limitLock = globalLocks.get(limitKey);
        ReentrantLock intervalLock = globalLocks.get(intervalKey);

        log.info("投注次数间隔限制 🔒 锁对象 - limitLock: {}, intervalLock: {}",
                limitLock != null ? "存在" : "null",
                intervalLock != null ? "存在" : "null");

        if (limitLock != null && intervalLock != null) {
            log.info("投注次数间隔限制 🔒 锁状态 - limitLock: 锁定={}/队列={}, intervalLock: 锁定={}/队列={}",
                    limitLock.isLocked(), limitLock.getQueueLength(),
                    intervalLock.isLocked(), intervalLock.getQueueLength());
        }

        // 检查限制状态
        MatchLimitState limitState = limitStates.get(limitKey);
        if (limitState != null) {
            log.info("投注次数间隔限制 📊 限制状态 - 确认总数: {}, 预留总数: {}, 最后访问: {}",
                    limitState.confirmedTotal.get(), limitState.reservedTotal.get(),
                    new Date(limitState.lastAccessTime));
            if (!limitState.confirmedScores.isEmpty()) {
                log.info("投注次数间隔限制 📊 确认比分: {}", limitState.confirmedScores);
            }
            if (!limitState.reservedCountByScore.isEmpty()) {
                log.info("投注次数间隔限制 📊 预留比分: {}", limitState.reservedCountByScore);
            }
        } else {
            log.info("投注次数间隔限制 📊 限制状态 - 无状态记录");
        }

        // 检查间隔状态
        IntervalLimitState intervalState = intervalStates.get(intervalKey);
        if (intervalState != null) {
            log.info("投注次数间隔限制 ⏰ 间隔状态 - 预留数量: {}, 最后访问: {}",
                    intervalState.getReservationCount(), new Date(intervalState.lastAccessTime));
            Long lastReserved = intervalState.getLastReservedTime();
            if (lastReserved != null) {
                log.info("投注次数间隔限制 ⏰ 最后预留时间: {} ({}秒前)",
                        new Date(lastReserved),
                        (System.currentTimeMillis() - lastReserved) / 1000);
            }
        } else {
            log.info("投注次数间隔限制 ⏰ 间隔状态 - 无状态记录");
        }

        // 检查最后投注时间
        Long lastBetTime = lastBetTimes.get(intervalKey);
        if (lastBetTime != null) {
            log.info("投注次数间隔限制 🕒 最后投注时间: {} ({}秒前)",
                    new Date(lastBetTime),
                    (System.currentTimeMillis() - lastBetTime) / 1000);
        } else {
            log.info("投注次数间隔限制 🕒 最后投注时间 - 无记录");
        }

        log.info("🐛 ========== 调试结束 ==========");
    }

    /**
     * 调试锁对象一致性
     */
    public void debugLockConsistency(String limitKey, String intervalKey) {
        String[] keys = getOrderedLockKeys(limitKey, intervalKey);

        log.info("🔍 ========== 投注次数间隔限制 锁一致性调试 ==========");
        for (String key : keys) {
            ReentrantLock lock1 = getGlobalLock(key);
            ReentrantLock lock2 = getGlobalLock(key);
            ReentrantLock lock3 = globalLocks.get(key);

            log.info("投注次数间隔限制 🔍 {} - lock1==lock2: {}, lock1==lock3: {}, 锁定: {}, 队列: {}",
                    key, lock1 == lock2, lock1 == lock3,
                    lock1.isLocked(), lock1.getQueueLength());
        }
        log.info("🔍 ========== 投注次数间隔限制 调试结束 ==========");
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 获取按全局顺序排序的锁 key 数组
     */
    private String[] getOrderedLockKeys(String... keys) {
        String[] sorted = keys.clone();
        Arrays.sort(sorted);
        return sorted;
    }

    /**
     * 按顺序获取所有锁
     */
    private void lockAll(String[] lockKeys) {
        ReentrantLock[] locks = new ReentrantLock[lockKeys.length];
        for (int i = 0; i < lockKeys.length; i++) {
            locks[i] = getGlobalLock(lockKeys[i]);
        }

        // 二次检查锁对象一致性
        for (int i = 0; i < lockKeys.length; i++) {
            ReentrantLock l1 = locks[i];
            ReentrantLock l2 = getGlobalLock(lockKeys[i]);
            if (l1 != l2) {
                log.error("投注次数间隔限制 ❌ 锁对象不一致: key={}", lockKeys[i]);
                throw new IllegalStateException("锁对象不一致: " + lockKeys[i]);
            }
        }

        for (int i = 0; i < locks.length; i++) {
            ReentrantLock lock = locks[i];
            try {
                boolean acquired = lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    log.error("投注次数间隔限制 ❌ 获取锁超时: {} (线程:{})", lockKeys[i], Thread.currentThread().getName());
                    throw new IllegalStateException("获取锁超时: " + lockKeys[i]);
                }
                log.debug("投注次数间隔限制 🔒 线程 [{}] 锁定: {}", Thread.currentThread().getName(), lockKeys[i]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("获取锁被中断: " + lockKeys[i], e);
            }
        }
    }

    /**
     * 按相反顺序释放所有锁
     */
    private void unlockAll(String[] lockKeys) {
        for (int i = lockKeys.length - 1; i >= 0; i--) {
            ReentrantLock lock = globalLocks.get(lockKeys[i]);
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("投注次数间隔限制 🔓 线程 [{}] 解锁: {}", Thread.currentThread().getName(), lockKeys[i]);
            }
        }
    }

    /**
     * 获取全局锁对象
     */
    private ReentrantLock getGlobalLock(String key) {
        return globalLocks.computeIfAbsent(key, k -> {
            log.info("投注次数间隔限制 🔐 创建全局锁: {}", k);
            return new ReentrantLock();
        });
    }

    /**
     * 严格时间间隔检查 - 考虑已预留的时间间隔
     */
    private TimeCheckResult checkTimeIntervalStrict(String intervalKey, long requiredInterval, long currentTime) {
        // 1. 检查最后确认的投注时间
        Long lastConfirmedTime = lastBetTimes.get(intervalKey);
        if (lastConfirmedTime != null) {
            long actualInterval = currentTime - lastConfirmedTime;
            if (actualInterval < requiredInterval) {
                return TimeCheckResult.failed(actualInterval);
            }
        }

        // 2. 检查已预留但未确认的时间间隔
        IntervalLimitState intervalState = intervalStates.get(intervalKey);
        if (intervalState != null) {
            Long lastReservedTime = intervalState.getLastReservedTime();
            if (lastReservedTime != null) {
                long actualInterval = currentTime - lastReservedTime;
                if (actualInterval < requiredInterval) {
                    return TimeCheckResult.failed(actualInterval);
                }
            }
        }

        return TimeCheckResult.passed(lastConfirmedTime == null ? 0 : currentTime - lastConfirmedTime);
    }

    // ==================== 监控打印实现 ====================

    private void printBasicStatistics() {
        int totalLimitKeys = limitStates.size();
        int totalIntervalKeys = intervalStates.size();
        int totalLastBetTimes = lastBetTimes.size();
        int totalLocks = globalLocks.size();

        long totalReserved = limitStates.values().stream()
                .mapToInt(state -> state.reservedTotal.get())
                .sum();
        long totalConfirmed = limitStates.values().stream()
                .mapToInt(state -> state.confirmedTotal.get())
                .sum();

        long totalIntervalReserved = intervalStates.values().stream()
                .mapToInt(IntervalLimitState::getReservationCount)
                .sum();

        log.info("投注次数间隔限制 📈 基本统计 - 限制Key数: {}, 间隔Key数: {}, 时间记录数: {}, 锁数: {}, 总额度预留: {}, 总额度确认: {}, 总间隔预留: {}",
                totalLimitKeys, totalIntervalKeys, totalLastBetTimes, totalLocks,
                totalReserved, totalConfirmed, totalIntervalReserved);
    }

    private void printDetailedLimitStates() {
        if (limitStates.isEmpty()) {
            log.info("投注次数间隔限制 📝 详细状态 - 暂无限制记录");
            return;
        }

        List<Map.Entry<String, MatchLimitState>> sortedEntries = limitStates.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().lastAccessTime, e1.getValue().lastAccessTime))
                .limit(20)
                .collect(Collectors.toList());

        log.info("投注次数间隔限制 📝 详细限制状态 (显示最近{}个活跃key):", sortedEntries.size());

        for (Map.Entry<String, MatchLimitState> entry : sortedEntries) {
            String limitKey = entry.getKey();
            MatchLimitState state = entry.getValue();
            long minutesAgo = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - state.lastAccessTime);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  🔑 %s (%.1f分钟前)", limitKey, (double) minutesAgo));
            sb.append(String.format(" - 确认: %d, 预留: %d", state.confirmedTotal.get(), state.reservedTotal.get()));

            if (!state.confirmedScores.isEmpty() || !state.reservedCountByScore.isEmpty()) {
                sb.append(" | 比分详情: ");
                Set<String> allScores = new HashSet<>();
                allScores.addAll(state.confirmedScores.keySet());
                allScores.addAll(state.reservedCountByScore.keySet());
                for (String s : allScores) {
                    int c = state.confirmedScores.getOrDefault(s, 0);
                    int r = state.reservedCountByScore.getOrDefault(s, 0);
                    if (c > 0 || r > 0) {
                        sb.append(String.format("%s(%d+%d) ", s, c, r));
                    }
                }
            }
            log.info("投注次数间隔限制 " + sb.toString());
        }

        if (limitStates.size() > sortedEntries.size()) {
            log.info("  ... 还有 {} 个限制key未显示", limitStates.size() - sortedEntries.size());
        }
    }

    private void printIntervalStates() {
        if (intervalStates.isEmpty() && lastBetTimes.isEmpty()) {
            log.info("投注次数间隔限制 ⏰ 时间间隔 - 暂无状态记录");
            return;
        }

        // 打印间隔状态
        List<Map.Entry<String, IntervalLimitState>> sortedIntervalStates = intervalStates.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().lastAccessTime, e1.getValue().lastAccessTime))
                .limit(10)
                .collect(Collectors.toList());

        log.info("投注次数间隔限制 ⏰ 间隔状态 (显示最近{}个活跃key):", sortedIntervalStates.size());

        for (Map.Entry<String, IntervalLimitState> entry : sortedIntervalStates) {
            String intervalKey = entry.getKey();
            IntervalLimitState state = entry.getValue();
            long minutesAgo = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - state.lastAccessTime);

            Long lastReserved = state.getLastReservedTime();
            String lastReservedDesc = lastReserved != null ?
                    String.format("最后预留: %d秒前", (System.currentTimeMillis() - lastReserved) / 1000) :
                    "无预留";

            log.info("投注次数间隔限制   🕒 {} ({}分钟前) - 预留数: {}, {}",
                    intervalKey, String.format("%.1f", (double)minutesAgo),
                    state.getReservationCount(), lastReservedDesc);
        }

        // 打印最后投注时间
        List<Map.Entry<String, Long>> sortedTimes = lastBetTimes.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .limit(10)
                .collect(Collectors.toList());

        log.info("投注次数间隔限制 ⏰ 最后投注时间 (显示最近{}个):", sortedTimes.size());

        long currentTime = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : sortedTimes) {
            String intervalKey = entry.getKey();
            long lastTime = entry.getValue();
            long secondsAgo = TimeUnit.MILLISECONDS.toSeconds(currentTime - lastTime);

            String timeDesc;
            if (secondsAgo < 60) timeDesc = secondsAgo + "秒前";
            else if (secondsAgo < 3600) timeDesc = TimeUnit.SECONDS.toMinutes(secondsAgo) + "分钟前";
            else timeDesc = TimeUnit.SECONDS.toHours(secondsAgo) + "小时前";

            log.info("投注次数间隔限制   🕒 {} - {}", intervalKey, timeDesc);
        }
    }

    private void printLockStatistics() {
        long lockedCount = globalLocks.values().stream().filter(ReentrantLock::isLocked).count();
        long queueLength = globalLocks.values().stream().mapToInt(ReentrantLock::getQueueLength).sum();

        if (lockedCount > 0 || queueLength > 0) {
            log.info("投注次数间隔限制 🔒 锁竞争警告 - 当前锁定数: {}, 等待队列: {}", lockedCount, queueLength);
            globalLocks.entrySet().stream()
                    .filter(entry -> entry.getValue().isLocked())
                    .limit(5)
                    .forEach(entry -> log.info("投注次数间隔限制   🔐 锁定Key: {} - 等待数: {}",
                            entry.getKey(), entry.getValue().getQueueLength()));
        } else {
            log.info("投注次数间隔限制 🔓 锁状态 - 无锁竞争，运行正常");
        }
    }

    // ==================== 数据清理与超时回收 ====================

    /**
     * 定期清理过期数据并回收超时 reservation
     */
    private void cleanupExpiredData() {
        long now = System.currentTimeMillis();
        long reservationThreshold = now - RESERVATION_TIMEOUT_MS;
        long stateThreshold = now - TimeUnit.HOURS.toMillis(DEFAULT_CLEANUP_HOURS);

        int rolledBackLimitReservations = 0;
        int rolledBackIntervalReservations = 0;
        int removedLimitStates = 0;
        int removedIntervalStates = 0;
        int removedTimes = 0;

        // 清理 limitStates
        for (Map.Entry<String, MatchLimitState> entry : limitStates.entrySet()) {
            String limitKey = entry.getKey();
            MatchLimitState state = entry.getValue();

            ReentrantLock lock = getGlobalLock(limitKey);
            boolean locked = false;
            try {
                locked = lock.tryLock();
                if (!locked) continue;

                // 回收超时 reservation
                List<String> expired = state.getReservationsOlderThan(reservationThreshold);
                for (String resId : expired) {
                    boolean ok = state.rollbackByReservation(resId, now);
                    if (ok) rolledBackLimitReservations++;
                }

                // 移除空状态
                boolean emptyState = state.confirmedTotal.get() == 0 && state.reservedTotal.get() == 0;
                if (state.lastAccessTime < stateThreshold && emptyState) {
                    limitStates.remove(limitKey, state);
                    removedLimitStates++;
                }

            } finally {
                if (locked) lock.unlock();
            }
        }

        // 清理 intervalStates
        for (Map.Entry<String, IntervalLimitState> entry : intervalStates.entrySet()) {
            String intervalKey = entry.getKey();
            IntervalLimitState state = entry.getValue();

            ReentrantLock lock = getGlobalLock(intervalKey);
            boolean locked = false;
            try {
                locked = lock.tryLock();
                if (!locked) continue;

                // 回收超时 reservation
                List<String> expired = state.getReservationsOlderThan(reservationThreshold);
                for (String resId : expired) {
                    boolean ok = state.rollbackByReservation(resId, now);
                    if (ok) rolledBackIntervalReservations++;
                }

                // 移除空状态
                boolean emptyState = state.getReservationCount() == 0;
                if (state.lastAccessTime < stateThreshold && emptyState) {
                    intervalStates.remove(intervalKey, state);
                    removedIntervalStates++;
                }

            } finally {
                if (locked) lock.unlock();
            }
        }

        // 清理 lastBetTimes
        for (Iterator<Map.Entry<String, Long>> it = lastBetTimes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() < stateThreshold) {
                it.remove();
                removedTimes++;
            }
        }

        if (rolledBackLimitReservations > 0 || rolledBackIntervalReservations > 0 ||
                removedLimitStates > 0 || removedIntervalStates > 0 || removedTimes > 0) {
            log.info("投注次数间隔限制 🧹 清理结果 - 回收额度预留: {}, 回收间隔预留: {}, 移除额度状态: {}, 移除间隔状态: {}, 移除时间记录: {}",
                    rolledBackLimitReservations, rolledBackIntervalReservations,
                    removedLimitStates, removedIntervalStates, removedTimes);
        }
    }

    public void shutdown() {
        scheduledExecutor.shutdownNow();
        log.info("投注次数间隔限制 SuccessBasedLimitManager 已关闭");
    }

    // ==================== 内部类定义 ====================

    /**
     * 比赛限制状态 - 线程不安全类（外部加锁保护）
     */
    private static class MatchLimitState {
        // reservationId -> ReservationInfo
        private final LinkedHashMap<String, ReservationInfo> reservations = new LinkedHashMap<>();
        private final Map<String, Integer> confirmedScores = new HashMap<>();
        private final Map<String, Integer> reservedCountByScore = new HashMap<>();
        private final AtomicInteger confirmedTotal = new AtomicInteger(0);
        private final AtomicInteger reservedTotal = new AtomicInteger(0);
        long lastAccessTime = System.currentTimeMillis();

        private static class ReservationInfo {
            final String reservationId;
            final String score;
            final String intervalKey;
            final long time;
            ReservationInfo(String reservationId, String score, String intervalKey, long time) {
                this.reservationId = reservationId;
                this.score = score;
                this.intervalKey = intervalKey;
                this.time = time;
            }
        }

        boolean reserve(String reservationId, String score, String intervalKey, long time) {
            if (reservations.containsKey(reservationId)) {
                return false;
            }
            ReservationInfo r = new ReservationInfo(reservationId, score, intervalKey, time);
            reservations.put(reservationId, r);
            reservedTotal.incrementAndGet();
            reservedCountByScore.put(score, reservedCountByScore.getOrDefault(score, 0) + 1);
            lastAccessTime = time;
            return true;
        }

        boolean confirmByReservation(String reservationId, long time) {
            ReservationInfo info = reservations.remove(reservationId);
            if (info == null) return false;
            String score = info.score;
            // reserved -> confirmed
            reservedTotal.decrementAndGet();
            Integer r = reservedCountByScore.getOrDefault(score, 0);
            if (r <= 1) reservedCountByScore.remove(score); else reservedCountByScore.put(score, r - 1);
            confirmedScores.put(score, confirmedScores.getOrDefault(score, 0) + 1);
            confirmedTotal.incrementAndGet();
            lastAccessTime = time;
            return true;
        }

        boolean rollbackByReservation(String reservationId, long time) {
            ReservationInfo info = reservations.remove(reservationId);
            if (info == null) return false;
            String score = info.score;
            reservedTotal.decrementAndGet();
            Integer r = reservedCountByScore.getOrDefault(score, 0);
            if (r <= 1) reservedCountByScore.remove(score); else reservedCountByScore.put(score, r - 1);
            lastAccessTime = time;
            return true;
        }

        int getCurrentScoreCount(String score) {
            int c = confirmedScores.getOrDefault(score, 0);
            int r = reservedCountByScore.getOrDefault(score, 0);
            return c + r;
        }

        int getCurrentTotalCount() {
            return confirmedTotal.get() + reservedTotal.get();
        }

        boolean canBet(String score, LimitDTO limitDTO) {
            return getCurrentScoreCount(score) < limitDTO.getBetLimitScore()
                    && getCurrentTotalCount() < limitDTO.getBetLimitGame();
        }

        String getIntervalKeyForReservation(String reservationId) {
            ReservationInfo info = reservations.get(reservationId);
            return info == null ? null : info.intervalKey;
        }

        List<String> getReservationsOlderThan(long thresholdTime) {
            List<String> expired = new ArrayList<>();
            for (ReservationInfo r : reservations.values()) {
                if (r.time < thresholdTime) expired.add(r.reservationId);
            }
            return expired;
        }

        List<ReservationInfo> getAllReservationsInInsertionOrder() {
            return new ArrayList<>(reservations.values());
        }
    }

    /**
     * 时间间隔限制状态 - 线程不安全类（外部加锁保护）
     */
    private static class IntervalLimitState {
        // reservationId -> 预留时间
        private final LinkedHashMap<String, Long> reservations = new LinkedHashMap<>();
        long lastAccessTime = System.currentTimeMillis();

        boolean reserve(String reservationId, long time) {
            if (reservations.containsKey(reservationId)) {
                return false;
            }
            reservations.put(reservationId, time);
            lastAccessTime = time;
            return true;
        }

        boolean confirmByReservation(String reservationId, long time) {
            Long reservedTime = reservations.remove(reservationId);
            lastAccessTime = time;
            return reservedTime != null;
        }

        boolean rollbackByReservation(String reservationId, long time) {
            Long reservedTime = reservations.remove(reservationId);
            lastAccessTime = time;
            return reservedTime != null;
        }

        Long getLastReservedTime() {
            if (reservations.isEmpty()) {
                return null;
            }
            return Collections.max(reservations.values());
        }

        int getReservationCount() {
            return reservations.size();
        }

        List<String> getReservationsOlderThan(long thresholdTime) {
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, Long> entry : reservations.entrySet()) {
                if (entry.getValue() < thresholdTime) {
                    expired.add(entry.getKey());
                }
            }
            return expired;
        }
    }

    // ==================== 返回类型定义 ====================

    public static class EnforcementResult {
        public enum FailReason {
            TIME_INTERVAL,     // 时间间隔不满足
            SCORE_LIMIT,       // 比分次数超限
            TOTAL_LIMIT,       // 总次数超限
            SYSTEM_ERROR       // 系统错误
        }

        private final boolean success;
        private final FailReason failReason;
        private final String reservationId;

        private EnforcementResult(boolean success, FailReason failReason, String reservationId) {
            this.success = success;
            this.failReason = failReason;
            this.reservationId = reservationId;
        }

        public static EnforcementResult success(String reservationId) {
            return new EnforcementResult(true, null, reservationId);
        }

        public static EnforcementResult failed(FailReason reason) {
            return new EnforcementResult(false, reason, null);
        }

        public boolean isSuccess() { return success; }
        public FailReason getFailReason() { return failReason; }
        public String getReservationId() { return reservationId; }
    }

    private static class TimeCheckResult {
        private final boolean passed;
        private final long actualInterval;

        private TimeCheckResult(boolean passed, long actualInterval) {
            this.passed = passed;
            this.actualInterval = actualInterval;
        }

        public static TimeCheckResult passed(long interval) { return new TimeCheckResult(true, interval); }
        public static TimeCheckResult failed(long interval) { return new TimeCheckResult(false, interval); }

        public boolean isPassed() { return passed; }
        public long getActualInterval() { return actualInterval; }
    }
}