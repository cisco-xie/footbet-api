package com.example.demo.common.enmu;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 游戏类型定义枚举常量
 */
@Getter
@AllArgsConstructor
public enum GameType {
    JSC11X5("11X5JSC", "DX1,DX2,DX3,DX4,DX5,ZDX,ZWDX,ZDS,DS1,DS2,DS3,DS4,DS5,LH", "极速11选5"),
    HK6JSC("HK6JSC", "TMA,DX,DS,HDS,HDX,WDX,TDXDS,SB", "极速六合彩"),
    K3JSC("K3JSC", "", "极速快3"),
    KL8JSC("KL8JSC", "ZDX,ZDS,ZHT,DXDS,QHH,DSH,WX", "极速快乐8"),
    KLSFJSC("KLSFJSC", "DX1,DX2,DX3,DX4,DX5,DX6,DX7,DX8,WDX1,WDX2,WDX3,WDX4,WDX5,WDX6,WDX7,WDX8,DS1,DS2,DS3,DS4,DS5,DS6,DS7,DS8,HDS1,HDS2,HDS3,HDS4,HDS5,HDS6,HDS7,HDS8,ZDX,ZDS,ZWDX,LH1,LH2,LH3,LH4,B1,B2,B3,B4,B5,B6,B7,B8,ZM,MP,ZFB,WDX1,WDX2,WDX3,WDX4,WDX5,WDX6,WDX7,WDX8,HDS1,HDS2,HDS3,HDS4,HDS5,HDS6,HDS7,HDS8,FS,FW,FW1,FW2,FW3,FW4,FW5,FW6,FW7,FW8,ZFB1,ZFB2,ZFB3,ZFB4,ZFB5,ZFB6,ZFB7,ZFB8,LH1,LH2,LH3,LH4,LM2,LM22,LM3,LM32,LM4,LM5", "极速快乐十分"),
    SSCJSC("SSCJSC", "DX1,DX2,DX3,DX4,DX5,DS1,DS2,DS3,DS4,DS5,ZDX,ZDS,LH,TS1,TS2,TS3,B1,B2,B3,B4,B5,DN,DNDX,DNDS,DNGP,DNYD,DNLD,DNSANT,DNSHUNZ,DNHL,DNSIT,DNWT,1ZTS1,1ZTS2,1ZTS3,1ZTS5,2ZTS1,2ZTS2,2ZTS3,3ZTS1,3ZTS2,3ZTS3,DW21,DW31,DW32,DW41,DW42,DW43,DW51,DW52,DW53,DW54,DW321,DW432,DW543,HS21,HS31,HS32,HS41,HS42,HS43,HS51,HS52,HS53,HS54,HWS21,HWS31,HWS32,HWS41,HWS42,HWS43,HWS51,HWS52,HWS53,HWS54,HS543,HS432,HS321,HWS543,HWS432,HWS321,ZX3TS1,ZX3TS2,ZX3TS3,ZX6TS1,ZX6TS2,ZX6TS3,FSTS1,FSTS2,FSTS3,KDTS1,KDTS2,KDTS3,ZX6TS1,ZX6TS2,ZX6TS3,ZX3", "极速时时彩"),
    PK10JSCNN("PK10JSCNN", "FB,PB", "极速牛牛"),
    FTJSC("FTJSC", "FTF,FTN,FTJ,FTT,FTZ,SM,DS", "极速番摊"),
    PK10JSC("PK10JSC", "DX1,DX2,DX3,DX4,DX5,DX6,DX7,DX8,DX9,DX10,DS1,DS2,DS3,DS4,DS5,DS6,DS7,DS8,DS9,DS10,GDX,GDS,LH1,LH2,LH3,LH4,LH5,B1,B2,B3,B4,B5,B6,B7,B8,B9,B10,GDX,GDS,GYH", "极速赛车");

    private final String lottery;
    private final String games;
    private final String description;

    // 获取所有游戏类型的映射
    public static Map<String, String> getAllGameRulesWithDescriptions() {
        Map<String, String> params = new HashMap<>();
        for (GameType type : values()) {
            params.put(type.getDescription(), type.getGames());
        }
        return params;
    }

    // 根据code获取枚举实例
    public static GameType getByLottery(String lottery) {
        for (GameType type : values()) {
            if (type.getLottery().equals(lottery)) {
                return type;
            }
        }
        return null;
    }
}

