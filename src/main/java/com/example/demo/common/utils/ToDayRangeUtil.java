package com.example.demo.common.utils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ToDayRangeUtil {

    // 获取今天早上7点到明天早上6点的日期范围 ，如果当前时间在今天早上7点之前，返回昨天的7点到今天的6点
    public static String getToDayRange() {
        // 获取今天早上7点的时间
        LocalDateTime today7am = LocalDateTime.now().withHour(7).withMinute(0).withSecond(0).withNano(0);

        // 获取明天早上6点的时间
        LocalDateTime tomorrow6am = today7am.plusDays(1).withHour(6);

        // 获取当前时间
        LocalDateTime currentTime = LocalDateTime.now();

        // 计算日期范围
        String startDate = getFormattedDate(today7am);
        String endDate = getFormattedDate(tomorrow6am);

        // 如果当前时间在今天早上7点之前
        if (currentTime.isBefore(today7am)) {
            // 当前时间在今天7点之前，则输出昨天早上7点到今天早上6点
            startDate = getFormattedDate(today7am.minusDays(1));
            endDate = getFormattedDate(today7am.minusHours(1)); // 今天早上6点
        } else if (currentTime.isBefore(tomorrow6am)) {
            // 当前时间在今天早上7点到明天早上6点之间
            // 输出今天早上7点到明天早上6点
            startDate = getFormattedDate(today7am);
            endDate = getFormattedDate(tomorrow6am); // 明天早上6点
        }

        return startDate + "-" + endDate;
    }

    // 格式化日期为 yyyyMMdd07 或 yyyyMMdd06
    private static String getFormattedDate(LocalDateTime dateTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String formattedDate = dateTime.format(formatter);
        // 根据小时判断是07点还是06点
        return formattedDate + (dateTime.getHour() == 7 ? "07" : "06");
    }

    // 获取昨天的今天早上7点
    public static LocalDateTime getYesterday7am() {
        LocalDateTime today7am = LocalDateTime.now().withHour(7).withMinute(0).withSecond(0).withNano(0);
        return today7am.minusDays(1);
    }

    // 获取明天的今天早上6点
    public static LocalDateTime getTomorrow6am() {
        LocalDateTime today7am = LocalDateTime.now().withHour(7).withMinute(0).withSecond(0).withNano(0);
        return today7am.plusDays(1).withHour(6);
    }

    /**
     * 获取最近指定minutes分钟内的时间,返回 HHmm 格式的时间字符串列表
     * eg:假设现在是 17:58,minutes传入3,则返回 ["1756", "1757", "1758"]
     * @param minutes
     * @return
     */
    public static List<String> getRecentMinuteKeys(int minutes) {
        List<String> keys = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HHmm");

        for (int i = minutes - 1; i >= 0; i--) {
            String key = now.minusMinutes(i).format(formatter);
            keys.add(key);
        }

        return keys;
    }

    /**
     * 获取最近10天的开始和结束日期
     * @return List，索引0是开始日期，索引1是结束日期
     */
    public static List<LocalDate> getLast10Days() {
        LocalDate today = LocalDate.now();
        LocalDate startDay = today.minusDays(10);
        return Arrays.asList(startDay, today);
    }

    public static void main(String[] args) {
        // 示例调用
        System.out.println("日期范围: " + ToDayRangeUtil.getToDayRange());
    }

}
