package com.ai.analyzer.tools;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 批量爆破工具，替换 Burp Intruder（Intruder API 不支持程序化注入 payload）。
 * AI 生成的每一组 payload 都在弹窗中展示并可编辑，用户确认后通过 Burp HTTP 引擎发送。
 */
public class BatchFuzzTool {

    private final MontoyaApi api;

    public BatchFuzzTool(MontoyaApi api) {
        this.api = api;
    }

    @Tool(name = "batch_fuzz", description =
            "对 HTTP 请求中指定的参数进行批量 payload 注入测试。弹窗展示所有 payload，用户确认后通过 Burp HTTP 引擎发送，结果汇总返回。")
    public String batchFuzz(
            @ToolParam(name = "requestContent", description = "原始 HTTP 请求内容") String requestContent,
            @ToolParam(name = "targetHostname", description = "目标主机名") String targetHostname,
            @ToolParam(name = "targetPort", description = "目标端口") int targetPort,
            @ToolParam(name = "usesHttps", description = "是否使用 HTTPS") boolean usesHttps,
            @ToolParam(name = "parameter", description = "要注入的参数名") String parameter,
            @ToolParam(name = "payloads", description = "AI 生成的 payload 列表") List<String> payloads) {

        if (requestContent == null || requestContent.isEmpty()) return "错误: 请求内容不能为空";
        if (targetHostname == null || targetHostname.isEmpty()) return "错误: 目标主机名不能为空";
        if (payloads == null || payloads.isEmpty()) return "错误: payload 列表不能为空";

        final String param = parameter != null ? parameter : "";
        final int port = targetPort > 0 ? targetPort : (usesHttps ? 443 : 80);

        AtomicReference<List<String>> confirmedPayloads = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                BatchFuzzDialog dialog = new BatchFuzzDialog(
                        requestContent, targetHostname, port, usesHttps, param, payloads);
                dialog.setVisible(true);
                if (dialog.isConfirmed()) confirmedPayloads.set(dialog.getPayloads());
            });
        } catch (Exception e) {
            return "弹窗错误: " + e.getMessage();
        }

        List<String> finalPayloads = confirmedPayloads.get();
        if (finalPayloads == null || finalPayloads.isEmpty()) return "用户取消了批量爆破。";

        return sendBatch(requestContent, targetHostname, port, usesHttps, param, finalPayloads);
    }

    private String sendBatch(String requestContent, String host, int port, boolean https,
                             String paramName, List<String> payloads) {
        int success = 0, error = 0;
        List<String> anomalies = new ArrayList<>();
        HttpService service = HttpService.httpService(host, port, https);

        for (String payload : payloads) {
            try {
                String reqBody = substituteParam(requestContent, paramName, payload);
                HttpRequest httpReq = HttpRequest.httpRequest(service, reqBody);
                HttpResponse resp = api.http().sendRequest(httpReq).response();

                if (resp.statusCode() >= 500) {
                    anomalies.add("HTTP " + resp.statusCode() + " server error for: " + truncate(payload, 80));
                } else if (resp.statusCode() >= 400) {
                    anomalies.add("HTTP " + resp.statusCode() + " for: " + truncate(payload, 80));
                }
                success++;
            } catch (Exception e) {
                error++;
                anomalies.add("发送失败 [" + truncate(payload, 60) + "]: " + e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("批量爆破完成。发送 ").append(payloads.size()).append(" 个请求，").append(success).append(" 成功");
        if (error > 0) sb.append(", ").append(error).append(" 失败");
        sb.append("。\n");
        if (!anomalies.isEmpty()) {
            sb.append("\n异常响应:\n");
            for (String a : anomalies) sb.append("  - ").append(a).append("\n");
        }
        return sb.toString();
    }

    static String substituteParam(String requestContent, String paramName, String newValue) {
        if (paramName == null || paramName.isEmpty()) return requestContent;
        String s = requestContent;
        String jsonPat = "\"" + Pattern.quote(paramName) + "\"\\s*:\\s*\"[^\"]*\"";
        java.util.regex.Matcher m = Pattern.compile(jsonPat).matcher(s);
        if (m.find()) {
            return s.substring(0, m.start()) + "\"" + paramName + "\":\"" + newValue.replace("\"", "\\\"") + "\""
                    + s.substring(m.end());
        }
        String formPat = "(?<![\\w-])" + Pattern.quote(paramName) + "=([^&\\s]*)";
        m = Pattern.compile(formPat).matcher(s);
        if (m.find()) {
            return s.substring(0, m.start()) + paramName + "=" + newValue + s.substring(m.end());
        }
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ============================================================
    private static class BatchFuzzDialog extends JDialog {
        private boolean confirmed;
        private JTextArea payloadArea;
        private JTextArea previewArea;
        private JLabel countLabel;
        private String requestContent, host, paramName;
        private int port;
        private boolean https;

        BatchFuzzDialog(String requestContent, String host, int port, boolean https,
                        String paramName, List<String> payloads) {
            super((Frame) null, "AI 批量爆破 - 确认 Payloads", true);
            this.requestContent = requestContent;
            this.host = host;
            this.port = port;
            this.https = https;
            this.paramName = paramName;

            setLayout(new BorderLayout(8, 8));
            ((JPanel) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
            split.setResizeWeight(0.5);

            JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
            leftPanel.setBorder(BorderFactory.createTitledBorder("Payload 列表（可编辑、删改）"));
            payloadArea = new JTextArea();
            payloadArea.setFont(new Font("Consolas", Font.PLAIN, 12));
            StringBuilder sb = new StringBuilder();
            for (String p : payloads) sb.append(p).append("\n");
            payloadArea.setText(sb.toString());
            payloadArea.setCaretPosition(0);
            leftPanel.add(new JScrollPane(payloadArea), BorderLayout.CENTER);
            countLabel = new JLabel("共 " + payloads.size() + " 个 payload");
            leftPanel.add(countLabel, BorderLayout.SOUTH);
            split.setLeftComponent(leftPanel);

            JPanel rightPanel = new JPanel(new BorderLayout(4, 4));
            rightPanel.setBorder(BorderFactory.createTitledBorder("请求预览"));
            previewArea = new JTextArea();
            previewArea.setFont(new Font("Consolas", Font.PLAIN, 11));
            previewArea.setEditable(false);
            String preview = "目标: " + (https ? "https://" : "http://") + host + ":" + port + "\n\n"
                    + (!payloads.isEmpty() ? substituteParam(requestContent, paramName, payloads.get(0)) : requestContent);
            previewArea.setText(preview);
            rightPanel.add(new JScrollPane(previewArea), BorderLayout.CENTER);
            split.setRightComponent(rightPanel);
            add(split, BorderLayout.CENTER);

            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            btnPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
            JButton cancelBtn = new JButton("取消");
            cancelBtn.addActionListener(e -> { dispose(); });
            JButton confirmBtn = new JButton("确认发送");
            confirmBtn.addActionListener(e -> { confirmed = true; dispose(); });
            btnPanel.add(cancelBtn);
            btnPanel.add(confirmBtn);
            add(btnPanel, BorderLayout.SOUTH);

            payloadArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                void update() {
                    int total = 0;
                    for (String l : payloadArea.getText().split("\\n")) {
                        if (!l.trim().isEmpty()) total++;
                    }
                    countLabel.setText("共 " + total + " 个 payload");
                }
                public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) {}
            });

            setSize(960, 600);
            setLocationRelativeTo(null);
        }

        boolean isConfirmed() { return confirmed; }

        List<String> getPayloads() {
            List<String> list = new ArrayList<>();
            for (String line : payloadArea.getText().split("\\n")) {
                String t = line.trim();
                if (!t.isEmpty()) list.add(t);
            }
            return list;
        }
    }
}
