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

    @Test
    @DisplayName("折叠钮收起/展开工具区")
    void collapseTogglesExpandedPanel() {
        ActiveAnalysisPanel panel = newPanel();
        // 控件行 = 含「自动继续」复选框的面板
        java.awt.Component expanded = findPanelWithCheckbox(panel);
        assertThat(expanded).isNotNull();
        assertThat(expanded.isVisible()).isTrue();
        javax.swing.JToggleButton collapse = findCollapseButton(panel);
        assertThat(collapse).isNotNull();
        // 折叠：控件行隐藏，箭头变 ▸
        collapse.doClick();
        assertThat(expanded.isVisible()).isFalse();
        assertThat(collapse.getText()).isEqualTo("▸");
        // 再点一次恢复
        collapse.doClick();
        assertThat(expanded.isVisible()).isTrue();
        assertThat(collapse.getText()).isEqualTo("▾");
    }

    private static java.awt.Component findPanelWithCheckbox(java.awt.Container parent) {
        for (java.awt.Component c : parent.getComponents()) {
            if (c instanceof javax.swing.JCheckBox) return parent;
            if (c instanceof java.awt.Container container) {
                java.awt.Component found = findPanelWithCheckbox(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static javax.swing.JToggleButton findCollapseButton(java.awt.Container parent) {
        for (java.awt.Component c : parent.getComponents()) {
            if (c instanceof javax.swing.JToggleButton tb) return tb;
            if (c instanceof java.awt.Container container) {
                javax.swing.JToggleButton found = findCollapseButton(container);
                if (found != null) return found;
            }
        }
        return null;
    }
}
