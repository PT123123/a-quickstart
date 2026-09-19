package com.quickstart.util;

import com.github.promeg.pinyinhelper.Pinyin;
import com.quickstart.model.AppEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * 10. 多音字匹配：beike / beiqiao 都能命中「贝壳找房」（每个汉字的所有常见读音都会生成变体）
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
    public static final int PAT_ALT_FULL_PINYIN     = 5; // 多音字备选全拼："贝壳找房" → "beikezhaofang"（'\u0001' 分隔多个）
    public static final int PAT_ALT_PINYIN_INITIALS = 6; // 与上一槽位一一对应的备选拼音首字母："bkzf"
    /** 槽位总数 */
    public static final int PAT_COUNT = 7;

    /** 备选读音在单个槽位内的分隔符（控制字符，不会出现在拼音/应用名里） */
    private static final char ALT_SEP = '\u0001';

    /** 一条应用名最多生成多少个读音变体（多音字组合过多时只取前 N 个） */
    private static final int MAX_READINGS = 8;

    /**
     * 多音字表：汉字 → 全部常见读音（小写、无音调，"v" 代表 ü），用 '|' 分隔。
     *
     * TinyPinyin 每个汉字只给一个默认读音（如「壳」默认 qiao），
     * 于是「贝壳找房」只能被 beiqiao 搜到、搜不到 beike。这里补上其余读音，
     * 生成读音变体时与默认读音一起参与匹配（重复的会自动去掉）。
     */
    private static final String HETERONYM_TABLE =
            "壳:ke|qiao,长:chang|zhang,重:zhong|chong,行:xing|hang,乐:le|yue,大:da|dai,"
          + "会:hui|kuai,传:chuan|zhuan,便:bian|pian,单:dan|shan,参:can|shen|cen,"
          + "差:cha|chai|ci,弹:dan|tan,得:de|dei,都:dou|du,更:geng|jing,角:jiao|jue,"
          + "觉:jue|jiao,卡:ka|qia,落:luo|la,露:lu|lou,率:lv|shuai,弄:nong|long,"
          + "强:qiang|jiang,圈:quan|juan,色:se|shai,什:shen|shi,似:si|shi,宿:su|xiu,"
          + "提:ti|di,系:xi|ji,省:sheng|xing,血:xue|xie,折:zhe|she,着:zhe|zhao|zhuo,"
          + "藏:cang|zang,恶:e|wu,模:mo|mu,择:ze|zhai,扎:zha|za,拾:shi|she,熟:shu|shou,"
          + "幢:zhuang|chuang,仔:zi|zai,薄:bao|bo,的:de|di,地:de|di,了:le|liao,"
          + "泊:bo|po,秘:mi|bi,塞:sai|se,屏:ping|bing,陆:lu|liu,绿:lv|lu,朴:pu|piao,"
          + "曝:pu|bao,亲:qin|qing,识:shi|zhi,说:shuo|shui,遗:yi|wei,还:hai|huan,"
          + "佛:fo|fu,度:du|duo,巷:xiang|hang,没:mei|mo,么:me|mo,校:xiao|jiao,"
          + "畜:chu|xu,埋:mai|man,削:xue|xiao,否:fou|pi,脉:mai|mo";

    /** 解析后的多音字表 */
    private static final Map<Character, String[]> HETERONYMS = parseHeteronyms();

    private static Map<Character, String[]> parseHeteronyms() {
        Map<Character, String[]> map = new HashMap<>();
        for (String item : HETERONYM_TABLE.split(",")) {
            int colon = item.indexOf(':');
            if (colon != 1) continue; // 只接受「单字:读音」形式
            map.put(item.charAt(0), item.substring(colon + 1).split("\\|"));
        }
        return map;
    }

    /** 一个读音变体：全拼（仅字母、小写）+ 拼音首字母（每个音节一个字母） */
    private static final class Reading {
        final String fullPinyin;
        final String initials;

        Reading(String fullPinyin, String initials) {
            this.fullPinyin = fullPinyin;
            this.initials = initials;
        }
    }

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
        String normalized     = patAt(pat, PAT_NORMALIZED);
        String wordInitials   = patAt(pat, PAT_WORD_INITIALS);
        String mixedInitials  = patAt(pat, PAT_MIXED_INITIALS);
        String fullPinyin     = patAt(pat, PAT_FULL_PINYIN);
        String pinyinInitials = patAt(pat, PAT_PINYIN_INITIALS);
        String altFull        = patAt(pat, PAT_ALT_FULL_PINYIN);
        String altInitials    = patAt(pat, PAT_ALT_PINYIN_INITIALS);

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

        // 5. 拼音前缀匹配（全拼/部分拼音）：weixin → 微信；多音字备选读音同样参与，beike → 贝壳找房
        if (!fullPinyin.isEmpty() && fullPinyin.startsWith(lowerQuery)) {
            return true;
        }
        if (anyAltMatches(altFull, lowerQuery, false)) {
            return true;
        }

        // 6. 混合输入匹配（首字母+全拼混合，按音节边界切分）：qidq → T9启动器；备选读音同样支持
        if (matchesMixedInput(lowerQuery, fullPinyin, pinyinInitials)) {
            return true;
        }
        if (anyAltMixedInput(altFull, altInitials, lowerQuery)) {
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
        String normalized     = patAt(pat, PAT_NORMALIZED);
        String wordInitials   = patAt(pat, PAT_WORD_INITIALS);
        String mixedInitials  = patAt(pat, PAT_MIXED_INITIALS);
        String fullPinyin     = patAt(pat, PAT_FULL_PINYIN);
        String pinyinInitials = patAt(pat, PAT_PINYIN_INITIALS);
        String altFull        = patAt(pat, PAT_ALT_FULL_PINYIN);
        String altInitials    = patAt(pat, PAT_ALT_PINYIN_INITIALS);

        // 完全匹配：整个输入等于应用的某个可搜索表示（含多音字备选读音）
        if (lowerLabel.equals(lowerQuery)
                || normalized.equals(lowerQuery)
                || wordInitials.equals(lowerQuery)
                || mixedInitials.equals(lowerQuery)
                || fullPinyin.equals(lowerQuery)
                || pinyinInitials.equals(lowerQuery)
                || anyAltMatches(altFull, lowerQuery, true)
                || anyAltMatches(altInitials, lowerQuery, true)) {
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
                || pinyinInitials.startsWith(lowerQuery)
                || anyAltMatches(altFull, lowerQuery, false)
                || anyAltMatches(altInitials, lowerQuery, false)) {
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

        // 4) 多音字备选读音的 T9 指纹：beike → 贝壳找房（23453）
        //    默认读音的指纹上面已经加过，重复的会自动跳过；标签里没有多音字就整体跳过
        if (hasHeteronym(label)) {
            for (Reading reading : buildReadings(label)) {
                String fullDigits = lettersToDigits(reading.fullPinyin);
                if (!fullDigits.isEmpty() && !result.contains(fullDigits)) result.add(fullDigits);
                String initDigits = lettersToDigits(reading.initials);
                if (!initDigits.isEmpty() && !result.contains(initDigits)) result.add(initDigits);
            }
        }
        return result;
    }

    /** label 里是否含多音字（不含就不用生成备选读音，省一轮逐字拼音转换） */
    private static boolean hasHeteronym(String label) {
        for (int i = 0; i < label.length(); i++) {
            if (HETERONYMS.containsKey(label.charAt(i))) return true;
        }
        return false;
    }

    /**
     * 为一条应用名预计算增强指纹（固定 PAT_COUNT 个槽位，见 PAT_* 常量）。
     * 由 AppLoader 在加载应用列表时调用一次，存入 AppEntry.enhancedPatterns。
     */
    public static List<String> buildEnhancedPatterns(String label) {
        List<String> patterns = new ArrayList<>(PAT_COUNT);
        for (int i = 0; i < PAT_COUNT; i++) patterns.add("");
        if (label == null || label.isEmpty()) {
            return patterns;
        }

        String fullPinyin = getFullPinyin(label);
        String pinyinInitials = getPinyinInitials(label);

        patterns.set(PAT_NORMALIZED, normalizeSpecialChars(label));
        patterns.set(PAT_WORD_INITIALS, getEnglishWordInitials(label));
        patterns.set(PAT_MIXED_INITIALS, getMixedInitials(label));
        patterns.set(PAT_FULL_PINYIN, fullPinyin);
        patterns.set(PAT_PINYIN_INITIALS, pinyinInitials);

        // 多音字备选读音：贝壳找房 → 默认 beiqiaozhaofang/bqzf，额外补 beikezhaofang/bkzf；
        // 只有全拼和首字母都与默认读音相同的变体才跳过（首字母相同但全拼不同的必须保留，
        // 如「都市」都=dou/du 首字母都是 d，dushi 仍要能搜到）
        StringBuilder altFull = new StringBuilder();
        StringBuilder altInitials = new StringBuilder();
        if (hasHeteronym(label)) {
            for (Reading reading : buildReadings(label)) {
                if (reading.fullPinyin.equals(fullPinyin) && reading.initials.equals(pinyinInitials)) continue;
                if (reading.fullPinyin.isEmpty() || reading.initials.isEmpty()) continue;
                if (altFull.length() > 0) {
                    altFull.append(ALT_SEP);
                    altInitials.append(ALT_SEP);
                }
                altFull.append(reading.fullPinyin);
                altInitials.append(reading.initials);
            }
        }
        patterns.set(PAT_ALT_FULL_PINYIN, altFull.toString());
        patterns.set(PAT_ALT_PINYIN_INITIALS, altInitials.toString());
        return patterns;
    }

    /**
     * 生成一条应用名的全部读音变体，第 0 个固定是 TinyPinyin 的默认读音。
     * 只有多音字会产生多个变体（笛卡尔积，上限 MAX_READINGS 个）。
     *
     * 单元划分：每个汉字一个单元（可有多读音），连续的非汉字（英文/数字/符号）整体一个单元。
     */
    private static List<Reading> buildReadings(String label) {
        List<Reading> out = new ArrayList<>();
        if (label == null || label.isEmpty()) return out;

        // 1) 切分单元
        List<List<String>> units = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char ch = label.charAt(i);
            if (isChinese(ch)) {
                if (ascii.length() > 0) {
                    units.add(Collections.singletonList(ascii.toString()));
                    ascii.setLength(0);
                }
                units.add(syllablesOf(ch));
            } else {
                ascii.append(ch);
            }
        }
        if (ascii.length() > 0) units.add(Collections.singletonList(ascii.toString()));

        // 2) 笛卡尔积（默认读音排在最前，超出上限只保留前 MAX_READINGS 个）
        List<List<String>> combos = new ArrayList<>();
        combos.add(new ArrayList<String>());
        for (List<String> options : units) {
            List<List<String>> next = new ArrayList<>(combos.size() * options.size());
            for (List<String> combo : combos) {
                for (String option : options) {
                    if (next.size() >= MAX_READINGS) break;
                    List<String> copy = new ArrayList<>(combo);
                    copy.add(option);
                    next.add(copy);
                }
                if (next.size() >= MAX_READINGS) break;
            }
            combos = next;
            if (combos.isEmpty()) break;
        }

        // 3) 每个组合拼成全拼 + 首字母，去掉重复读音
        Set<String> seen = new HashSet<>();
        for (List<String> combo : combos) {
            Reading reading = toReading(combo);
            if (reading.fullPinyin.isEmpty()) continue;
            if (!seen.add(reading.fullPinyin + ALT_SEP + reading.initials)) continue;
            out.add(reading);
        }
        return out;
    }

    /** 单个汉字的可选读音（首项为 TinyPinyin 的默认读音，其后是多音字表里的其余读音） */
    private static List<String> syllablesOf(char ch) {
        List<String> out = new ArrayList<>(2);
        try {
            String def = Pinyin.toPinyin(String.valueOf(ch), "");
            if (def != null && !def.isEmpty()) {
                out.add(def.toLowerCase());
            }
        } catch (Throwable ignored) {
        }
        String[] alternatives = HETERONYMS.get(ch);
        if (alternatives != null) {
            for (String alt : alternatives) {
                if (!alt.isEmpty() && !out.contains(alt)) out.add(alt);
            }
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    /** 音节序列 → 读音变体（全拼只保留字母；首字母取每个音节的首字符） */
    private static Reading toReading(List<String> syllables) {
        StringBuilder full = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        for (String syllable : syllables) {
            for (int i = 0; i < syllable.length(); i++) {
                char ch = Character.toLowerCase(syllable.charAt(i));
                if (ch >= 'a' && ch <= 'z') full.append(ch);
            }
            if (!syllable.isEmpty()) {
                char first = Character.toLowerCase(syllable.charAt(0));
                if (first >= 'a' && first <= 'z') initials.append(first);
            }
        }
        return new Reading(full.toString(), initials.toString());
    }

    /** 安全读取增强槽位（旧条目/异常数据可能缺槽位） */
    private static String patAt(List<String> pat, int index) {
        if (pat == null || index < 0 || index >= pat.size()) return "";
        String value = pat.get(index);
        return value == null ? "" : value;
    }

    /** 遍历 '\u0001' 分隔的备选读音串：exact=true 要求完全相等，否则要求前缀匹配 */
    private static boolean anyAltMatches(String joined, String query, boolean exact) {
        if (joined == null || joined.isEmpty() || query == null || query.isEmpty()) return false;
        int from = 0;
        while (from <= joined.length()) {
            int sep = joined.indexOf(ALT_SEP, from);
            String one = sep < 0 ? joined.substring(from) : joined.substring(from, sep);
            if (!one.isEmpty() && (exact ? one.equals(query) : one.startsWith(query))) return true;
            if (sep < 0) return false;
            from = sep + 1;
        }
        return false;
    }

    /** 多音字备选读音的混合输入匹配（全拼槽位与首字母槽位索引一一对应） */
    private static boolean anyAltMixedInput(String joinedFull, String joinedInitials, String query) {
        if (joinedFull == null || joinedFull.isEmpty()
                || joinedInitials == null || joinedInitials.isEmpty()) {
            return false;
        }
        String sep = String.valueOf(ALT_SEP);
        String[] fulls = joinedFull.split(sep, -1);
        String[] initials = joinedInitials.split(sep, -1);
        int n = Math.min(fulls.length, initials.length);
        for (int i = 0; i < n; i++) {
            if (matchesMixedInput(query, fulls[i], initials[i])) return true;
        }
        return false;
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
