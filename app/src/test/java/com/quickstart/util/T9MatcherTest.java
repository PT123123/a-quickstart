package com.quickstart.util;

import static org.junit.Assert.assertEquals;
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

    // ========== 多音字 ==========

    @Test
    public void heteronym_defaultReadingStillMatches() {
        // TinyPinyin 默认把「壳」读成 qiao，默认读音必须继续能搜到
        assertTrue(T9Matcher.matchesEnhanced("beiqiao", entry("贝壳找房")));
        assertTrue(T9Matcher.matchesEnhanced("bqzf", entry("贝壳找房")));
    }

    @Test
    public void heteronym_alternateReadingMatches() {
        // 用户按「ke」的读音搜索：全拼 / 部分拼音 / 首字母 / 数字键（23453 = beike）
        assertTrue(T9Matcher.matchesEnhanced("beike", entry("贝壳找房")));
        assertTrue(T9Matcher.matchesEnhanced("beik", entry("贝壳找房")));
        assertTrue(T9Matcher.matchesEnhanced("bkzf", entry("贝壳找房")));
        assertTrue(T9Matcher.matchesEnhanced("23453", entry("贝壳找房")));
    }

    @Test
    public void heteronym_mixedInputOnAlternateReading() {
        // 备选读音同样支持混合输入（首字母 + 全拼）：b + ke
        assertTrue(T9Matcher.matchesEnhanced("bke", entry("贝壳找房")));
    }

    @Test
    public void heteronym_commonAppNames() {
        assertTrue(T9Matcher.matchesEnhanced("chongqing", entry("重庆")));   // 重 默认 zhong
        assertTrue(T9Matcher.matchesEnhanced("zhongqing", entry("重庆")));
        assertTrue(T9Matcher.matchesEnhanced("yinhang", entry("银行")));     // 行 默认 xing
        assertTrue(T9Matcher.matchesEnhanced("yinyue", entry("音乐")));      // 乐 默认 le
        assertTrue(T9Matcher.matchesEnhanced("pianyi", entry("便宜")));      // 便 默认 bian
        assertTrue(T9Matcher.matchesEnhanced("shoucang", entry("收藏")));    // 藏 默认 zang
        assertTrue(T9Matcher.matchesEnhanced("dushi", entry("都市")));       // 都 默认 dou
    }

    @Test
    public void heteronym_matchStrength() {
        // 备选读音是「完全匹配」，输入其前缀算「开头匹配」
        assertEquals(3, T9Matcher.matchStrength("beike", entry("贝壳")));
        assertEquals(2, T9Matcher.matchStrength("beik", entry("贝壳")));
        assertEquals(3, T9Matcher.matchStrength("beiqiao", entry("贝壳")));
    }

    @Test
    public void heteronym_noFalsePositive() {
        assertFalse(T9Matcher.matchesEnhanced("zzzz", entry("贝壳找房")));
        assertFalse(T9Matcher.matchesEnhanced("yinyue", entry("快乐")));
    }

    // ========== 反例 ==========

    @Test
    public void noFalsePositive() {
        // 9999 无法命中微信的任何 T9 指纹（934946 / 99）
        assertFalse(T9Matcher.matchesEnhanced("zzzz", entry("微信")));
        assertFalse(T9Matcher.matchesEnhanced("bx", entry("Bambu Handy")));
    }
}
