package com.quickstart.util;

import com.github.promeg.pinyinhelper.Pinyin;
import com.quickstart.model.AppEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T9 搜索匹配引擎 - 增强版。
 *
 * 支持的匹配模式：
 * 1. T9 数字匹配（传统模式）：2→ABC, 9→WXYZ
 * 2. 首字母匹配：wx → 微信
 * 3. 全拼匹配：weixin → 微信
 * 4. 部分拼音匹配：weixi → 微信（weixin 前缀）
 * 5. 混合输入匹配：qidq → T9启动器（首字母+全拼混合，按音节边界切分）
 * 6. 英文空格分词：bh → Bambu Handy
 * 7. 中英混合分词：bxib → 冰箱IceBox
 * 8. 特殊字符映射：1dm0 → 1DM+（0 代表非字母数字字符）
 * 9. 中间匹配：ditu → 高德地图（跳过起始音节）
 *
 * 增强指纹（buildEnhancedPatterns）由 AppLoader 预计算存入 AppEntry.enhancedPatterns，
 * 缓存加载的条目（FastCache）在首次匹配时惰性补算。
 */
public final class T9Matcher {

    private T9Matcher() {}

    /** enhancedPatterns 固定槽位索引 */
    public static final int PAT_NORMALIZED      = 0; // 特殊字符规范化："1DM+" → "1dm0"
    public static final int PAT_WORD_INITIALS   = 1; // 英文单词首字母（含 CamelCase）："Bambu Handy" → "bh"
    public static final int PAT_MIXED_INITIALS  = 2; // 中英混合首字母："冰箱IceBox" → "bxib"
    public static final int PAT_FULL_PINYIN     = 3; // 全拼（小写、无分隔）："微信" → "weixin"
    public static final int PAT_PINYIN_INITIALS = 4; // 拼音首字母："微信" → "wx"

    /** 字母 → T9 数字映射表（index = letter - 'A'） */
    private static final char[] LETTER_TO_DIGIT = {
        // A B C   D E F   G H I   J K L   M N O   P Q R S   T U V   W X Y Z
        '2','2','2','3','3','3','4','4','4','5','5','5','6','6','6','7','7','7','7','8','8','8','9','9','9','9'
    };

    /**
     * 匹配缓存（纯 Java LRU，替代 android.util.LruCache，便于 JVM 单测）。
     * key = query|label，应用列表刷新后旧 key 自然失效，无需主动清理。
     */
    private static final int CACHE_MAX = 1000;
    private static final Map<String, Boolean> MATCH_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    // ==================== 公共 API ====================

    /**
     * 统一的搜索匹配入口
     * @param query 用户输入（原始字符，如 "qidq"、"bxib"、"weixi"）
     * @param entry 应用条目
     * @return true 如果匹配成功
     */
    public static boolean matchesEnhanced(String query, AppEntry entry) {
        if (query == null || query.isEmpty()) return true;
        if (entry == null || entry.label == null || entry.label.isEmpty()) return false;

        // 惰性补算增强指纹（AppLoader 已预计算的条目直接命中）
        if (entry.enhancedPatterns == null) {
            entry.enhancedPatterns = buildEnhancedPatterns(entry.label);
        }

        String cacheKey = query + "|" + entry.label;
        Boolean cached = MATCH_CACHE.get(cacheKey);
        if (cached != null) return cached;

        boolean result = doMatchEnhanced(query, entry);

        MATCH_CACHE.put(cacheKey, result);
        return result;
    }

