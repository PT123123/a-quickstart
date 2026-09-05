package com.likpia.quickstartpro.util;

import com.github.promeg.pinyinhelper.Pinyin;

import java.util.ArrayList;
import java.util.List;

/**
 * T9 搜索匹配引擎。
 *
 * 原理：
 *  1. 把应用名转成拼音（含首字母和全拼）
 *  2. 把拼音字母映射到 T9 数字键：2(ABC) 3(DEF) 4(GHI) 5(JKL) 6(MNO) 7(PQRS) 8(TUV) 9(WXYZ)
 *  3. 用户输入数字串时，对每条候选做前缀匹配（支持首字母序列和全拼序列两种）
 *
 * 例：微信 → pinyin "wei xin" → 首字母 "wx" → T9 "99" ；全拼 "weixin" → T9 "934946"
 *     用户按 9 → 匹配；按 99 → 更精确；按 934 → 也匹配
 */
public final class T9Matcher {

    private T9Matcher() {}

    /** 字母 → T9 数字映射表（index = letter - 'A'） */
    private static final char[] LETTER_TO_DIGIT = {
        // A B C   D E F   G H I   J K L   M N O   P Q R S   T U V   W X Y Z
        '2','2','2','3','3','3','4','4','4','5','5','5','6','6','6','7','7','7','7','8','8','8','9','9','9','9'
    };

    /** 把单个 ASCII 字母转成 T9 数字，非字母返回原字符 */
    public static char letterToDigit(char ch) {
        if (ch >= 'A' && ch <= 'Z') return LETTER_TO_DIGIT[ch - 'A'];
        if (ch >= 'a' && ch <= 'z') return LETTER_TO_DIGIT[ch - 'a'];
        return ch;
    }

    /** 把一段 ASCII 字母串整体转成 T9 数字串 */
    public static String lettersToDigits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
                sb.append(letterToDigit(ch));
            } else if (ch >= '0' && ch <= '9') {
                sb.append(ch); // 数字原样保留
            }
            // 其它字符（空格、标点）丢弃
        }
        return sb.toString();
    }

    /**
     * 为一条应用名生成所有可用于 T9 匹配的 "数字指纹"。
     * 对中文：pinyin 首字母序列 + 全拼序列（去空格）
     * 对英文/数字：原始字母转数字 + 原始数字
     *
     * 返回数组长度 0..N，每条都是纯数字串。
     */
    public static List<String> buildFingerprints(String label) {
        List<String> result = new ArrayList<>();
        if (label == null || label.isEmpty()) return result;

        // 1) 原始标签中的字母直接转 T9（处理纯英文应用名如 "QQ" "Chrome"）
        String rawDigits = lettersToDigits(label);
        if (!rawDigits.isEmpty()) result.add(rawDigits);

        // 2) 用 TinyPinyin 把整个标签转成拼音（带分隔符），再分别取首字母 / 全拼
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
     * 判断用户输入的 T9 数字串是否匹配某条应用名。
     * @param query 用户输入（只应包含数字 2-9，但本方法不过滤，由调用方保证）
     * @param fingerprints 该应用名预计算好的指纹列表
     * @return 任一指纹以 query 为前缀即命中
     */
    public static boolean matches(String query, List<String> fingerprints) {
        if (query == null || query.isEmpty()) return true; // 空查询 → 全部显示
        for (String fp : fingerprints) {
            if (fp.startsWith(query)) return true;
        }
        return false;
    }
}
