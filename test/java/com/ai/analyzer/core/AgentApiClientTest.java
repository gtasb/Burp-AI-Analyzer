package com.ai.analyzer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentApiClient - path normalization")
class AgentApiClientTest {

    @Test
    @DisplayName("normalizePath should return empty for null input")
    void normalizePath_null() {
        assertThat(AgentApiClient.normalizePath(null)).isNull();
    }

    @Test
    @DisplayName("normalizePath should return empty for empty input")
    void normalizePath_empty() {
        assertThat(AgentApiClient.normalizePath("")).isEmpty();
    }

    @Test
    @DisplayName("normalizePath should return unchanged for path without spaces")
    void normalizePath_noSpaces() {
        String result = AgentApiClient.normalizePath("C:\\Tools\\test");
        assertThat(result).isEqualTo("C:\\Tools\\test");
    }

    @Test
    @DisplayName("normalizePath should resolve short path for real directory with spaces")
    void normalizePath_realDirWithSpaces(@TempDir Path tempDir) throws IOException {
        // 创建一个实际存在的子目录，路径包含空格
        File spaceDir = new File(tempDir.toFile(), "my tools");
        assertThat(spaceDir.mkdirs()).isTrue();

        String result = AgentApiClient.normalizePath(spaceDir.getAbsolutePath());
        assertThat(result).isNotNull().isNotEmpty();

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            // Windows 上应转为短路径（不含空格）
            assertThat(result).doesNotContain(" ");
        } else {
            // 非 Windows 原样返回
            assertThat(result).isEqualTo(spaceDir.getAbsolutePath());
        }
    }
}
