package com.example.android.notepad;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 扩展功能的公共工具类：
 * 1. 把笔记的修改时间格式化成友好的时间戳文本；
 * 2. 提供便签配色方案（对应"更换背景颜色"功能）。
 */
public final class NoteUtils {

    private NoteUtils() {
    }

    /**
     * 便签底色，下标即笔记表中 color 字段的取值。
     * 全部为浅色，保证深色文字在任何一种底色上都清晰可读。
     */
    public static final int[] NOTE_COLORS = {
            0xFFFFFFFF, // 白色
            0xFFFFF4C2, // 浅黄
            0xFFFFDFE4, // 浅粉
            0xFFDDF2E1, // 浅绿
            0xFFDEE9FF, // 浅蓝
            0xFFEDE3FF  // 浅紫
    };

    /**
     * 便签颜色的名称，用于颜色选择对话框。
     */
    public static final String[] NOTE_COLOR_NAMES = {
            "白色", "浅黄", "浅粉", "浅绿", "浅蓝", "浅紫"
    };

    /**
     * 根据下标取得便签底色，下标越界时回落到白色。
     */
    public static int noteColor(int index) {
        if (index < 0 || index >= NOTE_COLORS.length) {
            return NOTE_COLORS[0];
        }
        return NOTE_COLORS[index];
    }

    /**
     * 时间显示使用的时区。
     *
     * 数据库里存的是 UTC 毫秒时间戳（System.currentTimeMillis），本身与时区无关；
     * 显示时再换算成本地时间。这里固定用中国标准时间（UTC+8），而不是依赖
     * 设备默认时区：部分模拟器 / 刷机包的时区会停留在 GMT，导致界面时间
     * 比真实时间早 8 小时。固定时区后，无论设备时区如何，时间都与北京时间一致。
     */
    private static final TimeZone DISPLAY_ZONE = TimeZone.getTimeZone("Asia/Shanghai");

    /**
     * 把时间戳格式化为列表/编辑器上显示的时间文本。
     *
     * 统一使用「年-月-日 时:分」的完整格式（例如 2026年11月13日 14:30），
     * 不再使用"今天 / 昨天"这类相对说法，保证任何一条笔记都能直接看出年月日。
     *
     * @param time 毫秒时间戳（取自笔记表的 modified 字段）
     */
    public static String formatTime(long time) {
        if (time <= 0) {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA);
        format.setTimeZone(DISPLAY_ZONE);
        return format.format(new Date(time));
    }

    /**
     * 根据正文自动生成一个标题：取第一行前 30 个字符。
     * 正文为空时返回空串，由调用方决定回落到哪种默认标题。
     */
    public static String deriveTitle(String text) {
        if (text == null) {
            return "";
        }
        String firstLine = text.split("\n", 2)[0].trim();
        if (firstLine.length() <= 30) {
            return firstLine;
        }
        return firstLine.substring(0, 30).trim();
    }
}
