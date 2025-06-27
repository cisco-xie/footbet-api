package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.dict.BindLeagueDTO;
import com.example.demo.model.vo.WebsiteVO;
import com.example.demo.model.vo.dict.BindLeagueVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BindDictService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    private JSONArray defaultCompetitions;
    private JSONArray defaultTeams;

    /**
     * 获取默认联赛文件数据
     * @return
     */
    public JSONArray getDefaultCompetitions() {
        if (defaultCompetitions == null) {
            String competitionsStr = ResourceUtil.readUtf8Str("data/competitions.json");
            defaultCompetitions = JSONUtil.parseObj(competitionsStr).getJSONArray("results");
        }
        return defaultCompetitions;
    }

    /**
     * 获取默认球队文件数据
     * @return
     */
    public JSONArray getDefaultTeams() {
        if (defaultTeams == null) {
            String teamsStr = ResourceUtil.readUtf8Str("data/teams.json");
            defaultTeams = JSONUtil.parseObj(teamsStr).getJSONArray("results");
        }
        return defaultTeams;
    }

    /**
     * 获取所有已绑定的球队字典
     * @param username
     * @return
     */
    public List<List<BindLeagueVO>> getAllBindDict(String username) {
        // 生成模糊查询的 key，使用通配符 * 来匹配所有 websiteIdA 和 websiteIdB
        String patternKey = KeyUtil.genKey(RedisConstants.PLATFORM_BIND_DICT_TEAM_PREFIX, username, "*", "*");

        // 使用 Redisson 的 scan 方法进行模糊查询
        Iterable<String> keys = businessPlatformRedissonClient.getKeys().getKeysByPattern(patternKey);

        List<List<BindLeagueVO>> result = new ArrayList<>();

        // 遍历所有匹配的 key，获取对应的数据
        for (String key : keys) {
            String json = (String) businessPlatformRedissonClient.getBucket(key).get();
            if (StringUtils.isNotBlank(json)) {
                // 将每个 key 对应的 JSON 数据解析为 List<BindLeagueVO>，并添加到结果中
                List<BindLeagueVO> bindLeagueList = JSONUtil.parseArray(json).toList(BindLeagueVO.class);
                result.add(bindLeagueList);
            }
        }

        return result;
    }

    /**
     * 获取指定网站已绑定的球队字典
     * @param username
     * @return
     */
    public List<BindLeagueVO> getBindDict(String username, String websiteIdA, String websiteIdB) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_BIND_DICT_TEAM_PREFIX, username, websiteIdA, websiteIdB);

        // 从 Redis 中获取 数据
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();

        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JSONUtil.parseArray(json).toList(BindLeagueVO.class);
    }

    /**
     * 绑定球队字典
     * @param username 用户名
     * @param bindLeagueVOS 绑定信息
     */
    public void bindDict(String username, String websiteIdA, String websiteIdB, List<BindLeagueVO> bindLeagueVOS) {
        if (bindLeagueVOS.isEmpty()) {
            throw new BusinessException(SystemError.BIND_1320);
        }
        // 生成 Redis 中的 key
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_BIND_DICT_TEAM_PREFIX, username, websiteIdA, websiteIdB);

        // 获取 Redis 中的列表
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(bindLeagueVOS));
    }

    /**
     * 删除绑定
     * @param username 用户名
     * @param websiteIdA 要删除的网站ID
     */
    public void deleteBindDict(String username, String websiteIdA, String websiteIdB) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_BIND_DICT_TEAM_PREFIX, username, websiteIdA, websiteIdB);

        // 获取所有网站信息
        businessPlatformRedissonClient.getBucket(key).delete();
    }

    /**
     * 删除所有绑定
     * @param username
     */
    public void deleteBindDict(String username) {
        // 获取所有绑定信息的前缀
        String keyPattern = KeyUtil.genKey(RedisConstants.PLATFORM_BIND_DICT_TEAM_PREFIX, username, "*");

        // 获取所有匹配的键
        Iterable<String> keysIterable = businessPlatformRedissonClient.getKeys().getKeysByPattern(keyPattern);

        // 将Iterable转换为Set
        Set<String> keys = new HashSet<>();
        for (String key : keysIterable) {
            keys.add(key);
        }

        // 删除所有匹配的键
        for (String key : keys) {
            businessPlatformRedissonClient.getBucket(key).delete();
        }
    }


}
