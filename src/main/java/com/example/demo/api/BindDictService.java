package com.example.demo.api;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BindDictService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 获取已绑定的球队字典
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
    public void bindDict(String username, List<BindLeagueVO> bindLeagueVOS) {
        if (bindLeagueVOS.isEmpty()) {
            throw new BusinessException(SystemError.BIND_1320);
        }
        // 生成 Redis 中的 key
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_BIND_DICT_TEAM_PREFIX, username, bindLeagueVOS.get(0).getWebsiteIdA(), bindLeagueVOS.get(0).getWebsiteIdB());

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

}
