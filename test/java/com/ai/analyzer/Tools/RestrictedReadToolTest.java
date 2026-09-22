package com.ai.analyzer.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RestrictedReadTool - 受限 read_file")
class RestrictedReadToolTest {

    private Path workplace;
    private Path cacheDir;
    private RestrictedReadTool tool;

    @BeforeEach
    void setUp() throws Exception {
        workplace = Files.createTempDirectory("restricted-read-workplace");
        cacheDir = Files.createDirectories(workplace.resolve(".cache"));
        tool = new RestrictedReadTool(workplace.toString());
    }

    @Test
    @DisplayName("读取 .cache 内文件返回内容")
    void reads_file_inside_cache() throws Exception {
        Path f = cacheDir.resolve("123-response.txt");
        Files.writeString(f, "HTTP/1.1 200 OK\n\n<body>", StandardCharsets.UTF_8);

        String out = tool.readFile(f.toString(), null, null);

        assertThat(out).contains("HTTP/1.1 200 OK");
        assertThat(out).contains("<body>");
    }

    @Test
    @DisplayName("拒绝 .cache 之外的文件")
    void rejects_outside_cache() throws Exception {
        Path secret = workplace.resolve("secret.txt");
        Files.writeString(secret, "top secret", StandardCharsets.UTF_8);

        String out = tool.readFile(secret.toString(), null, null);

        assertThat(out).contains("拒绝");
    }

    @Test
    @DisplayName("拒绝目录穿越")
    void rejects_traversal() throws Exception {
        Path secret = workplace.resolve("secret.txt");
        Files.writeString(secret, "top secret", StandardCharsets.UTF_8);

        String traversal = cacheDir.resolve("../secret.txt").toString();
        assertThat(tool.readFile(traversal, null, null)).contains("拒绝");
    }

    @Test
    @DisplayName("文件不存在时返回错误")
    void missing_file_returns_error() {
        assertThat(tool.readFile(cacheDir.resolve("nope.txt").toString(), null, null)).contains("不存在");
    }

    @Test
    @DisplayName("offset/limit 分段读取")
    void offset_limit_slices() throws Exception {
        Path f = cacheDir.resolve("abc.txt");
        Files.writeString(f, "0123456789", StandardCharsets.UTF_8);

        assertThat(tool.readFile(f.toString(), 2, 3)).startsWith("234");
        assertThat(tool.readFile(f.toString(), 8, 100)).contains("89");
    }

    @Test
    @DisplayName("offset 超出长度返回错误")
    void offset_beyond_length_returns_error() throws Exception {
        Path f = cacheDir.resolve("short.txt");
        Files.writeString(f, "hi", StandardCharsets.UTF_8);

        assertThat(tool.readFile(f.toString(), 100, null)).contains("超出");
    }
}