package com.example.demo.api;

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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 获取已绑定的球队字典进行扫水比对
     * @param username
     * @return
     */
    public void getBindDict(String username) {
        List<List<BindLeagueVO>> bindLeagueVOList = bindDictService.getAllBindDict(username);
        bindLeagueVOList.forEach(list -> {
            for (BindLeagueVO bindLeagueVO : list) {
                if (StringUtils.isBlank(bindLeagueVO.getLeagueIdA()) || StringUtils.isBlank(bindLeagueVO.getLeagueIdB())) {
                    continue;
                }
                String websiteIdA = bindLeagueVO.getWebsiteIdA();
                String websiteIdB = bindLeagueVO.getWebsiteIdB();
                JSONObject eventsA = (JSONObject) handicapApi.events(username, websiteIdA);
                JSONObject eventsB = (JSONObject) handicapApi.events(username, websiteIdB);

            };
        });
    }


}
