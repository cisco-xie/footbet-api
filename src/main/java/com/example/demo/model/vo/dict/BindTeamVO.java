package com.example.demo.model.vo.dict;

import lombok.Data;

@Data
public class BindTeamVO {
    private String pid;
    private String idA;     // 比赛id
    private String nameA;
    private Boolean isHomeA;
    private String ecidA;   // 比赛id-新二网站专属参数
    private String idB;     // 比赛id
    private String nameB;
    private String ecidB;   // 比赛id-新二网站专属参数
    private Boolean isHomeB;
}