    /**
     * 核心匹配逻辑（使用预计算的增强指纹，避免每次按键重复做拼音转换）
     */
    private static boolean doMatchEnhanced(String query, AppEntry entry) {
        String label = entry.label;
        String lowerQuery = query.toLowerCase();
        List<String> pat = entry.enhancedPatterns;
        String normalized     = pat.get(PAT_NORMALIZED);
        String wordInitials   = pat.get(PAT_WORD_INITIALS);
        String mixedInitials  = pat.get(PAT_MIXED_INITIALS);
        String fullPinyin     = pat.get(PAT_FULL_PINYIN);
        String pinyinInitials = pat.get(PAT_PINYIN_INITIALS);

        // 1. 原始文字精确匹配（包含关系）
        if (label.toLowerCase().contains(lowerQuery)) {
            return true;
        }

        // 2. 特殊字符规范化匹配：1dm0 → 1DM+
        if (normalized.startsWith(lowerQuery)) {
            return true;
        }

        // 3. 英文单词首字母匹配：bh → Bambu Handy
        if (!wordInitials.isEmpty()
                && (wordInitials.startsWith(lowerQuery) || subsequenceMatch(lowerQuery, wordInitials))) {
            return true;
        }

        // 4. 中英混合首字母匹配：bxib → 冰箱IceBox
        if (!mixedInitials.isEmpty()
                && (mixedInitials.startsWith(lowerQuery) || subsequenceMatch(lowerQuery, mixedInitials))) {
            return true;
        }

        // 5. 拼音前缀匹配（全拼/部分拼音）：weixin → 微信, weixi → 微信
        if (!fullPinyin.isEmpty() && fullPinyin.startsWith(lowerQuery)) {
            return true;
        }

        // 6. 混合输入匹配（首字母+全拼混合，按音节边界切分）：qidq → T9启动器
        if (matchesMixedInput(lowerQuery, fullPinyin, pinyinInitials)) {
            return true;
        }

        // 7. T9 数字匹配（传统模式）
        if (matches(query, entry.fingerprints)) {
            return true;
        }

        // 8. 模糊跳跃匹配（兜底，主要命中 label 里的英文字母）
        if (subsequenceMatch(lowerQuery, label.toLowerCase())) {
            return true;
        }

        return false;
    }

    /**
     * 匹配强度分级（供 T9 排序权重使用）：
     * 3=完全匹配（整个输入 = 应用名 / 规范化串 / 首字母 / 全拼 / 数字指纹，如 77→QQ、wx→微信、weixin→微信）
     * 2=开头匹配（输入是应用某个可搜索表示的前缀）
     * 1=包含 / 跳跃匹配（能匹配到，但既非完全也非开头）
     * 0=不匹配
     */
    public static int matchStrength(String query, AppEntry entry) {
        if (query == null || query.isEmpty()) return 3;
        if (entry == null || entry.label == null || entry.label.isEmpty()) return 0;
        String lowerQuery = query.toLowerCase();
        List<String> pat = entry.enhancedPatterns;
        if (pat == null) pat = buildEnhancedPatterns(entry.label);

        String lowerLabel   = entry.label.toLowerCase();
        String normalized     = pat.get(PAT_NORMALIZED);
        String wordInitials   = pat.get(PAT_WORD_INITIALS);
        String mixedInitials  = pat.get(PAT_MIXED_INITIALS);
        String fullPinyin     = pat.get(PAT_FULL_PINYIN);
        String pinyinInitials = pat.get(PAT_PINYIN_INITIALS);

        // 完全匹配：整个输入等于应用的某个可搜索表示
        if (lowerLabel.equals(lowerQuery)
                || normalized.equals(lowerQuery)
                || wordInitials.equals(lowerQuery)
                || mixedInitials.equals(lowerQuery)
                || fullPinyin.equals(lowerQuery)
                || pinyinInitials.equals(lowerQuery)) {
            return 3;
        }
        if (entry.fingerprints != null) {
            for (String fp : entry.fingerprints) {
                if (fp.equals(lowerQuery)) return 3;
            }
        }

        // 开头匹配：输入是某个可搜索表示的前缀
        if (lowerLabel.startsWith(lowerQuery)
                || normalized.startsWith(lowerQuery)
                || wordInitials.startsWith(lowerQuery)
                || mixedInitials.startsWith(lowerQuery)
                || fullPinyin.startsWith(lowerQuery)
                || pinyinInitials.startsWith(lowerQuery)) {
            return 2;
        }
        if (entry.fingerprints != null) {
            for (String fp : entry.fingerprints) {
                if (fp.startsWith(lowerQuery)) return 2;
            }
        }

        // 能匹配但非完全/开头 → 包含 / 跳跃匹配
        return matchesEnhanced(query, entry) ? 1 : 0;
    }

    /**
     * 兼容旧 API：判断数字串是否匹配指纹列表
     */
    public static boolean matches(String query, List<String> fingerprints) {
        if (query == null || query.isEmpty()) return true;
        if (fingerprints == null) return false;
        for (String fp : fingerprints) {
            if (fp.startsWith(query)) return true;          // 精确前缀
            if (fuzzyMatches(query, fp)) return true;        // 跳跃匹配
        }
        return false;
    }

