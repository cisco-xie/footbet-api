package com.example.demo.api;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.model.vo.ConfigAccountVO;
import com.example.demo.model.vo.WebsiteVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RList;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ConfigAccountService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    @Resource
    private WebsiteService websiteService;

    /**
     * 获取网站账户列表
     * @param username
     * @return
     */
    public List<ConfigAccountVO> getAccount(String username, String websiteId) {

        WebsiteVO website = websiteService.getWebsite(username, websiteId);

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);

        // 从 Redis 中获取 List 数据
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();  // 如果 Redis 中没有数据，返回一个空列表
        }

        // 将 List 中的 JSON 字符串反序列化为 WebSiteVO 列表
        List<ConfigAccountVO> accountList = jsonList.stream()
                .map(json -> JSONUtil.toBean(json, ConfigAccountVO.class))
                .collect(Collectors.toList());

        // 如果 website 不为空，将 baseUrls 的第一个值赋给每个 ConfigAccountVO 的 url
        if (website != null && website.getBaseUrls() != null && !website.getBaseUrls().isEmpty()) {
            String baseUrl = website.getBaseUrls().get(0); // 获取第一个 baseUrl
            accountList.forEach(account -> account.setWebsiteUrl(baseUrl)); // 设置 url
        }
        return accountList;
    }

    /**
     * 获取网站账户信息
     * @param username
     * @return
     */
    public ConfigAccountVO getAccountById(String username, String websiteId, String accountId) {

        WebsiteVO website = websiteService.getWebsite(username, websiteId);

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);

        // 从 Redis 中获取 List 数据
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return null;
        }

        // 将 List 中的 JSON 字符串反序列化为 ConfigAccountVO
        ConfigAccountVO accountVO = jsonList.stream()
                .map(json -> JSONUtil.toBean(json, ConfigAccountVO.class))
                .filter(account -> account.getId().equals(accountId))
                .findFirst() // 找到第一个匹配的对象
                .orElse(null); // 如果没有匹配对象，返回 null;

        if (accountVO == null) {
            return null;
        }

        // 如果 website 不为空，将 baseUrls 的第一个值赋给每个 ConfigAccountVO 的 url
        if (website != null && website.getBaseUrls() != null && !website.getBaseUrls().isEmpty()) {
            String baseUrl = website.getBaseUrls().get(0); // 获取第一个 baseUrl
            accountVO.setWebsiteUrl(baseUrl); // 设置 url
        }
        return accountVO;
    }

    /**
     * 新增或修改账户
     * @param username 用户名
     * @param configAccountVO 网站信息
     */
    public void saveAccount(String username, String websiteId, ConfigAccountVO configAccountVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);
        RList<String> accountList = businessPlatformRedissonClient.getList(key);
        RLock lock = businessPlatformRedissonClient.getLock("lock:" + key);

        try {
            lock.lock(10, TimeUnit.SECONDS); // 获取分布式锁，防止并发覆盖

            // 若账户 ID 为空则生成
            if (StringUtils.isBlank(configAccountVO.getId())) {
                configAccountVO.setId(IdUtil.getSnowflakeNextIdStr());
            }

            List<String> updatedList = new ArrayList<>();
            boolean updated = false;

            // 遍历当前账户列表，若存在则替换
            for (String json : accountList) {
                ConfigAccountVO account = JSONUtil.toBean(json, ConfigAccountVO.class);
                if (account.getId().equals(configAccountVO.getId())) {
                    updatedList.add(JSONUtil.toJsonStr(configAccountVO));
                    updated = true;
                } else {
                    updatedList.add(json);
                }
            }

            // 如果未更新说明是新增
            if (!updated) {
                configAccountVO.setWebsiteId(websiteId);
                updatedList.add(JSONUtil.toJsonStr(configAccountVO));
            }

            // 覆盖原有列表
            accountList.clear();
            accountList.addAll(updatedList);

        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除账户
     * @param username 用户名
     * @param websiteId 要删除的网站ID
     */
    public void deleteAccount(String username, String websiteId, String accountId) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);
        RList<String> list = businessPlatformRedissonClient.getList(key);

        for (String json : list) {
            ConfigAccountVO account = JSONUtil.toBean(json, ConfigAccountVO.class);
            if (account.getId().equals(accountId)) {
                list.remove(json);
                break;
            }
        }
    }

    /**
     * 启停账户
     * @param username 用户名
     * @param websiteId 网站 ID
     * @param isEnable 启用状态（1启用，0停用）
     */
    public void enable(String username, String websiteId, Integer isEnable) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);
        RList<String> accountList = businessPlatformRedissonClient.getList(key);
        RLock lock = businessPlatformRedissonClient.getLock("lock:" + key);

        try {
            lock.lock(10, TimeUnit.SECONDS);

            List<String> updated = new ArrayList<>();
            for (String json : accountList) {
                ConfigAccountVO account = JSONUtil.toBean(json, ConfigAccountVO.class);
                account.setEnable(isEnable);
                updated.add(JSONUtil.toJsonStr(account));
            }
            accountList.clear();
            accountList.addAll(updated);

        } finally {
            lock.unlock();
        }
    }

    /**
     * 启停账户
     * @param username 用户名
     * @param websiteId 网站 ID
     * @param isEnable 启用状态（1启用，0停用）
     */
    public void autoLoginEnable(String username, String websiteId, Integer isEnable) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);
        RList<String> accountList = businessPlatformRedissonClient.getList(key);
        RLock lock = businessPlatformRedissonClient.getLock("lock:" + key);

        try {
            lock.lock(10, TimeUnit.SECONDS);

            List<String> updated = new ArrayList<>();
            for (String json : accountList) {
                ConfigAccountVO account = JSONUtil.toBean(json, ConfigAccountVO.class);
                account.setAutoLogin(isEnable);
                updated.add(JSONUtil.toJsonStr(account));
            }
            accountList.clear();
            accountList.addAll(updated);

        } finally {
            lock.unlock();
        }
    }

    /**
     * 退出所有账户 - 即清空所有 token
     * @param username 用户名
     * @param websiteId 网站 ID
     * @param accountId 可选，若不为空只清除对应 ID
     */
    public void logoutByWebsite(String username, String websiteId, String accountId) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);
        RList<String> redisList = businessPlatformRedissonClient.getList(key);
        RLock lock = businessPlatformRedissonClient.getLock("lock:" + key);

        try {
            lock.lock(10, TimeUnit.SECONDS);

            List<String> updatedList = new ArrayList<>();
            for (String json : redisList) {
                ConfigAccountVO account = JSONUtil.toBean(json, ConfigAccountVO.class);

                // 如果指定了 accountId，跳过非目标账户
                if (StringUtils.isNotBlank(accountId) && !accountId.equals(account.getId())) {
                    updatedList.add(json);
                    continue;
                }

                // 清空 token 信息
                account.setIsTokenValid(0);
                account.setToken(new JSONObject());
                updatedList.add(JSONUtil.toJsonStr(account));
            }

            redisList.clear();
            redisList.addAll(updatedList);

        } finally {
            lock.unlock();
        }
    }

    /**
     * 退出指定账户 - 即清空指定账户 token
     * @param username 用户名
     * @param websiteId 网站 ID
     * @param accountId 账户 ID
     */
    public void logoutByWebsiteAndAccountId(String username, String websiteId, String accountId) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);
        RList<String> accountList = businessPlatformRedissonClient.getList(key);
        RLock lock = businessPlatformRedissonClient.getLock("lock:" + key);

        try {
            lock.lock(10, TimeUnit.SECONDS);

            List<String> updatedList = new ArrayList<>();
            for (String json : accountList) {
                ConfigAccountVO account = JSONUtil.toBean(json, ConfigAccountVO.class);
                if (account.getId().equals(accountId)) {
                    account.setIsTokenValid(0);
                    account.setToken(new JSONObject());
                }
                updatedList.add(JSONUtil.toJsonStr(account));
            }
            accountList.clear();
            accountList.addAll(updatedList);

        } finally {
            lock.unlock();
        }
    }

    /**
     * 退出系统用户和网站进行id去重
     * @param username
     * @param websiteId
     */
    public void deduplicateLargeAccountList(String username, String websiteId) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);
        RList<String> redisList = businessPlatformRedissonClient.getList(key);
        RLock lock = businessPlatformRedissonClient.getLock("lock:" + key);

        try {
            lock.lock(10, TimeUnit.MINUTES); // 批量操作，延长锁时间

            int total = redisList.size();
            log.info("开始去重，Redis 列表总长度：{}", total);

            Set<String> seenIds = new HashSet<>(total / 2); // 估算初始容量
            List<String> dedupedList = new ArrayList<>(total);

            for (int i = 0; i < total; i++) {
                String json = redisList.get(i);
                try {
                    ConfigAccountVO vo = JSONUtil.toBean(json, ConfigAccountVO.class);
                    if (seenIds.add(vo.getId())) {
                        dedupedList.add(json); // 新 ID，保留
                    }
                } catch (Exception e) {
                    log.warn("解析异常，跳过非法JSON: {}", json);
                }

                if (i % 10_000 == 0) {
                    log.info("已处理 {} 条...", i);
                }
            }

            // 分批写入，避免超大 payload
            redisList.clear();
            int batchSize = 10_000;
            for (int i = 0; i < dedupedList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dedupedList.size());
                redisList.addAll(dedupedList.subList(i, end));
            }

            log.info("去重完成，原始数量={}，去重后={}", total, dedupedList.size());

        } finally {
            lock.unlock();
        }
    }

}
