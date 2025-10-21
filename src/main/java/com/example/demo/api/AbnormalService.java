package com.example.demo.api;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.model.vo.dict.BindLeagueVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AbnormalService {

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    private JSONArray defaultCompetitions;
    private JSONArray defaultTeams;

    /**
     * 查询未读的非正常投注记录
     * @param username
     * @return
     */
    public Object getUnReadAbnormal(String username) {
        // 取昨天的日期
        String date = LocalDateTimeUtil.format(LocalDate.now().minusDays(1), DatePattern.NORM_DATE_PATTERN);
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTLED_NORMAL_PREFIX, username, date);

        Object cache = businessPlatformRedissonClient.getBucket(key).get();
        if (cache == null) {
            return null;
        }

        JSONObject abnormal = JSONUtil.parseObj(cache);

        // 只返回 read=false 的数据
        Boolean read = abnormal.getBool("read", true);
        if (!read) {
            return abnormal;
        }
        return null;
    }

    /**
     * 给非正常投注数据记录设置为已读
     * @param username
     * @return
     */
    public boolean markAbnormalAsRead(String username) {
        String date = LocalDateTimeUtil.format(LocalDate.now().minusDays(1), DatePattern.NORM_DATE_PATTERN);
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTLED_NORMAL_PREFIX, username, date);

        Object cache = businessPlatformRedissonClient.getBucket(key).get();
        if (cache == null) {
            return false;
        }

        JSONObject abnormal = JSONUtil.parseObj(cache);

        // 如果已读则不更新
        Boolean read = abnormal.getBool("read", true);
        if (read) {
            return false;
        }

        // 写入 read=true 并保持原 TTL（避免覆盖过期时间）
        abnormal.putOpt("read", true);
        businessPlatformRedissonClient.getBucket(key).set(abnormal); // 默认保持原 TTL

        return true;
    }

}
