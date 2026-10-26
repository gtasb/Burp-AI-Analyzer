package com.ai.analyzer.ui;

import com.ai.analyzer.graph.GraphStore;
import com.ai.analyzer.graph.GraphTypes.Edge;
import com.ai.analyzer.graph.GraphTypes.EdgeType;
import com.ai.analyzer.graph.GraphTypes.FactStatus;
import com.ai.analyzer.graph.GraphTypes.Node;
import com.ai.analyzer.graph.GraphTypes.NodeType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资产覆盖图：按 Origin 查看当前知识图谱。
 *
 * <p>视觉参考 ARTEX 的资产覆盖图：浅色画布、力导向布局、圆形图标节点、白色圆角标签；
 * 已分析（fact 锚定）节点高亮为实心彩色，未分析节点为灰色描边，推测关系用虚线；
 * 同类节点超过阈值时折叠为虚线圈「…」，点击展开。支持拖拽节点、拖拽画布、滚轮缩放，
 * 并可导出同风格的交互式 HTML。
 */
public class GraphViewerPanel extends JPanel {

    private static final int MAX_NODES = 500;
    private static final int MAX_EDGES = 2000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final JComboBox<String> rootCombo = new JComboBox<>();
    private final JLabel statusLabel = new JLabel("就绪");
    private final JTextArea detailArea = new JTextArea(4, 20);
    private final GraphCanvas canvas;

    private boolean updatingRoots;
    private int loadGeneration;

    public GraphViewerPanel() {
        this.canvas = new GraphCanvas();

        setLayout(new BorderLayout(8, 8));
        setBorder(UIStyles.panelBorder());

        JButton refreshButton = new JButton("刷新");
        UIStyles.styleSecondary(refreshButton);
        refreshButton.addActionListener(e -> refreshRoots());

        JButton exportButton = new JButton("导出 HTML");
        UIStyles.styleSecondary(exportButton);
        exportButton.addActionListener(e -> exportHtml());

        rootCombo.setPreferredSize(new Dimension(360, rootCombo.getPreferredSize().height));
        rootCombo.addActionListener(e -> {
            if (updatingRoots) return;
            Object sel = rootCombo.getSelectedItem();
            if (sel != null) loadGraph(sel.toString());
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(new JLabel("站点:"));
        top.add(rootCombo);
        top.add(refreshButton);
        top.add(exportButton);
        top.add(statusLabel);

        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        UIStyles.setFont(detailArea, 12f, Font.PLAIN);
        detailArea.setText("点击节点查看详情；虚线圈「…」表示折叠的参数组");
        JScrollPane detailPane = new JScrollPane(detailArea);
        detailPane.setPreferredSize(new Dimension(100, 92));
        detailPane.setBorder(UIStyles.scrollBorder());

        add(top, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(detailPane, BorderLayout.SOUTH);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refreshRoots();
            }
        });
    }

    private void refreshRoots() {
        statusLabel.setText("加载站点…");
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return GraphStore.getInstance().listRoots();
            }

