package com.ai.analyzer.ui.active;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户消息长文本折叠（CC 风格）：超过阈值显示折叠态 + 「展开 N 行」，
 * 点击展开全文，再点收起。
 */
@DisplayName("ChatTimeline - 用户消息折叠")
class ChatTimelineUserCollapseTest {

    @Test
    @DisplayName("long_user_message_collapses_and_expands")
    void longMessageCollapses() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline timeline = new ChatTimeline();
            StringBuilder longText = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                longText.append("这是第 ").append(i).append(" 行的测试文本内容，用来撑过折叠阈值。");
            }
            timeline.addUser(longText.toString(), "GET https://a.example");

            // 找到刚加的 Card → bodyHolder → 折叠正文（含「展开」按钮）
            Component card = timeline.content().getComponent(timeline.content().getComponentCount() - 1);
            JTextArea area = findTextArea(card);
            assertThat(area).isNotNull();
            // 折叠态：显示的内容远短于原文
            assertThat(area.getText().length()).isLessThan(longText.length());
            JButton toggle = findButton(card);
            assertThat(toggle).isNotNull();
            // 82d6add 起展开/收起用箭头符号（▸ 向右=折叠态，▾ 向下=展开态）
            assertThat(toggle.getText()).startsWith("▸ 展开");

            // 点击展开：内容变全文，按钮变收起
            toggle.doClick();
            JButton collapseBtn = findButton(card);
            assertThat(collapseBtn.getText()).isEqualTo("▾ 收起");
            JTextArea fullArea = findTextArea(card);
            assertThat(fullArea.getText()).contains("这是第 39 行");
        });
    }

    @Test
    @DisplayName("short_user_message_shows_fully_without_toggle")
    void shortMessageShowsFully() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatTimeline timeline = new ChatTimeline();
            timeline.addUser("短消息", "GET https://a.example");
            Component card = timeline.content().getComponent(timeline.content().getComponentCount() - 1);
            assertThat(findTextArea(card).getText()).isEqualTo("短消息");
            assertThat(findButton(card)).isNull();
        });
    }

    private static JTextArea findTextArea(Component c) {
        if (c instanceof JTextArea ta) return ta;
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTextArea r = findTextArea(child);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static JButton findButton(Component c) {
        if (c instanceof JButton b) return b;
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton r = findButton(child);
                if (r != null) return r;
            }
        }
        return null;
    }
}
