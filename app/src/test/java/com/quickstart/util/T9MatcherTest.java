package com.quickstart.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quickstart.model.AppEntry;

import org.junit.Test;

import java.util.List;

/**
 * T9Matcher 增强匹配的 JVM 单元测试。
 *
 * 覆盖计划中的功能用例：
 * T9 数字、首字母、全拼、部分拼音、混合输入（qidq）、特殊字符（1dm0）、
 * 英文空格分词（bh）、中英混合（bxib）、CamelCase（ib）、中间匹配（ditu）。
 */
public class T9MatcherTest {

    private static AppEntry entry(String label) {
        List<String> fps = T9Matcher.buildFingerprints(label);
        AppEntry e = new AppEntry(label, "pkg.test", "act", fps);
        e.enhancedPatterns = T9Matcher.buildEnhancedPatterns(label);
        return e;
    }

    // ========== 边界 ==========

    @Test
    public void emptyQueryMatchesAll() {
        assertTrue(T9Matcher.matchesEnhanced("", entry("微信")));
        assertTrue(T9Matcher.matchesEnhanced(null, entry("微信")));
    }

    @Test
    public void nullEntryNoMatch() {
        assertFalse(T9Matcher.matchesEnhanced("wx", null));
    }

    // ========== 基础模式 ==========

    @Test
    public void t9Digits() {
        // 全拼 weixin → T9 数字 934946，"934" 为其前缀
        assertTrue(T9Matcher.matchesEnhanced("934", entry("微信")));
    }

    @Test
    public void firstLetters() {
        assertTrue(T9Matcher.matchesEnhanced("wx", entry("微信")));
    }

    @Test
    public void fullPinyin() {
        assertTrue(T9Matcher.matchesEnhanced("weixin", entry("微信")));
    }

    @Test
    public void partialPinyin() {
        // weixin 的前缀
        assertTrue(T9Matcher.matchesEnhanced("weixi", entry("微信")));
    }

    @Test
    public void rawText() {
        assertTrue(T9Matcher.matchesEnhanced("chrome", entry("Chrome")));
        assertTrue(T9Matcher.matchesEnhanced("Chrome", entry("Chrome")));
    }

    // ========== 增强模式 ==========

    @Test
    public void mixedInput_fullAndInitials() {
        // qi + d + q → T9启动器（音节 t|qi|dong|qi）
        assertTrue(T9Matcher.matchesEnhanced("qidq", entry("T9启动器")));
    }

    @Test
    public void mixedInput_initialsOnly() {
        // 拼音首字母 tqdq
        assertTrue(T9Matcher.matchesEnhanced("tqdq", entry("T9启动器")));
    }

    @Test
    public void specialCharMappedToZero() {
        assertTrue(T9Matcher.matchesEnhanced("1dm0", entry("1DM+")));
        assertTrue(T9Matcher.matchesEnhanced("1dm", entry("1DM+")));
    }

    @Test
    public void englishWordInitials() {
        assertTrue(T9Matcher.matchesEnhanced("bh", entry("Bambu Handy")));
        assertTrue(T9Matcher.matchesEnhanced("bambu", entry("Bambu Handy")));
    }

    @Test
    public void chineseEnglishMixed() {
        // 冰箱IceBox → 拼音首字母 bx + IceBox 首字母 ib
        assertTrue(T9Matcher.matchesEnhanced("bxib", entry("冰箱IceBox")));
    }

    @Test
    public void camelCaseInitials() {
        assertTrue(T9Matcher.matchesEnhanced("ib", entry("IceBox")));
    }

    @Test
    public void middleMatch_skipLeadingSyllables() {
        // 高德地图 gao|de|di|tu，跳过 gao、de 从 di 开始
        assertTrue(T9Matcher.matchesEnhanced("ditu", entry("高德地图")));
    }

    @Test
    public void middleMatch_skipOneSyllable() {
        // 跳过 gao，从 "dedi" 全拼开始
        assertTrue(T9Matcher.matchesEnhanced("deditu", entry("高德地图")));
    }

    // ========== 反例 ==========

    @Test
    public void noFalsePositive() {
        // 9999 无法命中微信的任何 T9 指纹（934946 / 99）
        assertFalse(T9Matcher.matchesEnhanced("zzzz", entry("微信")));
        assertFalse(T9Matcher.matchesEnhanced("bx", entry("Bambu Handy")));
    }
}