            @Override
            protected void done() {
                try {
                    List<String> roots = get();
                    String previous = (String) rootCombo.getSelectedItem();
                    updatingRoots = true;
                    rootCombo.removeAllItems();
                    for (String r : roots) {
                        rootCombo.addItem(r);
                    }
                    if (previous != null && roots.contains(previous)) {
                        rootCombo.setSelectedItem(previous);
                    }
                    updatingRoots = false;
                    if (roots.isEmpty()) {
                        statusLabel.setText("暂无图谱数据");
                        canvas.showEmpty("暂无图谱数据\n启动被动扫描或主动分析后自动建图");
                        return;
                    }
                    loadGraph((String) rootCombo.getSelectedItem());
                } catch (Exception ex) {
                    updatingRoots = false;
                    String msg = rootMessage(ex);
                    statusLabel.setText("加载失败: " + msg);
                    canvas.showEmpty("图谱加载失败\n" + msg);
                }
            }
        }.execute();
    }

    private void loadGraph(String rootId) {
        if (rootId == null || rootId.isBlank()) return;
        final int generation = ++loadGeneration;
        statusLabel.setText("加载 " + rootId + " …");
        new SwingWorker<GraphData, Void>() {
            @Override
            protected GraphData doInBackground() {
                GraphStore store = GraphStore.getInstance();
                List<Node> nodes = store.listNodes(rootId, MAX_NODES);
                List<Edge> edges = store.listEdges(rootId, MAX_EDGES);
                return new GraphData(rootId, nodes, edges);
            }

            @Override
            protected void done() {
                if (generation != loadGeneration) return;
                try {
                    GraphData data = get();
                    if (data.nodes().isEmpty()) {
                        canvas.showEmpty("该站点暂无图谱节点");
                        statusLabel.setText("节点 0 / 边 0");
                        return;
                    }
                    canvas.setData(data);
                    statusLabel.setText("范围内 " + canvas.scopeCount() + " · 已测 " + canvas.coveredCount()
                            + " · 节点 " + data.nodes().size() + " / 边 " + data.edges().size());
                    detailArea.setText("点击节点查看详情；虚线圈「…」表示折叠的参数组");
                } catch (Exception ex) {
                    statusLabel.setText("加载失败: " + rootMessage(ex));
                }
            }
        }.execute();
    }

    // ================= 导出 HTML =================

    private void exportHtml() {
        GraphData data = canvas.data;
        if (data == null || data.nodes().isEmpty()) {
            statusLabel.setText("没有可导出的图谱数据");
            return;
        }
        String host = data.rootId().replaceAll("^https?://", "").replaceAll("[^a-zA-Z0-9._-]+", "_");
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("asset-coverage-" + host + ".html"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), buildHtml(data), StandardCharsets.UTF_8);
            statusLabel.setText("已导出: " + chooser.getSelectedFile().getName());
        } catch (Exception e) {
            statusLabel.setText("导出失败: " + rootMessage(e));
        }
    }

    private String buildHtml(GraphData data) {
        int width = 1400;
        int height = 860;
        int pad = 80;
        double scale = Math.min((width - 2.0 * pad) / 2.0, (height - 2.0 * pad) / 2.0);

        List<Node> visible = new ArrayList<>(canvas.visibleNodes);
        Set<Long> visibleIds = new HashSet<>();
        for (Node n : visible) visibleIds.add(n.id());
        int scope = canvas.scopeCount();
        Set<Long> covered = canvas.coveredIds();
        int coveredCount = canvas.coveredCount();

        JsonObject nodeJson = new JsonObject();
        for (Node n : data.nodes()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", n.type().name());
            o.addProperty("name", n.name());
            o.addProperty("status", n.status().name());
            o.addProperty("confidence", n.confidence());
            o.addProperty("canonical", n.canonicalKey());
            o.addProperty("source", n.sourceType() + "/" + n.sourceId());
            o.addProperty("updated", n.updatedAt());
            o.addProperty("properties", n.properties() != null ? n.properties().toString() : "");
            nodeJson.add(String.valueOf(n.id()), o);
        }

        StringBuilder sb = new StringBuilder(96 * 1024);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>资产覆盖图 - ")
                .append(html(data.rootId())).append("</title><style>")
                .append("body{margin:0;font-family:'Microsoft YaHei',sans-serif;background:#F7F8FA;color:#374151;display:flex;height:100vh}")
                .append("#stage{flex:1;position:relative;min-width:0;overflow:hidden}")
                .append("#side{width:320px;background:#fff;border-left:1px solid #E5E7EB;padding:16px;overflow:auto;box-sizing:border-box}")
                .append("svg{width:100%;height:100%;display:block;cursor:grab}")
                .append(".edge{stroke:#D5D9E0;stroke-width:1}")
                .append(".edge.inferred{stroke:#C9A45C;stroke-dasharray:5 4}")
                .append(".node{cursor:pointer}")
                .append(".node:hover{stroke:#2F6FDF;stroke-width:3}")
                .append(".label{font-size:12px;fill:#374151;paint-order:stroke;stroke:#F7F8FA;stroke-width:4px}")
                .append("#legend{position:absolute;left:16px;top:16px;background:#fff;border:1px solid #E5E7EB;border-radius:12px;padding:12px 14px;box-shadow:0 2px 8px rgba(0,0,0,.06);font-size:12px;min-width:250px}")
                .append("#legend b{font-size:13px}")
                .append(".lgrow{display:flex;flex-wrap:wrap;gap:10px;margin-top:8px}")
                .append(".lg{display:flex;align-items:center;gap:4px}")
                .append(".dot{width:10px;height:10px;border-radius:50%;display:inline-block}")
                .append(".hint{color:#9CA3AF;font-size:11px;line-height:1.6;margin-top:8px}")
                .append("</style></head><body>");

        sb.append("<div id=\"stage\"><svg id=\"graph\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">")
                .append("<rect width=\"100%\" height=\"100%\" fill=\"#F7F8FA\"/>");

        sb.append("<g id=\"edges\">");
        for (Edge e : canvas.visibleEdges) {
            Point2D.Double a = canvas.world.get(e.sourceNodeId());
            Point2D.Double b = canvas.world.get(e.targetNodeId());
            if (a == null || b == null) continue;
            boolean inferred = e.status() == FactStatus.INFERRED
                    || e.status() == FactStatus.DERIVED
                    || e.status() == FactStatus.REJECTED;
            sb.append("<line class=\"edge").append(inferred ? " inferred" : "").append("\" x1=\"")
                    .append(fmt(width / 2.0 + a.x * scale)).append("\" y1=\"").append(fmt(height / 2.0 + a.y * scale))
                    .append("\" x2=\"").append(fmt(width / 2.0 + b.x * scale)).append("\" y2=\"")
                    .append(fmt(height / 2.0 + b.y * scale)).append("\"/>");
        }
        sb.append("</g><g id=\"nodes\">");
        for (Node n : visible) {
            Point2D.Double p = canvas.world.get(n.id());
            if (p == null) continue;
            double x = width / 2.0 + p.x * scale;
            double y = height / 2.0 + p.y * scale;
            if (isFoldNode(n)) {
                int count = n.prop("count") instanceof Number num ? num.intValue() : 0;
                sb.append("<g class=\"fold\" data-id=\"").append(n.id()).append("\">")
                        .append("<circle cx=\"").append(fmt(x)).append("\" cy=\"").append(fmt(y))
                        .append("\" r=\"13\" fill=\"#F1F5F9\" stroke=\"#94A3B8\" stroke-width=\"1.4\" stroke-dasharray=\"3 3\"/>")
                        .append("<text x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y + 4))
                        .append("\" text-anchor=\"middle\" fill=\"#475569\" font-size=\"12\">…</text></g>");
                String flabel = "还有 " + count + " 个" + typeLabel(n.type());
                double ftw = flabel.length() * 12 * 0.62 + 16;
                sb.append("<rect x=\"").append(fmt(x - ftw / 2)).append("\" y=\"").append(fmt(y + 21))
                        .append("\" width=\"").append(fmt(ftw)).append("\" height=\"24\" rx=\"8\" fill=\"#FFFFFF\" opacity=\"0.96\"/>")
                        .append("<text class=\"label\" x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y + 38))
                        .append("\" text-anchor=\"middle\">").append(html(flabel)).append("</text>");
                continue;
            }
            boolean cov = covered.contains(n.id());
            Color fill = cov ? colorFor(n.type()) : Color.WHITE;
            String stroke = cov ? hex(colorFor(n.type()).darker()) : "#C4C9D1";
            boolean inferred = n.status() == FactStatus.INFERRED
                    || n.status() == FactStatus.DERIVED
                    || n.status() == FactStatus.REJECTED;
            String dash = inferred ? " stroke-dasharray=\"5 4\"" : "";
            int r = n.type() == NodeType.ORIGIN ? 22 : 18;
            sb.append("<g class=\"node\" data-id=\"").append(n.id()).append("\">")
                    .append("<circle cx=\"").append(fmt(x)).append("\" cy=\"").append(fmt(y))
                    .append("\" r=\"").append(r).append("\" fill=\"").append(hex(fill))
                    .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"1.5\"").append(dash).append("/>")
                    .append(iconSvg(n.type(), x, y, cov ? "#FFFFFF" : "#9CA3AF"))
                    .append("<title>").append(html(typeLabel(n.type()) + "  " + n.name())).append("</title></g>");
            String label = nodeLabel(n);
            double tw = label.length() * 12 * 0.62 + 16;
            sb.append("<rect x=\"").append(fmt(x - tw / 2)).append("\" y=\"").append(fmt(y + r + 8))
                    .append("\" width=\"").append(fmt(tw)).append("\" height=\"24\" rx=\"8\" fill=\"#FFFFFF\" opacity=\"0.96\"/>")
                    .append("<text class=\"label\" x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y + r + 25))
                    .append("\" text-anchor=\"middle\">").append(html(label)).append("</text>");
        }
        sb.append("</g></svg>");

        sb.append("<div id=\"legend\"><b>范围内 ").append(scope).append(" · 已分析 ").append(coveredCount).append("</b>")
                .append("<div class=\"lgrow\">");
        NodeType[] legendTypes = {NodeType.ORIGIN, NodeType.INTERFACE, NodeType.RESOURCE,
                NodeType.TECHNOLOGY, NodeType.FEATURE, NodeType.BACKEND_HYPOTHESIS,
                NodeType.VULN_CLASS, NodeType.PARAMETER};
        for (NodeType t : legendTypes) {
            sb.append("<span class=\"lg\"><span class=\"dot\" style=\"background:").append(hex(colorFor(t)))
                    .append("\"></span>").append(html(typeLabel(t))).append("</span>");
        }
        sb.append("</div><div class=\"lgrow\"><span class=\"lg\"><span class=\"dot\" style=\"background:#10B981\"></span>已分析（fact 锚定）</span>")
                .append("<span class=\"lg\"><span class=\"dot\" style=\"background:#fff;border:1.5px solid #C4C9D1\"></span>未分析</span>")
                .append("<span class=\"lg\"><span class=\"dot\" style=\"background:#fff;border:1.5px dashed #9CA3AF\"></span>推测</span></div>")
                .append("<div class=\"hint\">力导向布局，可拖拽节点、滚轮缩放；<br>虚线圈「…」是被折叠的同类节点，点击展开；点击资产查看详情。</div></div>");

        sb.append("<div id=\"side\"><h3 style=\"margin-top:0;word-break:break-all;font-size:14px\">")
                .append(html(data.rootId())).append("</h3>")
                .append("<div style=\"color:#6B7280;font-size:12px\">范围内 ").append(scope)
                .append(" · 已测 ").append(coveredCount).append("</div>")
                .append("<div id=\"detail\" style=\"margin-top:14px;color:#6B7280;font-size:12px\">点击节点查看详情</div>");

        sb.append("<script>const NODES=").append(GSON.toJson(nodeJson)).append(";")
                .append("document.querySelectorAll('.node').forEach(g=>{g.addEventListener('click',()=>{")
                .append("const n=NODES[g.dataset.id];if(!n)return;const d=document.getElementById('detail');")
                .append("d.innerHTML='<b>'+n.type+'</b> '+esc(n.name)+'<br>status='+n.status+'  confidence='+n.confidence")
                .append("+'<br>canonical: '+esc(n.canonical)+'<br>source: '+esc(n.source)+'<br>updated: '+esc(n.updated)")
                .append("+'<br>properties: '+esc(n.properties);});});")
                .append("function esc(s){return String(s==null?'':s).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}")
                .append("const svg=document.getElementById('graph');let vx=0,vy=0,vw=").append(width).append(",vh=").append(height).append(";")
                .append("svg.addEventListener('wheel',e=>{e.preventDefault();const k=e.deltaY>0?1.1:0.9;")
                .append("vw*=k;vh*=k;svg.setAttribute('viewBox',vx+' '+vy+' '+vw+' '+vh);},{passive:false});")
                .append("</script></div></body></html>");
        return sb.toString();
    }

    private static String iconSvg(NodeType type, double x, double y, String color) {
        String c = "stroke=\"" + color + "\" fill=\"none\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"";
        return switch (type) {
            case ORIGIN -> "<g " + c + "><circle cx=\"" + fmt(x) + "\" cy=\"" + fmt(y) + "\" r=\"8\"/>"
                    + "<ellipse cx=\"" + fmt(x) + "\" cy=\"" + fmt(y) + "\" rx=\"3.6\" ry=\"8\"/>"
                    + "<line x1=\"" + fmt(x - 8) + "\" y1=\"" + fmt(y) + "\" x2=\"" + fmt(x + 8) + "\" y2=\"" + fmt(y) + "\"/></g>";
            case INTERFACE -> "<g " + c + "><rect x=\"" + fmt(x - 10) + "\" y=\"" + fmt(y - 5)
                    + "\" width=\"11\" height=\"10\" rx=\"5\"/><rect x=\"" + fmt(x - 1) + "\" y=\"" + fmt(y - 5)
                    + "\" width=\"11\" height=\"10\" rx=\"5\"/></g>";
            case MODULE -> "<g " + c + "><rect x=\"" + fmt(x - 8) + "\" y=\"" + fmt(y - 8)
                    + "\" width=\"16\" height=\"16\" rx=\"4\"/><rect x=\"" + fmt(x - 3) + "\" y=\"" + fmt(y - 3)
                    + "\" width=\"6\" height=\"6\" rx=\"1.5\" fill=\"" + color + "\" stroke=\"none\"/></g>";
            case TECHNOLOGY, VERSION -> "<g " + c + "><circle cx=\"" + fmt(x) + "\" cy=\"" + fmt(y + 5)
                    + "\" r=\"2.5\" fill=\"" + color + "\" stroke=\"none\"/>"
                    + "<path d=\"M " + fmt(x - 7) + " " + fmt(y + 2) + " a 9 9 0 0 1 14 0\"/>"
                    + "<path d=\"M " + fmt(x - 11) + " " + fmt(y - 2) + " a 14 14 0 0 1 22 0\"/></g>";
            case PARAMETER -> "<g " + c + "><path d=\"M " + fmt(x - 8) + " " + fmt(y - 6) + " h 10 l 6 6 v 6 h -16 z\"/>"
                    + "<circle cx=\"" + fmt(x - 3) + "\" cy=\"" + fmt(y - 2) + "\" r=\"1.6\" fill=\"" + color + "\" stroke=\"none\"/></g>";
            case VULNERABILITY -> "<g " + c + "><path d=\"M " + fmt(x) + " " + fmt(y - 9) + " l 8 3 v 5 c 0 5 -4 8 -8 10 c -4 -2 -8 -5 -8 -10 v -5 z\"/>"
                    + "<line x1=\"" + fmt(x) + "\" y1=\"" + fmt(y - 3) + "\" x2=\"" + fmt(x) + "\" y2=\"" + fmt(y + 2) + "\"/>"
                    + "<circle cx=\"" + fmt(x) + "\" cy=\"" + fmt(y + 5) + "\" r=\"1.2\" fill=\"" + color + "\" stroke=\"none\"/></g>";
            case RESOURCE -> "<g " + c + "><path d=\"M " + fmt(x - 7) + " " + fmt(y - 9) + " h 9 l 5 5 v 13 h -14 z\"/>"
                    + "<path d=\"M " + fmt(x + 2) + " " + fmt(y - 9) + " v 5 h 5\"/></g>";
            case EVIDENCE -> "<g " + c + "><rect x=\"" + fmt(x - 7) + "\" y=\"" + fmt(y - 9) + "\" width=\"14\" height=\"18\" rx=\"2\"/>"
                    + "<line x1=\"" + fmt(x - 3) + "\" y1=\"" + fmt(y - 3) + "\" x2=\"" + fmt(x + 3) + "\" y2=\"" + fmt(y - 3) + "\"/>"
                    + "<line x1=\"" + fmt(x - 3) + "\" y1=\"" + fmt(y + 1) + "\" x2=\"" + fmt(x + 3) + "\" y2=\"" + fmt(y + 1) + "\"/></g>";
            case FEATURE -> "<g " + c + "><path d=\"M " + fmt(x + 2) + " " + fmt(y - 10) + " l -8 11 h 6 l -2 9 l 8 -12 h -6 z\"/></g>";
            case BACKEND_HYPOTHESIS -> "<g " + c + "><rect x=\"" + fmt(x - 7) + "\" y=\"" + fmt(y - 7) + "\" width=\"14\" height=\"14\" rx=\"3\"/>"
                    + "<line x1=\"" + fmt(x - 10) + "\" y1=\"" + fmt(y - 3) + "\" x2=\"" + fmt(x - 7) + "\" y2=\"" + fmt(y - 3) + "\"/>"
                    + "<line x1=\"" + fmt(x - 10) + "\" y1=\"" + fmt(y + 3) + "\" x2=\"" + fmt(x - 7) + "\" y2=\"" + fmt(y + 3) + "\"/>"
                    + "<line x1=\"" + fmt(x + 7) + "\" y1=\"" + fmt(y - 3) + "\" x2=\"" + fmt(x + 10) + "\" y2=\"" + fmt(y - 3) + "\"/>"
                    + "<line x1=\"" + fmt(x + 7) + "\" y1=\"" + fmt(y + 3) + "\" x2=\"" + fmt(x + 10) + "\" y2=\"" + fmt(y + 3) + "\"/></g>";
            case VULN_CLASS -> "<g " + c + "><path d=\"M " + fmt(x) + " " + fmt(y - 9) + " l 8 3 v 5 c 0 5 -4 8 -8 10 c -4 -2 -8 -5 -8 -10 v -5 z\"/>"
                    + "<line x1=\"" + fmt(x) + "\" y1=\"" + fmt(y - 3) + "\" x2=\"" + fmt(x) + "\" y2=\"" + fmt(y + 2) + "\"/></g>";
            case CANDIDATE -> "<g " + c + "><circle cx=\"" + fmt(x) + "\" cy=\"" + fmt(y) + "\" r=\"8\"/>"
                    + "<path d=\"M " + fmt(x - 2.5) + " " + fmt(y - 3) + " a 3 3 0 1 1 3 5 v 1\"/>"
                    + "<circle cx=\"" + fmt(x) + "\" cy=\"" + fmt(y + 6) + "\" r=\"1.2\" fill=\"" + color + "\" stroke=\"none\"/></g>";
            case ANALYSIS_FACT -> "<g " + c + "><circle cx=\"" + fmt(x) + "\" cy=\"" + fmt(y) + "\" r=\"7\"/>"
                    + "<path d=\"M " + fmt(x - 3) + " " + fmt(y) + " l 2 2 l 4 -4\"/></g>";
            default -> "<g " + c + "><circle cx=\"" + fmt(x) + "\" cy=\"" + fmt(y) + "\" r=\"7\"/>"
                    + "<circle cx=\"" + fmt(x) + "\" cy=\"" + fmt(y) + "\" r=\"2\" fill=\"" + color + "\" stroke=\"none\"/></g>";
        };
    }

    private static boolean isFoldNode(Node n) {
        return n != null && Boolean.TRUE.equals(n.prop("fold"));
    }

    private static String nodeLabel(Node n) {        String name = n.name() == null ? "" : n.name();
        if (n.type() == NodeType.ORIGIN) {
            name = name.replaceFirst("^[a-zA-Z]+://", "");
        }
        return truncate(name, 30);
    }

    private static Map<Long, Integer> degreesOf(List<Edge> edges) {
        Map<Long, Integer> deg = new HashMap<>();
        for (Edge e : edges) {
            deg.merge(e.sourceNodeId(), 1, Integer::sum);
            deg.merge(e.targetNodeId(), 1, Integer::sum);
        }
        return deg;
    }

    private static Color colorFor(NodeType type) {
        return switch (type) {
            case ORIGIN -> new Color(47, 111, 223);
            case INTERFACE -> new Color(37, 99, 235);
            case PARAMETER -> new Color(139, 92, 246);
            case FUNCTION, BUSINESS_LOGIC -> new Color(168, 85, 247);
            case BUSINESS_ENTITY -> new Color(249, 115, 22);
            case TECHNOLOGY -> new Color(245, 158, 11);
            case VERSION -> new Color(180, 83, 9);
            case MODULE -> new Color(16, 185, 129);
            case SERVICE -> new Color(6, 182, 212);
            case VULNERABILITY -> new Color(239, 68, 68);
            case RESOURCE -> new Color(20, 184, 166);
            case EVIDENCE -> new Color(100, 116, 139);
            case FEATURE -> new Color(139, 92, 246);
            case BACKEND_HYPOTHESIS -> new Color(249, 115, 22);
            case VULN_CLASS -> new Color(239, 68, 68);
            case CANDIDATE -> new Color(244, 63, 94);
            case ANALYSIS_FACT -> new Color(16, 185, 129);
        };
    }

    private static String typeLabel(NodeType type) {
        return switch (type) {
            case ORIGIN -> "根域名";
            case INTERFACE -> "接口";
            case PARAMETER -> "参数";
            case FUNCTION -> "功能";
            case BUSINESS_ENTITY -> "业务实体";
            case BUSINESS_LOGIC -> "业务逻辑";
            case TECHNOLOGY -> "技术";
            case VERSION -> "版本";
            case MODULE -> "模块";
            case SERVICE -> "服务";
            case VULNERABILITY -> "漏洞";
            case RESOURCE -> "资源";
            case EVIDENCE -> "证据";
            case FEATURE -> "特征";
            case BACKEND_HYPOTHESIS -> "后端推断";
            case VULN_CLASS -> "漏洞类";
            case CANDIDATE -> "候选漏洞";
            case ANALYSIS_FACT -> "分析事实";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg;
    }

    private static String describe(Node n) {
        StringBuilder sb = new StringBuilder();
        sb.append(typeLabel(n.type())).append("  ").append(n.name()).append('\n');
        sb.append("id=").append(n.id())
                .append("  status=").append(n.status())
                .append("  confidence=").append(String.format(Locale.ROOT, "%.2f", n.confidence())).append('\n');
        sb.append("canonical: ").append(n.canonicalKey()).append('\n');
        sb.append("source: ").append(n.sourceType()).append('/').append(n.sourceId()).append('\n');
        sb.append("updated: ").append(n.updatedAt());
        if (n.properties() != null && !n.properties().isEmpty()) {
            String props = n.properties().toString();
            sb.append("\nproperties: ").append(props.length() > 300 ? props.substring(0, 300) + "…" : props);
        }
        return sb.toString();
    }

    private record GraphData(String rootId, List<Node> nodes, List<Edge> edges) {
    }

    // ================= Canvas =================

    private final class GraphCanvas extends JComponent {
        private GraphData data;
        private Map<Long, Point2D.Double> world = Map.of();
        private List<Node> visibleNodes = List.of();
        private List<Edge> visibleEdges = List.of();
        private Map<Long, Node> nodeById = Map.of();
        private Map<Long, Long> parentOf = Map.of();
        private Map<Long, List<Long>> childrenOf = Map.of();
        /** groupId(parent::type) → 当前展示上限（ARTEX 折叠语义） */
        private final Map<String, Integer> expandedGroups = new HashMap<>();
        private Set<Long> coveredIds = Set.of();
        private Node selected;
        private long foldSeq = -1;

        private static final int DEFAULT_FOLD = 12;

        private double zoom = 1.0;
        private double panX = 0;
        private double panY = 0;
        private Point dragStart;
        private Node draggingNode;
        private boolean panning;

        private static final int NODE_R = 17;
        private static final int ORIGIN_R = 21;

        GraphCanvas() {
            setOpaque(true);
            setMinimumSize(new Dimension(420, 320));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    dragStart = e.getPoint();
                    Node node = nodeAt(e.getX(), e.getY());
                    draggingNode = node;
                    panning = node == null && !isOverLegend(e.getX(), e.getY());
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    draggingNode = null;
                    panning = false;
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (isOverLegend(e.getX(), e.getY())) return;
                    Node node = nodeAt(e.getX(), e.getY());
                    if (node != null && isFold(node)) {
                        String groupId = String.valueOf(node.prop("groupId"));
                        expandedGroups.merge(groupId, 10, Integer::sum);
                        rebuildVisible();
                        relayout();
                        repaint();
                        return;
                    }
                    selected = node;
                    repaint();
                    detailArea.setText(node == null ? "点击节点查看详情" : describe(node));
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragStart == null) return;
                    int dx = e.getX() - dragStart.x;
                    int dy = e.getY() - dragStart.y;
                    dragStart = e.getPoint();
                    if (draggingNode != null) {
                        Point2D.Double p = world.get(draggingNode.id());
                        if (p != null) {
                            double s = scale();
                            world.put(draggingNode.id(), new Point2D.Double(p.x + dx / s, p.y + dy / s));
                            repaint();
                        }
                    } else if (panning) {
                        panX += dx;
                        panY += dy;
                        repaint();
                    }
                }
            });
            addMouseWheelListener(this::handleWheel);
        }

        private void handleWheel(MouseWheelEvent e) {
            double factor = e.getWheelRotation() > 0 ? 1.1 : 0.9;
            zoom = Math.max(0.4, Math.min(3.0, zoom * factor));
            repaint();
        }

        void showEmpty(String text) {
            emptyText = text;
            data = null;
            world = Map.of();
            visibleNodes = List.of();
            visibleEdges = List.of();
            selected = null;
            repaint();
        }

        private String emptyText = "选择站点后加载图谱";

        void setData(GraphData data) {
            this.data = data;
            this.expandedGroups.clear();
            this.zoom = 1.0;
            this.panX = 0;
            this.panY = 0;
            this.selected = null;
            buildContainment(data);
            this.coveredIds = computeCovered(data);
            rebuildVisible();
            relayout();
            repaint();
        }

        int scopeCount() {
            int c = 0;
            for (Node n : visibleNodes) {
                if (!isDetailNode(n) && !isFold(n)) c++;
            }
            return c;
        }

        int coveredCount() {
            int c = 0;
            for (Node n : visibleNodes) {
                if (!isDetailNode(n) && !isFold(n) && coveredIds.contains(n.id())) c++;
            }
            return c;
        }

        Set<Long> coveredIds() {
            return coveredIds;
        }

        private static boolean isDetailNode(Node n) {
            return n.type() == NodeType.PARAMETER
                    || n.type() == NodeType.EVIDENCE
                    || n.type() == NodeType.ANALYSIS_FACT;
        }

        private static boolean isFold(Node n) {
            return n != null && Boolean.TRUE.equals(n.prop("fold"));
        }

        /** 父子关系按优先级只取一条；COVERS 是覆盖锚点，不作为布局父子边。 */
        private void buildContainment(GraphData data) {
            Map<Long, Node> byId = new HashMap<>();
            for (Node n : data.nodes()) byId.put(n.id(), n);
            Map<Long, Long> parent = new HashMap<>();
            Map<Long, Integer> prio = new HashMap<>();
            for (Edge e : data.edges()) {
                if (e.relationType() == EdgeType.COVERS) continue;
                if (e.sourceNodeId() == e.targetNodeId()) continue;
                int p = parentPriority(e.relationType());
                if (p < 0) continue;
                Integer cur = prio.get(e.sourceNodeId());
                if (cur == null || p < cur) {
                    parent.put(e.sourceNodeId(), e.targetNodeId());
                    prio.put(e.sourceNodeId(), p);
                }
            }
            Map<Long, List<Long>> children = new HashMap<>();
            for (Map.Entry<Long, Long> en : parent.entrySet()) {
                children.computeIfAbsent(en.getValue(), k -> new ArrayList<>()).add(en.getKey());
            }
            nodeById = byId;
            parentOf = parent;
            childrenOf = children;
        }

        private static int parentPriority(EdgeType t) {
            return switch (t) {
                case HAS_INTERFACE, EXPOSES -> 0;
                case HAS_PARAMETER -> 1;
                case HAS_VERSION -> 2;
                case LOADS -> 3;
                case USES_TECHNOLOGY -> 4;
                case BELONGS_TO -> 5;
                case INDICATES -> 6;
                case SUPPORTS -> 7;
                case SUGGESTS -> 8;
                case DERIVED_FROM -> 9;
                case CALLS -> 10;
                case RELATED_TO -> 11;
                default -> -1;
            };
        }

        private Set<Long> computeCovered(GraphData data) {
            Set<Long> covered = new HashSet<>();
            Set<Long> factIds = new HashSet<>();
            for (Node n : data.nodes()) {
                if (n.type() == NodeType.ANALYSIS_FACT) factIds.add(n.id());
                if (n.status() == FactStatus.CONFIRMED) covered.add(n.id());
            }
            boolean anyInterfaceCovered = false;
            for (Edge e : data.edges()) {
                if (e.relationType() == EdgeType.COVERS && factIds.contains(e.sourceNodeId())) {
                    covered.add(e.targetNodeId());
                    Node target = nodeById.get(e.targetNodeId());
                    if (target != null && target.type() == NodeType.INTERFACE) anyInterfaceCovered = true;
                }
            }
            if (anyInterfaceCovered) {
                for (Node n : data.nodes()) {
                    if (n.type() == NodeType.ORIGIN) covered.add(n.id());
                }
            }
            return covered;
        }

        private int foldLimit(NodeType type) {
            return switch (type) {
                case PARAMETER, ANALYSIS_FACT -> 0;
                case EVIDENCE -> 2;
                case RESOURCE -> 6;
                default -> DEFAULT_FOLD;
            };
        }

        private void rebuildVisible() {
            if (data == null) {
                visibleNodes = List.of();
                visibleEdges = List.of();
                return;
            }
            foldSeq = -1;
            List<Node> out = new ArrayList<>();
            Set<Long> visible = new HashSet<>();
            Set<Long> handled = new HashSet<>();
            List<Edge> edges = new ArrayList<>();
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();

            for (Node n : data.nodes()) {
                if (n.type() == NodeType.ANALYSIS_FACT) continue;
                Long parent = parentOf.get(n.id());
                Node p = parent == null ? null : nodeById.get(parent);
                if (p == null || p.type() == NodeType.ANALYSIS_FACT) {
                    handled.add(n.id());
                    if (visible.add(n.id())) {
                        out.add(n);
                        queue.add(n.id());
                    }
                }
            }
            while (!queue.isEmpty()) {
                long parentId = queue.poll();
                List<Long> kids = childrenOf.get(parentId);
                if (kids == null || kids.isEmpty()) continue;
                Map<NodeType, List<Long>> groups = new java.util.LinkedHashMap<>();
                for (Long kid : kids) {
                    Node kn = nodeById.get(kid);
                    if (kn == null || kn.type() == NodeType.ANALYSIS_FACT) continue;
                    handled.add(kid);
                    groups.computeIfAbsent(kn.type(), k -> new ArrayList<>()).add(kid);
                }
                for (var entry : groups.entrySet()) {
                    List<Long> group = entry.getValue();
                    group.sort((a, b) -> {
                        boolean ca = coveredIds.contains(a), cb = coveredIds.contains(b);
                        if (ca != cb) return ca ? -1 : 1;
                        Node na = nodeById.get(a), nb = nodeById.get(b);
                        return String.valueOf(na == null ? "" : na.name())
                                .compareTo(String.valueOf(nb == null ? "" : nb.name()));
                    });
                    String groupId = parentId + "::" + entry.getKey().name();
                    int limit = expandedGroups.getOrDefault(groupId, foldLimit(entry.getKey()));
                    int shown = Math.min(Math.max(0, limit), group.size());
                    for (int i = 0; i < shown; i++) {
                        long kid = group.get(i);
                        Node kn = nodeById.get(kid);
                        if (kn != null && visible.add(kid)) {
                            out.add(kn);
                            queue.add(kid);
                        }
                    }
                    if (group.size() > shown) {
                        long foldId = foldSeq--;
                        int count = group.size() - shown;
                        Node fold = new Node(foldId, data.rootId(), entry.getKey(),
                                "…" + count, "fold:" + groupId, FactStatus.DERIVED, 0.0,
                                "fold", null,
                                Map.of("fold", true, "count", count, "groupId", groupId,
                                        "childType", entry.getKey().name()),
                                "", "");
                        out.add(fold);
                        edges.add(new Edge(foldId * 1000 + 1, data.rootId(), foldId, parentId,
                                EdgeType.CONTAINS, FactStatus.DERIVED, 0.0, "fold", null,
                                Map.of(), "", ""));
                    }
                }
            }
            // 兜底：完全没有任何父子边的孤立节点也展示出来（被折叠隐藏的节点不算）
            for (Node n : data.nodes()) {
                if (n.type() == NodeType.ANALYSIS_FACT) continue;
                if (!handled.contains(n.id()) && visible.add(n.id())) out.add(n);
            }
            Set<Long> visibleIds = new HashSet<>();
            for (Node n : out) visibleIds.add(n.id());
            for (Edge e : data.edges()) {
                if (e.relationType() == EdgeType.COVERS) continue;
                if (visibleIds.contains(e.sourceNodeId()) && visibleIds.contains(e.targetNodeId())) {
                    edges.add(e);
                }
            }
            visibleNodes = out;
            visibleEdges = edges;
        }

        private void relayout() {
            world = ForceLayout.layout(visibleNodes, visibleEdges);
        }

        private double baseScale() {
            return Math.max(40, Math.min(getWidth(), getHeight()) / 2.0 - 60);
        }

        private double scale() {
            return baseScale() * zoom;
        }

        private Point2D.Double toScreen(double wx, double wy) {
            double s = scale();
            return new Point2D.Double(getWidth() / 2.0 + wx * s + panX,
                    getHeight() / 2.0 + wy * s + panY);
        }

        private int radiusOf(Node n) {
            return n.type() == NodeType.ORIGIN ? ORIGIN_R : NODE_R;
        }

        private Node nodeAt(int mx, int my) {
            Node best = null;
            double bestDist = Double.MAX_VALUE;
            for (Node n : visibleNodes) {
                Point2D.Double p = world.get(n.id());
                if (p == null) continue;
                Point2D.Double s = toScreen(p.x, p.y);
                double d = Math.hypot(s.x - mx, s.y - my);
                if (d <= radiusOf(n) + 3 && d < bestDist) {
                    bestDist = d;
                    best = n;
                }
            }
            return best;
        }

        private boolean isOverLegend(int mx, int my) {
            return mx >= 12 && mx <= 292 && my >= 12 && my <= 210;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color bg = UIManager.getColor("Panel.background");
                if (bg == null) bg = new Color(0xF7, 0xF8, 0xFA);
                boolean dark = luminance(bg) < 128;
                Color canvasBg = dark ? new Color(0x1E, 0x21, 0x25) : new Color(0xF7, 0xF8, 0xFA);
                Color fg = dark ? new Color(0xE5, 0xE7, 0xEB) : new Color(0x37, 0x41, 0x51);
                Color pillBg = dark ? new Color(0x2B, 0x2F, 0x36) : Color.WHITE;
                Color edgeColor = dark ? new Color(0x3A, 0x3F, 0x46) : new Color(0xD5, 0xD9, 0xE0);
                Color nodeBg = dark ? new Color(0x2B, 0x2F, 0x36) : Color.WHITE;
                Color untestedStroke = dark ? new Color(0x5B, 0x61, 0x6A) : new Color(0xC4, 0xC9, 0xD1);
                Color untestedIcon = dark ? new Color(0x8B, 0x92, 0x9B) : new Color(0x9C, 0xA3, 0xAF);

                g.setColor(canvasBg);
                g.fillRect(0, 0, getWidth(), getHeight());

                if (data == null || visibleNodes.isEmpty()) {
                    g.setColor(untestedIcon);
                    g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
                    drawCenteredLines(g, emptyText, canvasBg);
                    return;
                }

                for (Edge e : visibleEdges) {
                    Point2D.Double a = world.get(e.sourceNodeId());
                    Point2D.Double b = world.get(e.targetNodeId());
                    if (a == null || b == null) continue;
                    Point2D.Double sa = toScreen(a.x, a.y);
                    Point2D.Double sb = toScreen(b.x, b.y);
                    boolean inferred = e.status() == FactStatus.INFERRED
                            || e.status() == FactStatus.DERIVED
                            || e.status() == FactStatus.REJECTED;
                    g.setColor(inferred ? new Color(0xC9, 0xA4, 0x5C) : edgeColor);
                    if (inferred) {
                        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                                1f, new float[]{4f, 4f}, 0f));
                    } else {
                        g.setStroke(new BasicStroke(1f));
                    }
                    g.draw(new Line2D.Double(sa, sb));
                }

                for (Node n : visibleNodes) {
                    Point2D.Double p = world.get(n.id());
                    if (p == null) continue;
                    Point2D.Double s = toScreen(p.x, p.y);
                    if (isFold(n)) {
                        drawFoldNode(g, n, s, pillBg, untestedStroke, fg);
                        continue;
                    }
                    int r = radiusOf(n);
                    boolean cov = coveredIds.contains(n.id());
                    Color fill = cov ? colorFor(n.type()) : nodeBg;
                    Color stroke = cov ? colorFor(n.type()).darker() : untestedStroke;
                    boolean inferred = n.status() == FactStatus.INFERRED
                            || n.status() == FactStatus.DERIVED
                            || n.status() == FactStatus.REJECTED;

                    g.setColor(fill);
                    g.fillOval((int) (s.x - r), (int) (s.y - r), r * 2, r * 2);
                    if (inferred) {
                        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                                1f, new float[]{4f, 3f}, 0f));
                    } else {
                        g.setStroke(new BasicStroke(1.5f));
                    }
                    g.setColor(stroke);
                    g.drawOval((int) (s.x - r), (int) (s.y - r), r * 2, r * 2);

                    if (selected != null && selected.id() == n.id()) {
                        g.setColor(UIStyles.ACCENT);
                        g.setStroke(new BasicStroke(3f));
                        g.drawOval((int) (s.x - r - 4), (int) (s.y - r - 4), (r + 4) * 2, (r + 4) * 2);
                    }

                    drawIcon(g, n.type(), s.x, s.y, cov ? Color.WHITE : untestedIcon);

                    String label = nodeLabel(n);
                    Font font = new Font("Microsoft YaHei", Font.PLAIN, 11);
                    g.setFont(font);
                    FontMetrics fm = g.getFontMetrics();
                    int tw = fm.stringWidth(label);
                    int lx = (int) (s.x - tw / 2.0 - 7);
                    int ly = (int) (s.y + r + 8);
                    int lh = fm.getHeight() + 6;
                    g.setColor(new Color(0, 0, 0, dark ? 60 : 22));
                    g.fillRoundRect(lx + 1, ly + 2, tw + 14, lh, 8, 8);
                    g.setColor(pillBg);
                    g.fillRoundRect(lx, ly, tw + 14, lh, 8, 8);
                    g.setColor(fg);
                    g.drawString(label, lx + 7, ly + fm.getAscent() + 3);
                }

                drawLegend(g, canvasBg, fg, pillBg, dark);
            } finally {
                g.dispose();
            }
        }

        private void drawFoldNode(Graphics2D g, Node n, Point2D.Double s,
                                  Color pillBg, Color stroke, Color fg) {
            int r = 13;
            g.setColor(pillBg);
            g.fillOval((int) (s.x - r), (int) (s.y - r), r * 2, r * 2);
            g.setColor(stroke);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{3f, 3f}, 0f));
            g.drawOval((int) (s.x - r), (int) (s.y - r), r * 2, r * 2);
            g.setStroke(new BasicStroke(1f));
            g.setColor(fg);
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("…", (int) s.x - fm.stringWidth("…") / 2, (int) s.y + fm.getAscent() / 2 - 1);

            int count = n.prop("count") instanceof Number num ? num.intValue() : 0;
            String label = "还有 " + count + " 个" + typeLabel(n.type());
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            fm = g.getFontMetrics();
            int tw = fm.stringWidth(label);
            int lx = (int) (s.x - tw / 2.0 - 7);
            int ly = (int) (s.y + r + 8);
            int lh = fm.getHeight() + 6;
            g.setColor(new Color(0, 0, 0, 22));
            g.fillRoundRect(lx + 1, ly + 2, tw + 14, lh, 8, 8);
            g.setColor(pillBg);
            g.fillRoundRect(lx, ly, tw + 14, lh, 8, 8);
            g.setColor(fg);
            g.drawString(label, lx + 7, ly + fm.getAscent() + 3);
        }

        private void drawIcon(Graphics2D g, NodeType type, double x, double y, Color color) {
            g.setColor(color);
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (type) {
                case ORIGIN -> {
                    g.drawOval((int) x - 8, (int) y - 8, 16, 16);
                    g.drawOval((int) x - 3, (int) y - 8, 7, 16);
                    g.drawLine((int) x - 8, (int) y, (int) x + 8, (int) y);
                }
                case INTERFACE -> {
                    g.drawRoundRect((int) x - 10, (int) y - 5, 11, 10, 6, 6);
                    g.drawRoundRect((int) x - 1, (int) y - 5, 11, 10, 6, 6);
                }
                case MODULE -> {
                    g.drawRoundRect((int) x - 8, (int) y - 8, 16, 16, 4, 4);
                    g.fillRoundRect((int) x - 3, (int) y - 3, 6, 6, 2, 2);
                }
                case TECHNOLOGY, VERSION -> {
                    g.fillOval((int) x - 2, (int) y + 3, 5, 5);
                    g.drawArc((int) x - 7, (int) y - 4, 15, 15, 40, 100);
                    g.drawArc((int) x - 11, (int) y - 8, 23, 23, 40, 100);
                }
                case PARAMETER -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(x - 8, y - 6);
                    p.lineTo(x + 2, y - 6);
                    p.lineTo(x + 8, y);
                    p.lineTo(x + 8, y + 6);
                    p.lineTo(x - 8, y + 6);
                    p.closePath();
                    g.draw(p);
                    g.fillOval((int) x - 4, (int) y - 3, 4, 4);
                }
                case VULNERABILITY, VULN_CLASS -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(x, y - 9);
                    p.lineTo(x + 8, y - 6);
                    p.lineTo(x + 8, y);
                    p.curveTo(x + 8, y + 5, x + 4, y + 8, x, y + 10);
                    p.curveTo(x - 4, y + 8, x - 8, y + 5, x - 8, y);
                    p.lineTo(x - 8, y - 6);
                    p.closePath();
                    g.draw(p);
                    g.drawLine((int) x, (int) y - 3, (int) x, (int) y + 2);
                    g.fillOval((int) x - 1, (int) y + 4, 3, 3);
                }
                case RESOURCE -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(x - 7, y - 9);
                    p.lineTo(x + 2, y - 9);
                    p.lineTo(x + 7, y - 4);
                    p.lineTo(x + 7, y + 9);
                    p.lineTo(x - 7, y + 9);
                    p.closePath();
                    g.draw(p);
                    g.drawLine((int) x + 2, (int) y - 9, (int) x + 2, (int) y - 4);
                    g.drawLine((int) x + 2, (int) y - 4, (int) x + 7, (int) y - 4);
                }
                case EVIDENCE -> {
                    g.drawRoundRect((int) x - 7, (int) y - 9, 14, 18, 2, 2);
                    g.drawLine((int) x - 3, (int) y - 3, (int) x + 3, (int) y - 3);
                    g.drawLine((int) x - 3, (int) y + 1, (int) x + 3, (int) y + 1);
                }
                case FEATURE -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(x + 2, y - 10);
                    p.lineTo(x - 6, y + 1);
                    p.lineTo(x, y + 1);
                    p.lineTo(x - 2, y + 10);
                    p.lineTo(x + 6, y - 1);
                    p.lineTo(x, y - 1);
                    p.closePath();
                    g.draw(p);
                }
                case BACKEND_HYPOTHESIS -> {
                    g.drawRoundRect((int) x - 7, (int) y - 7, 14, 14, 3, 3);
                    g.drawLine((int) x - 10, (int) y - 3, (int) x - 7, (int) y - 3);
                    g.drawLine((int) x - 10, (int) y + 3, (int) x - 7, (int) y + 3);
                    g.drawLine((int) x + 7, (int) y - 3, (int) x + 10, (int) y - 3);
                    g.drawLine((int) x + 7, (int) y + 3, (int) x + 10, (int) y + 3);
                }
                case CANDIDATE -> {
                    g.drawOval((int) x - 8, (int) y - 8, 16, 16);
                    g.drawArc((int) x - 3, (int) y - 5, 6, 7, 0, 220);
                    g.fillOval((int) x - 1, (int) y + 3, 3, 3);
                }
                case ANALYSIS_FACT -> {
                    g.drawOval((int) x - 7, (int) y - 7, 14, 14);
                    g.drawLine((int) x - 3, (int) y, (int) x - 1, (int) y + 2);
                    g.drawLine((int) x - 1, (int) y + 2, (int) x + 4, (int) y - 3);
                }
                default -> {
                    g.drawOval((int) x - 7, (int) y - 7, 14, 14);
                    g.fillOval((int) x - 2, (int) y - 2, 4, 4);
                }
            }
        }

        private void drawLegend(Graphics2D g, Color canvasBg, Color fg, Color pillBg, boolean dark) {
            int x = 12, y = 12, w = 280, h = 196;
            g.setColor(new Color(0, 0, 0, dark ? 70 : 18));
            g.fillRoundRect(x + 1, y + 2, w, h, 12, 12);
            g.setColor(pillBg);
            g.fillRoundRect(x, y, w, h, 12, 12);
            g.setColor(dark ? new Color(0x3A, 0x3F, 0x46) : new Color(0xE5, 0xE7, 0xEB));
            g.drawRoundRect(x, y, w, h, 12, 12);

            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
            g.setColor(fg);
            g.drawString("范围内 " + scopeCount() + " · 已分析 " + coveredCount(), x + 12, y + 20);

            NodeType[] types = {NodeType.ORIGIN, NodeType.INTERFACE, NodeType.RESOURCE,
                    NodeType.TECHNOLOGY, NodeType.FEATURE, NodeType.BACKEND_HYPOTHESIS,
                    NodeType.VULN_CLASS, NodeType.PARAMETER};
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            int rowY = y + 40;
            for (int i = 0; i < types.length; i++) {
                int col = i % 2;
                int row = i / 2;
                int tx = x + 12 + col * 132;
                int ty = rowY + row * 19;
                g.setColor(colorFor(types[i]));
                g.fillOval(tx, ty - 8, 9, 9);
                g.setColor(fg);
                g.drawString(typeLabel(types[i]), tx + 14, ty);
            }

            int sy = rowY + 4 * 19 + 6;
            g.setColor(new Color(16, 185, 129));
            g.fillOval(x + 12, sy - 8, 9, 9);
            g.setColor(fg);
            g.drawString("已分析（fact 锚定）", x + 26, sy);
            g.setColor(dark ? new Color(0x2B, 0x2F, 0x36) : Color.WHITE);
            g.fillOval(x + 12, sy + 11, 9, 9);
            g.setColor(new Color(0x9C, 0xA3, 0xAF));
            g.drawOval(x + 12, sy + 11, 9, 9);
            g.setColor(fg);
            g.drawString("未分析", x + 26, sy + 19);
            g.setColor(dark ? new Color(0x2B, 0x2F, 0x36) : Color.WHITE);
            g.fillOval(x + 84, sy + 11, 9, 9);
            g.setColor(new Color(0x9C, 0xA3, 0xAF));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{3f, 2f}, 0f));
            g.drawOval(x + 84, sy + 11, 9, 9);
            g.setStroke(new BasicStroke(1f));
            g.setColor(fg);
            g.drawString("推测", x + 98, sy + 19);

            g.setColor(dark ? new Color(0x8B, 0x92, 0x9B) : new Color(0x9C, 0xA3, 0xAF));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
            g.drawString("力导向布局，可拖拽节点、滚轮缩放；", x + 12, sy + 40);
            g.drawString("虚线圈「…」是被折叠的同类节点，点开可展开。", x + 12, sy + 56);
        }

        private void drawCenteredLines(Graphics2D g, String text, Color bg) {
            String[] lines = text.split("\\n");
            FontMetrics fm = g.getFontMetrics();
            int total = lines.length * fm.getHeight();
            int y = (getHeight() - total) / 2 + fm.getAscent();
            for (String line : lines) {
                int x = (getWidth() - fm.stringWidth(line)) / 2;
                g.setColor(bg);
                g.fillRect(x - 4, y - fm.getAscent(), fm.stringWidth(line) + 8, fm.getHeight());
                g.setColor(UIStyles.hintColor());
                g.drawString(line, x, y);
                y += fm.getHeight();
            }
        }

        private double luminance(Color c) {
            return 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        }
    }

    // ================= 力导向布局 =================

    /**
     * 归一到 [-1,1] 的力导向布局（Fruchterman-Reingold 变体）：
     * 不把坐标钳制到边界，仿真结束后按包围盒等比缩放，避免节点挤在画布四周。
     */
    private static final class ForceLayout {
        static Map<Long, Point2D.Double> layout(List<Node> nodes, List<Edge> edges) {
            int n = nodes.size();
            Map<Long, Point2D.Double> out = new HashMap<>();
            if (n == 0) return out;
            if (n == 1) {
                out.put(nodes.get(0).id(), new Point2D.Double(0, 0));
                return out;
            }
            Map<Long, Integer> index = new HashMap<>();
            for (int i = 0; i < n; i++) index.put(nodes.get(i).id(), i);
            double[] x = new double[n];
            double[] y = new double[n];
            double[] dx = new double[n];
            double[] dy = new double[n];
            java.util.Random random = new java.util.Random(42);
            for (int i = 0; i < n; i++) {
                double angle = 2 * Math.PI * i / n;
                x[i] = Math.cos(angle) * 0.5 + (random.nextDouble() - 0.5) * 0.02;
                y[i] = Math.sin(angle) * 0.5 + (random.nextDouble() - 0.5) * 0.02;
            }
            int[][] links = new int[edges.size()][2];
            int linkCount = 0;
            for (Edge e : edges) {
                Integer s = index.get(e.sourceNodeId());
                Integer t = index.get(e.targetNodeId());
                if (s != null && t != null && s != t) {
                    links[linkCount][0] = s;
                    links[linkCount][1] = t;
                    linkCount++;
                }
            }
            double k = Math.sqrt(4.0 / n);
            int iterations = n > 220 ? 80 : n > 120 ? 120 : 180;
            double temperature = 0.35;
            for (int iter = 0; iter < iterations; iter++) {
                Arrays.fill(dx, 0);
                Arrays.fill(dy, 0);
                for (int i = 0; i < n; i++) {
                    for (int j = i + 1; j < n; j++) {
                        double ddx = x[i] - x[j];
                        double ddy = y[i] - y[j];
                        double dist = Math.max(0.001, Math.hypot(ddx, ddy));
                        double force = k * k / dist;
                        double fx = ddx / dist * force;
                        double fy = ddy / dist * force;
                        dx[i] += fx; dy[i] += fy;
                        dx[j] -= fx; dy[j] -= fy;
                    }
                }
                for (int l = 0; l < linkCount; l++) {
                    int i = links[l][0], j = links[l][1];
                    double ddx = x[i] - x[j];
                    double ddy = y[i] - y[j];
                    double dist = Math.max(0.001, Math.hypot(ddx, ddy));
                    double force = dist * dist / k;
                    double fx = ddx / dist * force;
                    double fy = ddy / dist * force;
                    dx[i] -= fx; dy[i] -= fy;
                    dx[j] += fx; dy[j] += fy;
                }
                for (int i = 0; i < n; i++) {
                    // 轻微向心力，避免孤立节点被排斥到很远
                    dx[i] -= x[i] * 0.02;
                    dy[i] -= y[i] * 0.02;
                    double len = Math.max(0.001, Math.hypot(dx[i], dy[i]));
                    double step = Math.min(len, temperature);
                    x[i] += dx[i] / len * step;
                    y[i] += dy[i] / len * step;
                }
                temperature *= 0.96;
                if (temperature < 0.002) break;
            }
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
                minY = Math.min(minY, y[i]); maxY = Math.max(maxY, y[i]);
            }
            double cx = (minX + maxX) / 2;
            double cy = (minY + maxY) / 2;
            double span = Math.max(maxX - minX, maxY - minY);
            double norm = span < 1e-6 ? 1 : 2.0 / span;
            for (int i = 0; i < n; i++) {
                out.put(nodes.get(i).id(),
                        new Point2D.Double((x[i] - cx) * norm, (y[i] - cy) * norm));
            }
            return out;
        }
    }
}