    /**
     * 为一条应用名生成所有可用于 T9 匹配的 "数字指纹"。
     * 对中文：pinyin 首字母序列 + 全拼序列（去空格）
     * 对英文/数字：原始字母转数字 + 原始数字
     */
    public static List<String> buildFingerprints(String label) {
        List<String> result = new ArrayList<>();
        if (label == null || label.isEmpty()) return result;

        // 1) 原始标签中的字母直接转 T9（处理纯英文应用名如 "QQ" "Chrome"）
        String rawDigits = lettersToDigits(label);
        if (!rawDigits.isEmpty()) result.add(rawDigits);

        // 2) 纯英文标签（只含 ASCII 字母、数字、空格）跳过拼音转换
        if (isPureEnglish(label)) {
            return result;
        }

        // 3) 用 TinyPinyin 把整个标签转成拼音（带分隔符），再分别取首字母 / 全拼
        try {
            String pinyin = Pinyin.toPinyin(label, " "); // "wei xin"
            if (pinyin != null && !pinyin.isEmpty()) {
                String compact = pinyin.replaceAll("[^a-zA-Z]", "").toLowerCase(); // "weixin"
                if (!compact.isEmpty()) {
                    String compactDigits = lettersToDigits(compact);
                    if (!compactDigits.isEmpty() && !result.contains(compactDigits)) {
                        result.add(compactDigits);
                    }
                }

                // 每个汉字拼音的首字母 → T9
                StringBuilder initials = new StringBuilder();
                for (String token : pinyin.split("\\s+")) {
                    if (!token.isEmpty()) {
                        char first = token.charAt(0);
                        if ((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z')) {
                            initials.append(first);
                        }
                    }
                }
                if (initials.length() > 0) {
                    String initDigits = lettersToDigits(initials.toString());
                    if (!initDigits.isEmpty() && !result.contains(initDigits)) {
                        result.add(initDigits);
                    }
                }
            }
        } catch (Throwable ignored) {
            // TinyPinyin 极端情况下可能异常，忽略即可
        }
        return result;
    }

    /**
     * 为一条应用名预计算增强指纹（固定 5 个槽位，见 PAT_* 常量）。
     * 由 AppLoader 在加载应用列表时调用一次，存入 AppEntry.enhancedPatterns。
     */
    public static List<String> buildEnhancedPatterns(String label) {
        List<String> patterns = new ArrayList<>(5);
        if (label == null || label.isEmpty()) {
            for (int i = 0; i < 5; i++) patterns.add("");
            return patterns;
        }
        patterns.add(normalizeSpecialChars(label));      // PAT_NORMALIZED
        patterns.add(getEnglishWordInitials(label));     // PAT_WORD_INITIALS
        patterns.add(getMixedInitials(label));           // PAT_MIXED_INITIALS
        patterns.add(getFullPinyin(label));              // PAT_FULL_PINYIN
        patterns.add(getPinyinInitials(label));          // PAT_PINYIN_INITIALS
        return patterns;
    }

    // ==================== 混合输入匹配（音节边界切分） ====================

    /**
     * 混合输入匹配：qidq → T9启动器
     * 支持首字母和全拼的任意组合。
     */
    private static boolean matchesMixedInput(String query, String fullPinyin, String pinyinInitials) {
        if (fullPinyin.isEmpty() || pinyinInitials.isEmpty()) return false;

        // query 是全拼/首字母串的前缀
        if (fullPinyin.startsWith(query) || pinyinInitials.startsWith(query)) return true;

        // 混合模式：query 能否按音节边界切分（每段是一个音节的前缀，可跳过音节）
        return canSegmentToPinyin(query, fullPinyin, pinyinInitials);
    }

    /**
     * 检查 query 能否按拼音音节边界切分。
     * 例如：qidq = qi + d + q（对应 "T9启动器" 的音节 t | qi | dong | qi）
     *
     * 两步 DP：
     * 1. 由 全拼 + 拼音首字母串 重建音节边界（首字母即每个音节的首字符约束）；
     * 2. query 切成若干段，每段必须是某个音节的前缀（单字符段 = 首字母），
     *    允许跳过起始音节（中间匹配），允许段只覆盖音节前缀（部分拼音）。
     *
     * 边界重建失败（如含长英文词导致对不齐）时退化为子序列匹配。
     */
    private static boolean canSegmentToPinyin(String query, String fullPinyin, String initials) {
        int qlen = query.length();
        int n = initials.length();
        int plen = fullPinyin.length();
        if (qlen < 2 || n == 0 || plen < n) return false;

        // ---- 步骤1：重建音节边界 ----
        // starts[i] = 第 i 个音节所有可能的起始位置（该位置字符必须等于 initials[i]）
        @SuppressWarnings("unchecked")
        List<Integer>[] starts = new List[n];
        for (int i = 0; i < n; i++) starts[i] = new ArrayList<>();
        if (fullPinyin.charAt(0) != initials.charAt(0)) {
            return subsequenceMatch(query, fullPinyin); // 对不齐，退化
        }
        starts[0].add(0);
        for (int i = 0; i < n - 1; i++) {
            if (starts[i].isEmpty()) break;
            char nextInitial = initials.charAt(i + 1);
            for (int pos : starts[i]) {
                for (int end = pos + 1; end < plen; end++) {
                    if (fullPinyin.charAt(end) == nextInitial
                            && !starts[i + 1].contains(end)) {
                        starts[i + 1].add(end);
                    }
                }
            }
        }
        if (starts[n - 1].isEmpty()) {
            return subsequenceMatch(query, fullPinyin); // 对不齐，退化
        }

        // ---- 步骤2：query 切分 DP ----
        int[][] memo = new int[qlen + 1][n + 1];
        for (int[] row : memo) java.util.Arrays.fill(row, -1);
        return segmentDp(query, fullPinyin, starts, 0, 0, memo);
    }

    /**
     * 切分 DP：query[qi..] 能否由 starts[syl..] 的音节前缀段拼出（可跳音节）。
     */
    private static boolean segmentDp(String query, String pinyin, List<Integer>[] starts,
                                     int qi, int syl, int[][] memo) {
        if (qi == query.length()) return true;
        if (syl >= starts.length) return false;
        if (memo[qi][syl] != -1) return memo[qi][syl] == 1;

        boolean ok = segmentDp(query, pinyin, starts, qi, syl + 1, memo); // 跳过该音节

        if (!ok) {
            int plen = pinyin.length();
            int maxEnd = (syl == starts.length - 1) ? plen : maxOf(starts[syl + 1]);
            for (int pos : starts[syl]) {
                int maxSeg = Math.min(query.length() - qi, maxEnd - pos);
                for (int len = 1; len <= maxSeg; len++) {
                    // 段必须是音节前缀；不匹配时更长的段也不可能匹配
                    if (!pinyin.startsWith(query.substring(qi, qi + len), pos)) break;
                    if (segmentDp(query, pinyin, starts, qi + len, syl + 1, memo)) {
                        ok = true;
                        break;
                    }
                }
                if (ok) break;
            }
        }

        memo[qi][syl] = ok ? 1 : 0;
        return ok;
    }

    private static int maxOf(List<Integer> list) {
        int m = Integer.MIN_VALUE;
        for (int v : list) if (v > m) m = v;
        return m;
    }

    // ==================== 辅助函数 ====================

    /**
     * 把单个 ASCII 字母转成 T9 数字，非字母返回原字符
     */
    public static char letterToDigit(char ch) {
        if (ch >= 'A' && ch <= 'Z') return LETTER_TO_DIGIT[ch - 'A'];
        if (ch >= 'a' && ch <= 'z') return LETTER_TO_DIGIT[ch - 'a'];
        return ch;
    }

    /**
     * 把一段 ASCII 字母串整体转成 T9 数字串
     */
    public static String lettersToDigits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
                sb.append(letterToDigit(ch));
            } else if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 跳跃匹配：query 中的每个字符按序在 target 中出现（中间可跳过其他字符）
     */
    private static boolean fuzzyMatches(String query, String target) {
        if (query.length() > target.length()) return false;
        int qi = 0;
        for (int fi = 0; fi < target.length() && qi < query.length(); fi++) {
            if (target.charAt(fi) == query.charAt(qi)) {
                qi++;
            }
        }
        return qi == query.length();
    }

