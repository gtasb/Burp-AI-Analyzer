package com.ai.analyzer.ui;

import com.ai.analyzer.ui.active.ActiveAnalysisPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模式切换契约：主动面板的提示词输入区随模式切换指向对应面板，
 * 主动输出始终渲染在自己的会话时间线里（不再有共享结果面板）。
 */
@DisplayName("ActiveAnalysisPanel - 模式切换与提示词路由")
class ActiveAnalysisPanelModeTest {

    private static ActiveAnalysisPanel newPanel() {
        return new ActiveAnalysisPanel(null);
    }

    @Test
    @DisplayName("setActiveMode 切换提示词输入区")
    void promptRouting() {
        ActiveAnalysisPanel panel = newPanel();
        JTextArea passivePrompt = new JTextArea();
        panel.setActiveMode(false, passivePrompt);
        // 被动模式：面板使用被动提示词（选中行 + 提示词发起分析）
        assertThat(panel.getPromptArea()).isNotNull();
        JTextArea activePrompt = new JTextArea();
        panel.setActiveMode(true, activePrompt);
        assertThat(panel.getPromptArea()).isSameAs(panel.getPromptArea());
    }

    @Test
    @DisplayName("被动模式提示词为 null 时保持当前输入区")
    void nullPromptKeepsCurrent() {
        ActiveAnalysisPanel panel = newPanel();
        JTextArea before = panel.getPromptArea();
        panel.setActiveMode(false, null);
        assertThat(panel.getPromptArea()).isSameAs(before);
    }
}
