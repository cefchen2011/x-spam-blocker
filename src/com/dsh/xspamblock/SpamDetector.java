package com.dsh.xspamblock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the spam shape the user cares about: a leading "@handle," (or a
 * Chinese comma) followed by a body that carries a sexual come-on.
 */
final class SpamDetector {

    /** "@someone," or "@someone，" - the handle is captured. */
    private static final Pattern HANDLE_COMMA =
            Pattern.compile("@([A-Za-z0-9_]{2,15})\\s*[,，、]");

    /** Sexual / paid-service come-ons that mark the reply as spam. */
    private static final String[] INNUENDO = {
            // Chinese
            "约炮", "约啪", "打炮", "做爱", "爱爱", "骚", "骚货", "骚逼", "母狗", "舔", "口活", "口交",
            "鸡巴", "肉棒", "小穴", "奶子", "巨乳", "爆乳", "胸大", "腿控", "足控", "丝袜", "情趣",
            "裸聊", "裸照", "涩涩", "开车", "老司机", "福利", "上门", "包养", "兼职", "楼凤", "快餐",
            "全套", "空降", "服务", "加微", "私聊", "约起", "调教", "主人", "听话", "乖", "湿了", "硬了",
            "呻吟", "高潮", "射了", "射爆", "撸", "手冲", "飞机杯", "陪玩", "陪睡", "包夜", "一夜情",
            "妹妹", "小姐姐", "嫩模", "少妇", "御姐", "萝莉", "性感", "身材", "照片", "视频", "资源",
            "想操", "想被", "想舔", "想射", "欠操", "贱", "淫", "黄", "炮友", "固炮", "肉便器",
            // English
            "nude", "nudes", "nsfw", "sexy", "horny", "xxx", "porn", "escort", "hookup", "onlyfans",
            "of link", "snapchat", "sexting", "dick", "pussy", "cum", "blowjob", "anal", "cumshot",
    };

    private SpamDetector() {}

    /**
     * Loose shape check: any "@handle," in the text. Used to pick the replies worth
     * sending to the model, which then decides for real.
     */
    static String candidateHandle(String text) {
        if (text == null || text.length() < 4) return null;
        Matcher m = HANDLE_COMMA.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Returns the handle to mute when the post is spam, otherwise null. */
    static String detect(String text) {
        if (text == null || text.length() < 4) return null;
        Matcher m = HANDLE_COMMA.matcher(text);
        if (!m.find()) return null;
        String handle = m.group(1);
        if (handle == null || handle.isEmpty()) return null;
        if (!hasInnuendo(text)) return null;
        return handle;
    }

    private static boolean hasInnuendo(String text) {
        String lower = text.toLowerCase();
        for (String k : INNUENDO) {
            if (lower.contains(k)) return true;
        }
        return false;
    }
}