    /**
     * 子序列匹配：query 是 target 的子序列（按顺序，但不要求连续）
     */
    private static boolean subsequenceMatch(String query, String target) {
        if (query.length() > target.length()) return false;
        int qi = 0;
        for (int ti = 0; ti < target.length() && qi < query.length(); ti++) {
            if (target.charAt(ti) == query.charAt(qi)) {
                qi++;
            }
        }
        return qi == query.length();
    }

    /**
     * 判断标签是否为纯英文（只含 ASCII 字母、数字、空格和常见符号）
     */
    private static boolean isPureEnglish(String label) {
        for (int i = 0; i < label.length(); i++) {
            if (label.charAt(i) > 127) return false;
        }
        return true;
    }

    /**
     * 获取英文单词首字母（支持 CamelCase）
     * "Bambu Handy" → "bh"
     * "Chrome" → "c"
     * "IceBox" → "ib"
     */
    private static String getEnglishWordInitials(String label) {
        StringBuilder result = new StringBuilder();

        // 按空格/下划线/连字符分割
        String[] words = label.split("[\\s_-]+");

        for (String word : words) {
            if (word.isEmpty()) continue;

            // 识别 CamelCase: IceBox → Ice + Box
            if (word.length() > 1 && hasUpperCaseInMiddle(word)) {
                for (String cw : splitCamelCase(word)) {
                    if (!cw.isEmpty()) {
                        char first = cw.charAt(0);
                        if (Character.isLetter(first)) {
                            result.append(Character.toLowerCase(first));
                        }
                    }
                }
            } else {
                // 普通单词
                char first = word.charAt(0);
                if (Character.isLetter(first)) {
                    result.append(Character.toLowerCase(first));
                }
            }
        }

        return result.toString();
    }

