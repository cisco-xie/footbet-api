package com.example.demo.common.utils;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class MatchMatchUtil {

    private static final String REDIS_KEY_ALL_MATCHES = RedisConstants.PLATFORM_599_MATCHES_PREFIX + ":matches";

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 从Redis中匹配最佳赛事
     * @param teamName 球队名称（格式：主队 -vs- 客队）
     * @return 匹配到的赛事详情，包含detailUrl
     */
    public JSONObject findBestMatch(String teamName) {
        if (teamName == null || teamName.isEmpty()) {
            return null;
        }

        try {
            Object matchesJson = businessPlatformRedissonClient.getBucket(REDIS_KEY_ALL_MATCHES).get();
            if (matchesJson == null) {
                log.warn("Redis中没有赛事列表数据");
                return null;
            }

            JSONArray matches = JSONUtil.parseArray(matchesJson);
            if (matches.isEmpty()) {
                log.warn("Redis中赛事列表为空");
                return null;
            }

            return findBestMatchFromList(matches, teamName);

        } catch (Exception e) {
            log.error("从Redis匹配赛事失败: teamName={}", teamName, e);
            return null;
        }
    }

    /**
     * 从赛事列表中找到匹配度最高的赛事
     * 必须返回匹配度最高的赛事，即使匹配度很低
     */
    private JSONObject findBestMatchFromList(JSONArray matches, String teamName) {
        double bestScore = 0;
        JSONObject bestMatch = null;

        for (int i = 0; i < matches.size(); i++) {
            JSONObject match = matches.getJSONObject(i);
            double score = calculateMatchScore(match, teamName);

            if (score > bestScore) {
                bestScore = score;
                bestMatch = match;
            }
        }

        if (bestMatch != null) {
            log.info("匹配到赛事: teamName={}, score={}, match={}", teamName, bestScore, 
                    bestMatch.getStr("detailUrl"));
            return bestMatch;
        } else {
            log.warn("未找到任何赛事: teamName={}", teamName);
            return null;
        }
    }

    /**
     * 计算匹配分数
     * 使用智能相似度算法，不依赖预定义别名
     */
    private double calculateMatchScore(JSONObject match, String teamName) {
        if (match == null || teamName == null || teamName.isEmpty()) {
            return 0;
        }

        String homeTeam = match.getStr("homeTeam");
        String awayTeam = match.getStr("awayTeam");

        if (homeTeam == null && awayTeam == null) {
            return 0;
        }

        String[] inputTeams = teamName.split(" -vs- ");
        if (inputTeams.length != 2) {
            return 0;
        }

        String inputHome = normalizeTeamName(inputTeams[0].trim());
        String inputAway = normalizeTeamName(inputTeams[1].trim());

        String cleanHome = homeTeam != null ? normalizeTeamName(homeTeam) : "";
        String cleanAway = awayTeam != null ? normalizeTeamName(awayTeam) : "";

        // 计算双向匹配分数
        double score1 = calculateTeamPairSimilarity(inputHome, inputAway, cleanHome, cleanAway);
        double score2 = calculateTeamPairSimilarity(inputAway, inputHome, cleanHome, cleanAway);

        return Math.max(score1, score2);
    }

    /**
     * 计算两队组合的相似度分数
     */
    private double calculateTeamPairSimilarity(String inputHome, String inputAway, 
                                               String targetHome, String targetAway) {
        double homeScore = calculateSingleTeamSimilarity(inputHome, targetHome);
        double awayScore = calculateSingleTeamSimilarity(inputAway, targetAway);
        
        return (homeScore + awayScore) / 2.0;
    }

    /**
     * 计算单个球队名称的相似度
     * 使用多种相似度算法综合评分
     */
    private double calculateSingleTeamSimilarity(String inputTeam, String targetTeam) {
        if (inputTeam == null || targetTeam == null || inputTeam.isEmpty() || targetTeam.isEmpty()) {
            return 0;
        }

        // 完全匹配
        if (inputTeam.equalsIgnoreCase(targetTeam)) {
            return 1.0;
        }

        // 词级匹配
        List<String> inputWords = splitIntoWords(inputTeam);
        List<String> targetWords = splitIntoWords(targetTeam);

        if (inputWords.isEmpty() || targetWords.isEmpty()) {
            return 0;
        }

        // 计算共同词数（使用模糊匹配）
        int commonWords = 0;
        int totalWords = Math.max(inputWords.size(), targetWords.size());
        
        for (String inputWord : inputWords) {
            for (String targetWord : targetWords) {
                if (wordsMatch(inputWord, targetWord)) {
                    commonWords++;
                    break;
                }
            }
        }

        double wordMatchScore = (double) commonWords / totalWords;

        // 计算Levenshtein编辑距离相似度
        double editDistanceScore = calculateLevenshteinSimilarity(inputTeam, targetTeam);

        // 计算Jaccard相似度
        double jaccardScore = calculateJaccardSimilarity(inputTeam, targetTeam);

        // 综合评分：词匹配占40%，编辑距离占35%，Jaccard占25%
        double finalScore = wordMatchScore * 0.4 + editDistanceScore * 0.35 + jaccardScore * 0.25;

        return finalScore;
    }

    /**
     * 判断两个词是否匹配（支持模糊匹配）
     */
    private boolean wordsMatch(String word1, String word2) {
        if (word1 == null || word2 == null) {
            return false;
        }

        String w1 = word1.toLowerCase().trim();
        String w2 = word2.toLowerCase().trim();

        // 直接相等
        if (w1.equals(w2)) {
            return true;
        }

        // 一个词是另一个词的子串（长度至少为2）
        if (w1.length() >= 2 && w2.length() >= 2) {
            if (w1.contains(w2) || w2.contains(w1)) {
                return true;
            }
        }

        // Levenshtein距离相似度超过85%
        if (calculateLevenshteinSimilarity(w1, w2) > 0.85) {
            return true;
        }

        // 考虑常见的变体形式
        if (isVariant(w1, w2)) {
            return true;
        }

        return false;
    }

    /**
     * 检查两个词是否是变体形式（不区分大小写和常见后缀）
     */
    private boolean isVariant(String word1, String word2) {
        String w1 = word1.toLowerCase().trim();
        String w2 = word2.toLowerCase().trim();

        // 移除常见后缀后比较
        String[] commonSuffixes = {"fc", "sc", "ac", "cf", "team", "club", "united", "city", "town"};
        
        String base1 = w1;
        String base2 = w2;
        
        for (String suffix : commonSuffixes) {
            if (base1.endsWith(suffix)) {
                base1 = base1.substring(0, base1.length() - suffix.length()).trim();
            }
            if (base2.endsWith(suffix)) {
                base2 = base2.substring(0, base2.length() - suffix.length()).trim();
            }
        }

        // 移除空格和连字符后比较
        base1 = base1.replaceAll("[\\s\\-_·.]", "");
        base2 = base2.replaceAll("[\\s\\-_·.]", "");

        return base1.equals(base2) || calculateLevenshteinSimilarity(base1, base2) > 0.9;
    }

    /**
     * 规范化球队名称
     * 移除无关信息，统一格式
     */
    private String normalizeTeamName(String teamName) {
        if (teamName == null) {
            return "";
        }
        
        String normalized = teamName.trim();
        
        // 移除括号内容，如 "(女)", "(中)", "(U21)", "(主场)"
        normalized = normalized.replaceAll("\\([^)]*\\)", "");
        
        // 移除常见后缀（保留主名称）
        String[] suffixes = {"女足", "男足", "女子", "男子", "青年队", "U21", "U19", "U17", "U23",
                            "FC", "SC", "AC", "CF", "俱乐部", "队", "联队", "城", "市",
                            "竞技", "体育", "足球", "Soccer", "Football", "United", "City"};
        
        for (String suffix : suffixes) {
            // 不区分大小写移除后缀
            String regex = "(?i)" + Pattern.quote(suffix) + "$";
            normalized = normalized.replaceAll(regex, "").trim();
        }
        
        // 移除多余空格和特殊字符
        normalized = normalized.replaceAll("[\\s\\-_·.]+", " ").trim();
        
        // 统一为小写便于比较
        return normalized.toLowerCase();
    }

    /**
     * 将球队名称分割为词语列表
     */
    private List<String> splitIntoWords(String teamName) {
        List<String> words = new ArrayList<>();
        
        if (teamName == null || teamName.isEmpty()) {
            return words;
        }

        // 使用正则表达式分割：支持中文、英文、数字
        String[] parts = teamName.split("[\\s\\-_·.]+");
        
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && trimmed.length() >= 2) { // 只保留长度>=2的词
                words.add(trimmed);
            }
        }
        
        return words;
    }

    /**
     * 计算Levenshtein编辑距离相似度
     */
    private double calculateLevenshteinSimilarity(String str1, String str2) {
        if (str1 == null || str2 == null) {
            return 0;
        }

        int len1 = str1.length();
        int len2 = str2.length();

        if (len1 == 0 && len2 == 0) {
            return 1.0;
        }

        if (len1 == 0 || len2 == 0) {
            return 0;
        }

        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = str1.charAt(i - 1) == str2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }

        int maxLen = Math.max(len1, len2);
        return 1.0 - (double) dp[len1][len2] / maxLen;
    }

    /**
     * 计算Jaccard相似度（基于字符）
     */
    private double calculateJaccardSimilarity(String str1, String str2) {
        if (str1 == null || str2 == null || str1.isEmpty() || str2.isEmpty()) {
            return 0;
        }

        // 使用字符集合计算Jaccard系数
        List<Character> set1 = new ArrayList<>();
        List<Character> set2 = new ArrayList<>();

        for (char c : str1.toCharArray()) {
            if (!set1.contains(c)) {
                set1.add(c);
            }
        }

        for (char c : str2.toCharArray()) {
            if (!set2.contains(c)) {
                set2.add(c);
            }
        }

        // 计算交集
        int intersection = 0;
        for (Character c : set1) {
            if (set2.contains(c)) {
                intersection++;
            }
        }

        // 计算并集
        int union = set1.size() + set2.size() - intersection;

        if (union == 0) {
            return 0;
        }

        return (double) intersection / union;
    }
}
