package com.example.demo.api;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONNull;
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
import java.util.function.Consumer;
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

        List<String> jsonList = businessPlatformRedissonClient.getList(key);
        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }

        List<ConfigAccountVO> accountList = new ArrayList<>(jsonList.size());

        // 递归替换 JSONNull / null -> "" 的函数
        final Consumer<Object> replaceNullWithEmptyString = new Consumer<Object>() {
            @SuppressWarnings("unchecked")
            public void accept(Object o) {
                if (o == null) return;
                if (o instanceof JSONObject) {
                    JSONObject obj = (JSONObject) o;
                    // 复制 key 列表，避免遍历时修改引发 ConcurrentModification
                    List<String> keys = new ArrayList<>(obj.keySet());
                    for (String k : keys) {
                        Object v = obj.get(k);
                        // JSONNull -> 空字符串
                        if (v instanceof JSONNull) {
                            obj.putOpt(k, "");
                        }
                        // explicit Java null (Hutool may return null) -> 空字符串
                        else if (v == null) {
                            obj.putOpt(k, "");
                        }
                        // 嵌套对象递归
                        else if (v instanceof JSONObject) {
                            accept(v);
                        }
                        // 数组递归
                        else if (v instanceof JSONArray) {
                            accept(v);
                        }
                        // 其他类型（String/Number/Boolean）保持不变
                    }
                } else if (o instanceof JSONArray) {
                    JSONArray arr = (JSONArray) o;
                    for (int i = 0; i < arr.size(); i++) {
                        Object el = arr.get(i);
                        if (el instanceof JSONNull) {
                            arr.set(i, "");
                        } else if (el == null) {
                            arr.set(i, "");
                        } else if (el instanceof JSONObject || el instanceof JSONArray) {
                            accept(el);
                        }
                        // 基本类型跳过
                    }
                }
            }
        };

        for (String json : jsonList) {
            try {
                JSONObject jo = JSONUtil.parseObj(json);

                // 对整个 JSON 做替换（递归）
                replaceNullWithEmptyString.accept(jo);

                // 保证 token 字段保留，但如果 token 中又嵌套不规范结构，已经被上面递归替换为 "" 或处理过
                // 例如 { "sm": { "b": null } } -> { "sm": { "b": "" } }

                // 将清洗后的 JSON 转为 VO
                ConfigAccountVO vo = JSONUtil.toBean(jo.toString(), ConfigAccountVO.class);
                accountList.add(vo);
            } catch (Exception e) {
                // 单条记录解析失败时记录并跳过
                log.error("解析账号 JSON 失败，json={}, err={}", json, e.getMessage(), e);
            }
        }

        // 原逻辑：如果 website 存在，覆盖 websiteUrl 为 website.baseUrls[0]
        if (website != null && website.getBaseUrls() != null && !website.getBaseUrls().isEmpty()) {
            String baseUrl = website.getBaseUrls().get(0);
            accountList.forEach(account -> account.setWebsiteUrl(baseUrl));
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
     * 批量把某网站下所有账号的 websiteUrl 设置为 newWebsiteUrl（覆盖原值）。
     * 这个方法在内部会一次性读取 Redis 列表、在内存中修改并一次性写回（在分布式锁内）。
     */
    public void updateAccountsWebsiteUrlBatch(String username, String websiteId, String newWebsiteUrl) {
        String accountKey = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);
        RList<String> rList = businessPlatformRedissonClient.getList(accountKey);
        RLock lock = businessPlatformRedissonClient.getLock("lock:" + accountKey);

        try {
            // 尽量少锁时间：先获取列表（如果 client 返回的是远程视图，这一步可能不会做网络请求）
            lock.lock(10, TimeUnit.SECONDS);
            List<String> origin = new ArrayList<>(rList.size());
            origin.addAll(rList); // 读取当前所有 JSON 字符串

            if (origin.isEmpty()) {
                log.info("updateAccountsWebsiteUrlBatch: no accounts for websiteId={}, key={}", websiteId, accountKey);
                return;
            }

            List<String> updated = new ArrayList<>(origin.size());
            for (String accJson : origin) {
                try {
                    JSONObject jo = JSONUtil.parseObj(accJson);
                    // 覆盖 websiteUrl 字段
                    jo.putOpt("websiteUrl", newWebsiteUrl);
                    // 如果 saveAccount 序列化期望某些默认字段，按需设置
                    updated.add(jo.toString());
                } catch (Exception ex) {
                    // 单条解析失败则记录并直接保持原值（或选择跳过）
                    log.error("更新 account websiteUrl 时单条解析失败, json={}, err={}", accJson, ex.getMessage(), ex);
                    updated.add(accJson);
                }
            }

            // 覆盖写回：清空 + addAll（或使用你的 client 的覆盖写方法）
            rList.clear();
            rList.addAll(updated);

            log.info("批量更新完成：websiteId={} -> {} (updated {} of {})", websiteId, newWebsiteUrl, updated.size(), origin.size());
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            } catch (Exception ignore) {}
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
                account.setExecuteMsg(null);
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
                    account.setExecuteMsg(null);
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
