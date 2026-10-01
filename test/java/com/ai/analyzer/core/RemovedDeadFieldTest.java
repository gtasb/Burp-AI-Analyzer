package com.ai.analyzer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已删除的死字段不得复活。
 *
 * <p>{@code enableUnrestrictedCliTool} 曾是纯装饰：它被存进 PluginSettings/AgentConfig、
 * 在两个 ApiClient 之间来回传递、还驱动设置面板上的勾选框，但运行时
 * （Harness 原生 execute 工具、被动扫描的 ShellExecTool）从未读取它。
 * 「允许无限制调用」勾选框因此给人「白名单能被绕过、命令能被约束」的错觉，实际两者都不成立。
 */
@DisplayName("死字段清理 - enableUnrestrictedCliTool 已移除")
class RemovedDeadFieldTest {

    private static final List<Class<?>> CONFIG_CLASSES = List.of(
            PluginSettings.class, AgentConfig.class);

    @Test
    @DisplayName("should_not_declare_field_in_settings_or_config")
    void fieldIsGone() {
        for (Class<?> clazz : CONFIG_CLASSES) {
            assertThat(clazz.getDeclaredFields())
                    .as("%s 不应再声明 enableUnrestrictedCliTool", clazz.getSimpleName())
                    .noneMatch(f -> f.getName().toLowerCase().contains("unrestrictedclitool"));
        }
    }

    @Test
    @DisplayName("should_not_expose_getters_or_setters")
    void accessorsAreGone() throws Exception {
        for (Class<?> clazz : CONFIG_CLASSES) {
            assertThat(clazz.getDeclaredMethods())
                    .as("%s 不应再有 enableUnrestrictedCliTool 访问器", clazz.getSimpleName())
                    .noneMatch(m -> m.getName().toLowerCase().contains("unrestrictedclitool"));
        }
    }

    @Test
    @DisplayName("serialized_settings_should_ignore_legacy_field")
    void legacySerializedFileStillLoads() throws Exception {
        // 旧 .dat 里含有已被删除的字段；Java 反序列化会忽略流中多余的字段，
        // 插件必须仍能正常加载，而不是抛 InvalidClassException
        PluginSettings settings = new PluginSettings();
        settings.setEnableCliTool(true);
        settings.setEnableFileSystemSandbox(true);

        java.io.File tmp = java.io.File.createTempFile("legacy-settings", ".dat");
        tmp.deleteOnExit();
        try (java.io.ObjectOutputStream oos =
                     new java.io.ObjectOutputStream(new java.io.FileOutputStream(tmp))) {
            oos.writeObject(settings);
        }

        PluginSettings loaded = PluginSettings.loadCompat(tmp);
        assertThat(loaded.isEnableCliTool()).isTrue();
        assertThat(loaded.isEnableFileSystemSandbox()).isTrue();
    }

    @Test
    @DisplayName("cli_tool_toggle_should_still_work_without_removed_flag")
    void cliToolToggleStillWorks() {
        AgentApiClient client = new AgentApiClient("http://localhost", "k");
        assertThat(client.getConfig().isEnableCliTool()).isFalse();
        client.setEnableCliTool(true);
        assertThat(client.getConfig().isEnableCliTool()).isTrue();
    }
}
