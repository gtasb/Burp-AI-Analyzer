package com.ai.analyzer.ui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * 统一视觉样式工具类。
 *
 * <p>集中定义面板边距、按钮、输入框、滚动面板、标题边框与消息配色，
 * 让侧栏聊天、主动分析、配置等面板保持一致观感，同时尽量跟随 Burp
 * 的明/暗主题（颜色取自 UIManager，仅在关键处使用强调色）。
 */
public final class UIStyles {

    private UIStyles() {}

    /** 统一面板外边距 */
    public static final int PANEL_PAD = 10;

    /** 强调色（Burp 蓝） */
    public static final Color ACCENT = new Color(45, 108, 223);
    public static final Color ACCENT_DARK = new Color(35, 88, 195);
    public static final Color DANGER = new Color(200, 60, 60);
    public static final Color DANGER_DARK = new Color(175, 45, 45);

    /** 消息配色（跟随明/暗主题的基调基础上叠色） */
    public static final Color USER_MSG_BG = new Color(137, 183, 244);
    public static final Color AI_MSG_BG = translucent(new Color(45, 108, 223), 0x18);
    public static final Color SYSTEM_MSG_FG = new Color(128, 134, 146);

    private static boolean isDarkTheme() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return false;
        double lum = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        return lum < 128;
    }

    private static Color translucent(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** 面板统一外边距 */
    public static Border panelBorder() {
        return new EmptyBorder(PANEL_PAD, PANEL_PAD, PANEL_PAD, PANEL_PAD);
    }

    private static Font uiFont(float size, int style) {
        return new Font("Microsoft YaHei", style, Math.round(size));
    }

    /** 数字字体（等宽，支持中文回退） */
    public static Font monoFont(float size) {
        Font mono = new Font("Consolas", Font.PLAIN, Math.round(size));
        String sample = "中文 MONO 123";
        if (mono.canDisplayUpTo(sample) < 0) return mono;
        Font monoYahei = new Font("Microsoft YaHei UI", Font.PLAIN, Math.round(size));
        return monoYahei.canDisplayUpTo(sample) < 0 ? monoYahei : mono;
    }

    /** 统一 Set 一个组件的字体族（中文友好） */
    public static void setFont(JComponent c, float size, int style) {
        c.setFont(uiFont(size, style));
    }

    /** 圆角按钮统一样式 */
    public static void styleButton(JButton b) {
        b.setFocusable(false);
        b.setFont(uiFont(13f, Font.PLAIN));
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                if (!b.isEnabled()) return;
                Color cur = b.getBackground();
                if (cur != null && cur.equals(ACCENT)) {
                    b.setBackground(ACCENT_DARK);
                }
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                if (!b.isEnabled()) return;
                Color cur = b.getBackground();
                if (cur != null && cur.equals(ACCENT_DARK)) {
                    b.setBackground(ACCENT);
                }
            }
        });
    }

    /** 主操作按钮（蓝色填充） */
    public static void stylePrimary(JButton b) {
        styleButton(b);
        Color fg = UIManager.getColor("Button.foreground");
        b.setBackground(ACCENT);
        b.setForeground(fg != null && !isDarkTheme() ? fg : Color.WHITE);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT_DARK, 1),
                new EmptyBorder(4, 12, 4, 12)));
        b.setContentAreaFilled(true);
    }

    /** 危险按钮（红色填充） */
    public static void styleDanger(JButton b) {
        styleBooleanToggleColor(b, DANGER, DANGER_DARK);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DANGER_DARK, 1),
                new EmptyBorder(4, 12, 4, 12)));
    }

    /** 次要按钮（主题默认色） */
    public static void styleSecondary(JButton b) {
        styleButton(b);
    }

    /** 切换按钮（选中时用强调色文本） */
    public static void styleToggle(JToggleButton b) {
        b.setFocusable(false);
        b.setFont(uiFont(12f, Font.PLAIN));
    }

    private static void styleBooleanToggleColor(JComponent b, Color c, Color dark) {
        b.setFocusable(false);
        b.setFont(uiFont(13f, Font.PLAIN));
        b.setBackground(c);
        b.setForeground(Color.WHITE);
    }

    /** 统一带标题的边框（底色随主题） */
    public static TitledBorder titledBorder(String title) {
        TitledBorder tb = BorderFactory.createTitledBorder(title);
        tb.setBorder(BorderFactory.createLineBorder(UIManager.getColor("TextField.borderColor") != null
                ? UIManager.getColor("TextField.borderColor")
                : new Color(180, 184, 190), 1));
        tb.setTitleFont(uiFont(12f, Font.BOLD));
        Color titleFg = UIManager.getColor("Label.foreground");
        tb.setTitleColor(titleFg != null ? titleFg : new Color(90, 96, 105));
        return tb;
    }

    /** 统一滚动面板边框 */
    public static Border scrollBorder() {
        Color c = UIManager.getColor("TextField.borderColor") != null
                ? UIManager.getColor("TextField.borderColor")
                : new Color(180, 184, 190);
        return BorderFactory.createLineBorder(c, 1);
    }

    /** 消息文本颜色（跟随主题） */
    public static Color textColor() {
        Color c = UIManager.getColor("TextArea.foreground");
        return c != null ? c : Color.BLACK;
    }

    /** 占位文本颜色 */
    public static Color hintColor() {
        return new Color(160, 165, 172);
    }
}