    /**
     * 中英混合首字母
     * "冰箱IceBox" → "bxib"
     */
    private static String getMixedInitials(String label) {
        StringBuilder result = new StringBuilder();
        StringBuilder currentEnglish = new StringBuilder();

        for (int i = 0; i < label.length(); i++) {
            char ch = label.charAt(i);
            if (isChinese(ch)) {
                // 处理累积的英文字符
                if (currentEnglish.length() > 0) {
                    result.append(getEnglishWordInitials(currentEnglish.toString()));
                    currentEnglish.setLength(0);
                }
                // 追加中文拼音首字母
                try {
                    String pinyin = Pinyin.toPinyin(String.valueOf(ch), "");
                    if (!pinyin.isEmpty()) {
                        result.append(Character.toLowerCase(pinyin.charAt(0)));
                    }
                } catch (Throwable ignored) {
                }
            } else if (Character.isLetterOrDigit(ch) || ch == '+' || ch == '-' || ch == '_') {
                currentEnglish.append(ch);
            }
        }

        // 处理末尾英文
        if (currentEnglish.length() > 0) {
            result.append(getEnglishWordInitials(currentEnglish.toString()));
        }

        return result.toString();
    }

    /**
     * 获取完整拼音（小写、无分隔符；纯英文返回空串）
     */
    private static String getFullPinyin(String label) {
        if (isPureEnglish(label)) return "";
        try {
            String pinyin = Pinyin.toPinyin(label, "");
            return pinyin.replaceAll("[^a-zA-Z]", "").toLowerCase();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 获取拼音首字母（纯英文返回空串）
     */
    private static String getPinyinInitials(String label) {
        if (isPureEnglish(label)) return "";
        try {
            String pinyin = Pinyin.toPinyin(label, " ");
            StringBuilder initials = new StringBuilder();
            for (String token : pinyin.split("\\s+")) {
                if (!token.isEmpty()) {
                    char first = token.charAt(0);
                    if (Character.isLetter(first)) {
                        initials.append(Character.toLowerCase(first));
                    }
                }
            }
            return initials.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 规范化特殊字符：数字不变，字母（含中文）保留并转小写，其余符号映射为 0
     * "1DM+" → "1dm0"，"Bambu Handy" → "bambu0handy"
     */
    private static String normalizeSpecialChars(String label) {
        StringBuilder result = new StringBuilder();
        for (char ch : label.toCharArray()) {
            if (Character.isDigit(ch)) {
                result.append(ch);
            } else if (Character.isLetter(ch)) {
                result.append(Character.toLowerCase(ch));
            } else {
                result.append('0');
            }
        }
        return result.toString();
    }

    /**
     * 检查 CamelCase（中间有大写字母）
     */
    private static boolean hasUpperCaseInMiddle(String word) {
        if (word.length() <= 1) return false;
        for (int i = 1; i < word.length(); i++) {
            if (Character.isUpperCase(word.charAt(i))) return true;
        }
        return false;
    }

    /**
     * 拆分 CamelCase
     * "IceBox" → ["Ice", "Box"]
     */
    private static String[] splitCamelCase(String word) {
        return word.split("(?=[A-Z])");
    }

    /**
     * 判断是否为中文字符
     */
    private static boolean isChinese(char ch) {
        return ch > 127;
    }

    /**
     * 清除匹配缓存
     */
    public static void clearCache() {
        MATCH_CACHE.clear();
    }
}
