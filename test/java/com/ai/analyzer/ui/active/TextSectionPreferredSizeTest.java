package com.ai.analyzer.ui.active;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextSection 高度契约（线上零显示根因回归）：
 * FlatTextPaneUI 的 getPreferredSize 依赖组件当前 size——BoxLayout 首次布局
 * size=0 → pref=0 → 分配 0 高 → 死循环（Burp JVM 探针实锤：textLen=171、
 * pref height=0，setSize(475,100) 后立刻变 179）。
 * 修复后 pref 必须独立于当前 size：有内容时即使 size=0 也返回正高度。
 */
@DisplayName("ChatTimeline - TextSection 高度自算")
class TextSectionPreferredSizeTest {

    @Test
    @DisplayName("nonempty_section_has_positive_height_even_at_zero_size")
    void positiveHeightAtZeroSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline tl = new ChatTimeline();
            ChatTimeline.AssistantBlock block = tl.startAssistant("test");
            block.append("这是一段足够长的中文文本，用来验证换行高度估算。".repeat(3));
            // 手动驱动渲染（绕过 Timer）
            try {
                java.lang.reflect.Method m = ChatTimeline.AssistantBlock.class.getDeclaredMethod("render");
                m.setAccessible(true);
                m.invoke(block);
            } catch (Exception e) { throw new RuntimeException(e); }

            // 找到 TextSection
            JTextPane section = findTextPane(tl.content());
            assertThat(section).isNotNull();
            assertThat(section.getDocument().getLength()).isGreaterThan(0);
            // 关键断言：组件当前 size 为 0（未布局）时 pref 高度必须 >0
            section.setSize(0, 0);
            Dimension pref = section.getPreferredSize();
            assertThat(pref.height)
                    .as("有内容的 TextSection 在 size=0 时 pref 高度必须为正（实际 %s）", pref)
                    .isGreaterThan(0);
        });
    }

    @Test
    @DisplayName("empty_section_has_zero_height")
    void emptySectionZeroHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline tl = new ChatTimeline();
            ChatTimeline.AssistantBlock block = tl.startAssistant("test");
            JTextPane section = findTextPane(tl.content());
            assertThat(section).isNotNull();
            assertThat(section.getDocument().getLength()).isEqualTo(0);
            assertThat(section.getPreferredSize().height).isEqualTo(0);
        });
    }

    private static JTextPane findTextPane(Component c) {
        if (c instanceof JTextPane tp && c.getClass().getSimpleName().equals("TextSection")) return tp;
        if (c instanceof Container cont) {
            for (Component child : cont.getComponents()) {
                JTextPane r = findTextPane(child);
                if (r != null) return r;
            }
        }
        return null;
    }
}
