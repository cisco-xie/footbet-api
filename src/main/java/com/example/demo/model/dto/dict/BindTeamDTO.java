package com.example.demo.model.dto.dict;

import lombok.Data;

@Data
public class BindTeamDTO {
    private String pid;
    private String idA;
    private String nameA;
    private String ecidA;
    private Boolean isHomeA;
    private String idB;
    private String nameB;
    private String ecidB;
    private Boolean isHomeB;
}
