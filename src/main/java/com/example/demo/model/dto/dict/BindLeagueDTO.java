package com.example.demo.model.dto.dict;

import lombok.Data;

import java.util.List;

@Data
public class BindLeagueDTO {
    private String id;
    private String websiteIdA;
    private String websiteIdB;
    private String leagueIdA;
    private String leagueIdB;
    private String leagueNameA;
    private String leagueNameB;
    private List<BindTeamDTO> events;
}
