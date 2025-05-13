package com.example.demo.model.dto.bet;

import cn.hutool.json.JSONObject;
import lombok.Data;

@Data
public class SweepwaterBetDTO {
    private String id;
    private String oddsIdA;
    private String oddsIdB;
    private String selectionIdA;    // 平博网站的投注专属参数
    private String selectionIdB;    // 平博网站的投注专属参数
    private String websiteIdA;
    private String websiteIdB;
    private String websiteNameA;
    private String websiteNameB;
    private String leagueIdA;       // 联赛id
    private String leagueIdB;       // 联赛id
    private String eventIdA;        // 比赛id
    private String eventIdB;        // 比赛id
    private String leagueNameA;
    private String leagueNameB;
    private String type;
    private String handicapType;
    private String league;
    private String team;
    private String project;
    private String odds;
    private String oddsA;
    private String oddsB;
    private Integer isBet;          // 是否进行过投注
    private String betTimeA;        // 投注时间
    private String betTimeB;        // 投注时间
    private String betAccountIdA;   // 进行投注的盘口账号id
    private String betAccountIdB;   // 进行投注的盘口账号id
    private String betAccountA;     // 进行投注的盘口账号名称
    private String betAccountB;     // 进行投注的盘口账号名称
    private String betIdA;          // 投注成功后盘口返回的id
    private String betIdB;          // 投注成功后盘口返回的id
    private JSONObject betInfoA;    // 投注成功后获取盘口的未结注单
    private JSONObject betInfoB;    // 投注成功后获取盘口的未结注单
    private String decimalOddsA;    // 智博网站的投注专属参数
    private String decimalOddsB;    // 智博网站的投注专属参数
    private String handicapA;       // 智博网站的投注专属参数
    private String handicapB;       // 智博网站的投注专属参数
    private String scoreA;          // 智博网站的投注专属参数
    private String scoreB;          // 智博网站的投注专属参数

    private String strongA;         // 新2网站的投注专属参数
    private String strongB;         // 新2网站的投注专属参数
    private String gTypeA;          // 新2网站的投注专属参数
    private String gTypeB;          // 新2网站的投注专属参数
    private String wTypeA;          // 新2网站的投注专属参数
    private String wTypeB;          // 新2网站的投注专属参数
    private String rTypeA;          // 新2网站的投注专属参数
    private String rTypeB;          // 新2网站的投注专属参数
    private String choseTeamA;      // 新2网站的投注专属参数
    private String choseTeamB;      // 新2网站的投注专属参数
    private String conA;            // 新2网站的投注专属参数
    private String conB;            // 新2网站的投注专属参数
    private String ratioA;          // 新2网站的投注专属参数
    private String ratioB;          // 新2网站的投注专属参数

    private String water;           // 水位
}
