package com.example.demo.model.vo.dict;

import lombok.Data;

import java.util.List;

@Data
public class BindLeagueVO {
    private String id;
    private String websiteIdA;
    private String websiteIdB;
    private String leagueIdA;
    private String leagueIdB;
    private String leagueNameA;
    private String leagueNameB;
    private List<BindTeamVO> events;
}
