package com.example.demo.api;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.model.vo.dict.BindLeagueVO;
import com.example.demo.model.vo.dict.BindTeamVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.*;

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
    public void sweepwater(String username) {
        List<List<BindLeagueVO>> bindLeagueVOList = bindDictService.getAllBindDict(username);

        // 用来记录已经获取过的ecid，避免重复调用
        Map<String, JSONArray> fetchedEcidSetA = new HashMap<>();
        Map<String, JSONArray> fetchedEcidSetB = new HashMap<>();

        // 遍历bindLeagueVOList
        for (List<BindLeagueVO> list : bindLeagueVOList) {
            for (BindLeagueVO bindLeagueVO : list) {
                if (StringUtils.isBlank(bindLeagueVO.getLeagueIdA()) || StringUtils.isBlank(bindLeagueVO.getLeagueIdB())) {
                    continue;
                }

                String websiteIdA = bindLeagueVO.getWebsiteIdA();
                String websiteIdB = bindLeagueVO.getWebsiteIdB();
                JSONArray eventsA = new JSONArray();
                JSONArray eventsB = new JSONArray();

                // 遍历bindLeagueVO中的每个事件
                for (BindTeamVO events : bindLeagueVO.getEvents()) {
                    String ecidA = events.getEcidA();  // 获取ecidA
                    String ecidB = events.getEcidB();  // 获取ecidB

                    // 处理ecidA
                    eventsA = getEventsForEcid(username, fetchedEcidSetA, websiteIdA, bindLeagueVO.getLeagueIdA(), ecidA);

                    // 处理ecidB
                    eventsB = getEventsForEcid(username, fetchedEcidSetB, websiteIdB, bindLeagueVO.getLeagueIdB(), ecidB);

                    if (eventsA == null || eventsB == null) {
                        continue;
                    }

                    // 查找对应的eventAJson和eventBJson
                    JSONObject eventAJson = findEventByLeagueId(eventsA, bindLeagueVO.getLeagueIdA());
                    JSONObject eventBJson = findEventByLeagueId(eventsB, bindLeagueVO.getLeagueIdB());

                    if (eventAJson == null || eventBJson == null) {
                        continue;
                    }

                    // 调用aggregateEventOdds进行合并
                    JSONObject result = aggregateEventOdds(eventAJson, eventBJson, events.getNameA(), events.getNameB());
                    System.out.println(result);
                }
            }
        }
    }

    private JSONArray getEventsForEcid(String username, Map<String, JSONArray> fetchedEcidSet, String websiteId, String leagueId, String ecid) {
        if (StringUtils.isNotBlank(ecid)) {
            if (fetchedEcidSet.containsKey(ecid)) {
                return fetchedEcidSet.get(ecid);
            } else {
                // 根据ecid获取数据
                JSONArray events = (JSONArray) handicapApi.eventsOdds(username, websiteId, leagueId, ecid);
                fetchedEcidSet.put(ecid, events);
                return events;
            }
        } else {
            if (fetchedEcidSet.containsKey(ecid)) {
                return fetchedEcidSet.get(ecid);
            } else {
                // 获取默认的events数据
                JSONArray events = (JSONArray) handicapApi.eventsOdds(username, websiteId, null, null);
                fetchedEcidSet.put(ecid, events);
                return events;
            }
        }
    }

    private JSONObject findEventByLeagueId(JSONArray events, String leagueId) {
        for (Object eventObj : events) {
            JSONObject eventJson = (JSONObject) eventObj;
            if (leagueId.equals(eventJson.getStr("id"))) {
                return eventJson;
            }
        }
        return null; // 如果没有找到，返回null
    }


    public static JSONObject aggregateEventOdds(JSONObject eventAJson, JSONObject eventBJson, String teamNameA, String teamNameB) {
        JSONObject result = new JSONObject();

        Map<String, Map<String, String>> combinedOdds = new HashMap<>();

        // 遍历第一个 JSON 的事件列表
        for (Object eventA : eventAJson.getJSONArray("events")) {
            JSONObject aJson = (JSONObject) eventA;
            String nameA = aJson.getStr("name");
            JSONObject fullCourtA = aJson.getJSONObject("fullCourt");

            // 遍历第二个 JSON 的事件列表
            for (Object event2 : eventBJson.getJSONArray("events")) {
                JSONObject bJson = (JSONObject) event2;
                String nameB = bJson.getStr("name");
                JSONObject fullCourtB = bJson.getJSONObject("fullCourt");

                // 检查是否是相反的队伍
                if (nameA.equals(teamNameA) && !nameB.equals(teamNameB) ||
                        nameA.equals(teamNameB) && !nameB.equals(teamNameA)) {

                    // 创建一个 Map 来存储相同的队伍的赔率
                    Map<String, String> eventOdds = new HashMap<>();

                    // 动态遍历 fullCourt 中的每个键（如 letBall、overSize）
                    for (String key : fullCourtA.keySet()) {
                        if (fullCourtB.containsKey(key)) {
                            if ("win".equals(key) || "draw".equals(key)) {
                                double value1 = fullCourtA.getDouble(key);
                                if (fullCourtB.containsKey(key) && fullCourtB.getDouble(key) != 0) {
                                    double value2 = fullCourtB.getDouble(key);
                                    eventOdds.put(key, String.format("%.3f", value1 + value2 + 2));
                                }
                            } else {
                                // 获取该键的赔率值并相加
                                JSONObject letBall1 = fullCourtA.getJSONObject(key);
                                JSONObject letBall2 = fullCourtB.getJSONObject(key);
                                for (String subKey : letBall1.keySet()) {
                                    double value1 = letBall1.getDouble(subKey);
                                    if (letBall2.containsKey(subKey)) {
                                        double value2 = letBall2.getDouble(subKey);
                                        eventOdds.put(subKey, String.format("%.3f", value1 + value2 + 2));
                                    }
                                }
                            }
                        }
                    }

                    // 将结果存入 Map 中
                    combinedOdds.put(nameA + " vs " + nameB, eventOdds);
                }
            }
        }

        // 将结果返回给 JSONObject
        for (Map.Entry<String, Map<String, String>> entry : combinedOdds.entrySet()) {
            JSONObject odds = new JSONObject(entry.getValue());
            result.putOpt(entry.getKey(), odds);
        }

        return result;
    }

}
