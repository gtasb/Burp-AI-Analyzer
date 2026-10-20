package com.ai.analyzer.ui;

import com.ai.analyzer.graph.GraphStore;
import com.ai.analyzer.graph.GraphTypes.Edge;
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
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱可视化面板：按 Origin(root) 查看当前图谱。
 *
 * <p>布局采用确定性的放射状分层：Origin 在中心、接口在内环、参数/模块/技术在外环，
 * 同一层按父节点角度排序后均匀分布。相比力导向布局，层级关系清晰、不会把节点挤到
 * 画布边缘。节点颜色区分类型，虚线边表示未确认的推测关系；点击节点查看详情，
 * 可导出为自包含的交互式 HTML。
 */
public class GraphViewerPanel extends JPanel {

    private static final int MAX_NODES = 500;
    private static final int MAX_EDGES = 2000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final JComboBox<String> rootCombo = new JComboBox<>();
    private final JLabel statusLabel = new JLabel("就绪");
    private final JTextArea detailArea = new JTextArea(4, 20);
    private final GraphCanvas canvas = new GraphCanvas();

    private boolean updatingRoots;
    private int loadGeneration;

    public GraphViewerPanel() {
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
        detailArea.setText("点击节点查看详情");
        JScrollPane detailPane = new JScrollPane(detailArea);
        detailPane.setPreferredSize(new Dimension(100, 100));
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
                Map<Long, Point2D.Double> positions = RadialLayout.layout(nodes, edges);
                return new GraphData(rootId, nodes, edges, positions);
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
                    statusLabel.setText("节点 " + data.nodes().size() + " / 边 " + data.edges().size());
                    detailArea.setText("点击节点查看详情");
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
        chooser.setSelectedFile(new File("knowledge-graph-" + host + ".html"));
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

    private static String buildHtml(GraphData data) {
        int width = 1400;
        int height = 900;
        int pad = 70;
        double scale = Math.min((width - 2.0 * pad) / 2.0, (height - 2.0 * pad) / 2.0);

        Map<Long, Integer> degrees = canvasDegrees(data.edges());
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

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>知识图谱 - ")
                .append(html(data.rootId())).append("</title><style>")
                .append("body{margin:0;font-family:'Microsoft YaHei',sans-serif;display:flex;height:100vh;background:#fff;color:#222}")
                .append("#graph{flex:1;min-width:0;background:#fafbfc}")
                .append("#side{width:340px;border-left:1px solid #ddd;padding:14px;overflow:auto;box-sizing:border-box}")
                .append("#side h3{margin-top:0;word-break:break-all;font-size:14px}")
                .append(".edge{stroke:#9aa0a6;stroke-width:1}")
                .append(".inferred{stroke:#b97a1f;stroke-dasharray:5 4}")
                .append(".node{cursor:pointer}")
                .append(".node:hover{stroke:#2d6cdf;stroke-width:3}")
                .append("text.label{font-size:11px;fill:#333;paint-order:stroke;stroke:#fafbfc;stroke-width:3px}")
                .append(".legend div{margin:4px 0;font-size:12px}")
                .append(".dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px}")
                .append("</style></head><body>");

        sb.append("<svg id=\"graph\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" xmlns=\"http://www.w3.org/2000/svg\">");
        sb.append("<g id=\"edges\">");
        for (Edge e : data.edges()) {
            Point2D.Double a = data.positions().get(e.sourceNodeId());
            Point2D.Double b = data.positions().get(e.targetNodeId());
            if (a == null || b == null) continue;
            boolean inferred = e.status() == FactStatus.INFERRED || e.status() == FactStatus.REJECTED;
            sb.append("<line class=\"edge").append(inferred ? " inferred" : "").append("\" x1=\"")
                    .append(fmt(width / 2.0 + a.x * scale)).append("\" y1=\"").append(fmt(height / 2.0 + a.y * scale))
                    .append("\" x2=\"").append(fmt(width / 2.0 + b.x * scale)).append("\" y2=\"")
                    .append(fmt(height / 2.0 + b.y * scale)).append("\"/>");
        }
        sb.append("</g><g id=\"nodes\">");
        for (Node n : data.nodes()) {
            Point2D.Double p = data.positions().get(n.id());
            if (p == null) continue;
            double x = width / 2.0 + p.x * scale;
            double y = height / 2.0 + p.y * scale;
            int degree = degrees.getOrDefault(n.id(), 0);
            int radius = 6 + Math.min(6, degree);
            sb.append("<circle class=\"node\" data-id=\"").append(n.id()).append("\" cx=\"").append(fmt(x))
                    .append("\" cy=\"").append(fmt(y)).append("\" r=\"").append(radius)
                    .append("\" fill=\"").append(hex(colorFor(n.type()))).append("\" stroke=\"")
                    .append(hex(colorFor(n.type()).darker())).append("\"><title>")
                    .append(html(typeLabel(n.type()) + "  " + n.name())).append("</title></circle>");
            if (shouldLabel(n, degree)) {
                double dirX = p.x;
                double dirY = p.y;
                double len = Math.hypot(dirX, dirY);
                double ox = len < 0.01 ? 0 : dirX / len * (radius + 10);
                double oy = len < 0.01 ? radius + 16 : dirY / len * (radius + 10) + 4;
                sb.append("<text class=\"label\" x=\"").append(fmt(x + ox)).append("\" y=\"").append(fmt(y + oy))
                        .append("\" text-anchor=\"middle\">").append(html(truncate(n.name(), 26))).append("</text>");
            }
        }
        sb.append("</g></svg>");

        sb.append("<div id=\"side\"><h3>").append(html(data.rootId())).append("</h3><div>节点 ")
                .append(data.nodes().size()).append(" / 边 ").append(data.edges().size()).append("</div>")
                .append("<div id=\"detail\" style=\"margin-top:12px;color:#666\">点击节点查看详情</div>")
                .append("<div class=\"legend\" style=\"margin-top:18px\">");
        for (NodeType t : NodeType.values()) {
            sb.append("<div><span class=\"dot\" style=\"background:").append(hex(colorFor(t))).append("\"></span>")
                    .append(html(typeLabel(t))).append("</div>");
        }
        sb.append("</div></div>");

        sb.append("<script>const NODES=").append(GSON.toJson(nodeJson)).append(";")
                .append("document.querySelectorAll('circle.node').forEach(c=>{c.addEventListener('click',()=>{")
                .append("const n=NODES[c.dataset.id];if(!n)return;const d=document.getElementById('detail');")
                .append("d.innerHTML='<b>'+n.type+'</b> '+esc(n.name)+'<br>status='+n.status+'  confidence='+n.confidence")
                .append("+'<br>canonical: '+esc(n.canonical)+'<br>source: '+esc(n.source)+'<br>updated: '+esc(n.updated)")
                .append("+'<br>properties: '+esc(n.properties);});});")
                .append("function esc(s){return String(s==null?'':s).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}")
                .append("</script></body></html>");
        return sb.toString();
    }

    private static Map<Long, Integer> canvasDegrees(List<Edge> edges) {
        Map<Long, Integer> deg = new HashMap<>();
        for (Edge e : edges) {
            deg.merge(e.sourceNodeId(), 1, Integer::sum);
            deg.merge(e.targetNodeId(), 1, Integer::sum);
        }
        return deg;
    }

    private static boolean shouldLabel(Node n, int degree) {
        return switch (n.type()) {
            case ORIGIN, INTERFACE, TECHNOLOGY, MODULE, SERVICE, VULNERABILITY -> true;
            default -> degree >= 3;
        };
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
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

    private record GraphData(String rootId, List<Node> nodes, List<Edge> edges,
                             Map<Long, Point2D.Double> positions) {
    }

    // ================= Canvas =================

    private final class GraphCanvas extends JComponent {
        private GraphData data;
        private Map<Long, Integer> degrees = Map.of();
        private Node selected;
        private String emptyText = "选择站点后加载图谱";
        private double lastScale = 1;

        GraphCanvas() {
            setOpaque(true);
            setMinimumSize(new Dimension(400, 300));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Node hit = nearestNode(e.getX(), e.getY());
                    selected = hit;
                    repaint();
                    detailArea.setText(hit == null ? "点击节点查看详情" : describe(hit));
                }
            });
        }

        void showEmpty(String text) {
            data = null;
            degrees = Map.of();
            selected = null;
            emptyText = text;
            repaint();
        }

        void setData(GraphData data) {
            this.data = data;
            this.degrees = canvasDegrees(data.edges());
            this.selected = null;
            repaint();
        }

        /** 世界坐标（归一化 [-1,1]）→ 屏幕坐标，居中且保持纵横比。 */
        private Point2D.Double toScreen(double wx, double wy) {
            return new Point2D.Double(getWidth() / 2.0 + wx * lastScale,
                    getHeight() / 2.0 + wy * lastScale);
        }

        private Node nearestNode(int mx, int my) {
            if (data == null) return null;
            Node best = null;
            double bestDist = 14;
            for (Node n : data.nodes()) {
                Point2D.Double p = data.positions().get(n.id());
                if (p == null) continue;
                Point2D.Double s = toScreen(p.x, p.y);
                double d = Math.hypot(s.x - mx, s.y - my);
                if (d < bestDist) {
                    bestDist = d;
                    best = n;
                }
            }
            return best;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = UIManager.getColor("Panel.background");
                if (bg == null) bg = Color.WHITE;
                Color fg = UIManager.getColor("Label.foreground");
                if (fg == null) fg = Color.BLACK;
                g.setColor(bg);
                g.fillRect(0, 0, getWidth(), getHeight());

                if (data == null || data.nodes().isEmpty()) {
                    g.setColor(UIStyles.hintColor());
                    g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
                    drawCenteredLines(g, emptyText, bg);
                    return;
                }

                // 自适应缩放：布局坐标固定为 [-1,1]，这里按当前画布大小等比缩放并居中
                lastScale = Math.max(20, Math.min(getWidth(), getHeight()) / 2.0 - 40);

                Color edgeObserved = new Color(150, 156, 166, 180);
                Color edgeInferred = new Color(190, 150, 60, 170);
                for (Edge e : data.edges()) {
                    Point2D.Double a = data.positions().get(e.sourceNodeId());
                    Point2D.Double b = data.positions().get(e.targetNodeId());
                    if (a == null || b == null) continue;
                    Point2D.Double sa = toScreen(a.x, a.y);
                    Point2D.Double sb = toScreen(b.x, b.y);
                    boolean inferred = e.status() == FactStatus.INFERRED || e.status() == FactStatus.REJECTED;
                    g.setColor(inferred ? edgeInferred : edgeObserved);
                    if (inferred) {
                        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                                1f, new float[]{4f, 4f}, 0f));
                    } else {
                        g.setStroke(new BasicStroke(1.2f));
                    }
                    g.draw(new Line2D.Double(sa, sb));
                }

                Font labelFont = new Font("Microsoft YaHei", Font.PLAIN, 11);
                g.setFont(labelFont);
                FontMetrics fm = g.getFontMetrics();
                int labelsDrawn = 0;
                for (Node n : data.nodes()) {
                    Point2D.Double p = data.positions().get(n.id());
                    if (p == null) continue;
                    Point2D.Double s = toScreen(p.x, p.y);
                    int degree = degrees.getOrDefault(n.id(), 0);
                    int radius = 6 + Math.min(6, degree);
                    Color fill = colorFor(n.type());
                    g.setColor(fill);
                    g.fillOval((int) s.x - radius, (int) s.y - radius, radius * 2, radius * 2);
                    if (selected != null && selected.id() == n.id()) {
                        g.setColor(UIStyles.ACCENT);
                        g.setStroke(new BasicStroke(3f));
                    } else {
                        g.setColor(fill.darker());
                        g.setStroke(new BasicStroke(1f));
                    }
                    g.drawOval((int) s.x - radius, (int) s.y - radius, radius * 2, radius * 2);

                    if (labelsDrawn < 160 && shouldLabel(n, degree)) {
                        String label = truncate(n.name(), 26);
                        int tw = fm.stringWidth(label);
                        // 标签沿半径向外偏移，避免压在中心和连线上
                        double len = Math.hypot(p.x, p.y);
                        double ox = len < 0.01 ? 0 : p.x / len * (radius + 12);
                        double oy = len < 0.01 ? radius + 16 : p.y / len * (radius + 12) + 4;
                        int lx = (int) (s.x + ox - tw / 2.0);
                        int ly = (int) (s.y + oy);
                        g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 215));
                        g.fillRoundRect(lx - 3, ly - fm.getAscent(), tw + 6, fm.getHeight(), 6, 6);
                        g.setColor(fg);
                        g.drawString(label, lx, ly);
                        labelsDrawn++;
                    }
                }

                drawLegend(g, bg, fg);
            } finally {
                g.dispose();
            }
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

        private void drawLegend(Graphics2D g, Color bg, Color fg) {
            NodeType[] types = {NodeType.ORIGIN, NodeType.INTERFACE, NodeType.PARAMETER,
                    NodeType.TECHNOLOGY, NodeType.MODULE, NodeType.VULNERABILITY};
            Font font = new Font("Microsoft YaHei", Font.PLAIN, 11);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int lineH = fm.getHeight() + 2;
            int w = 120;
            int h = types.length * lineH + 8;
            int x = 10;
            int y = 10;
            g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 210));
            g.fillRoundRect(x, y, w, h, 8, 8);
            g.setColor(new Color(180, 184, 190));
            g.drawRoundRect(x, y, w, h, 8, 8);
            int ty = y + fm.getAscent() + 4;
            for (NodeType t : types) {
                g.setColor(colorFor(t));
                g.fillOval(x + 8, ty - 6, 10, 10);
                g.setColor(fg);
                g.drawString(typeLabel(t), x + 24, ty);
                ty += lineH;
            }
        }
    }

    // ================= 放射状分层布局 =================

    /**
     * 确定性放射状布局（返回归一化到 [-1,1] 的坐标）：
     * <ul>
     *   <li>ORIGIN（无则取度数最大的节点）作为中心；</li>
     *   <li>BFS 层数决定半径：接口内环、参数/模块/技术外环；</li>
     *   <li>同一层按父节点角度排序后均匀分布，保证子节点聚在父节点附近；</li>
     *   <li>与中心不可达的节点放到最外环。</li>
     * </ul>
     */
    private static final class RadialLayout {
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
            List<List<Integer>> adjacency = new ArrayList<>(n);
            for (int i = 0; i < n; i++) adjacency.add(new ArrayList<>());
            int[] degree = new int[n];
            for (Edge e : edges) {
                Integer s = index.get(e.sourceNodeId());
                Integer t = index.get(e.targetNodeId());
                if (s == null || t == null) continue;
                adjacency.get(s).add(t);
                adjacency.get(t).add(s);
                degree[s]++;
                degree[t]++;
            }

            List<Integer> roots = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (nodes.get(i).type() == NodeType.ORIGIN) roots.add(i);
            }
            if (roots.isEmpty()) {
                int best = 0;
                for (int i = 1; i < n; i++) {
                    if (degree[i] > degree[best]) best = i;
                }
                roots.add(best);
            }

            int[] depth = new int[n];
            int[] parent = new int[n];
            Arrays.fill(depth, -1);
            Arrays.fill(parent, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int r : roots) {
                depth[r] = 0;
                queue.add(r);
            }
            int maxDepth = 0;
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                for (int nb : adjacency.get(cur)) {
                    if (depth[nb] < 0) {
                        depth[nb] = depth[cur] + 1;
                        parent[nb] = cur;
                        maxDepth = Math.max(maxDepth, depth[nb]);
                        queue.add(nb);
                    }
                }
            }
            int unreachableDepth = maxDepth + 1;
            for (int i = 0; i < n; i++) {
                if (depth[i] < 0) depth[i] = unreachableDepth;
            }

            int levels = unreachableDepth + 1;
            List<List<Integer>> groups = new ArrayList<>(levels);
            for (int d = 0; d < levels; d++) groups.add(new ArrayList<>());
            for (int i = 0; i < n; i++) groups.get(depth[i]).add(i);

            // 子节点列表（BFS 树）
            List<List<Integer>> children = new ArrayList<>(n);
            for (int i = 0; i < n; i++) children.add(new ArrayList<>());
            for (int i = 0; i < n; i++) {
                if (parent[i] >= 0) children.get(parent[i]).add(i);
            }
            // 子树权重：按子节点数量分配角度跨度，使同一父节点的子节点聚在一起
            int[] weight = new int[n];
            Arrays.fill(weight, 1);
            for (int d = maxDepth; d >= 1; d--) {
                for (int node : groups.get(d)) {
                    if (parent[node] >= 0) weight[parent[node]] += weight[node];
                }
            }

            double[] angle = new double[n];
            double[] spanStart = new double[n];
            double[] spanSize = new double[n];
            double twoPi = Math.PI * 2;
            List<Integer> rootGroup = groups.get(0);
            double rootSpan = twoPi / Math.max(1, rootGroup.size());
            for (int i = 0; i < rootGroup.size(); i++) {
                int r = rootGroup.get(i);
                spanStart[r] = -Math.PI + rootSpan * i;
                spanSize[r] = rootSpan;
                angle[r] = spanStart[r] + rootSpan / 2.0;
            }
            // 自顶向下分配角度区间：父节点的区间按子树权重分给子节点
            for (int d = 1; d < levels; d++) {
                for (int p : groups.get(d - 1)) {
                    List<Integer> kids = children.get(p);
                    if (kids.isEmpty()) continue;
                    int totalWeight = 0;
                    for (int c : kids) totalWeight += weight[c];
                    if (totalWeight <= 0) totalWeight = kids.size();
                    double cursor = spanStart[p];
                    for (int c : kids) {
                        double sz = spanSize[p] * weight[c] / totalWeight;
                        spanStart[c] = cursor;
                        spanSize[c] = sz;
                        angle[c] = cursor + sz / 2.0;
                        cursor += sz;
                    }
                }
            }
            // 与中心不可达的节点：最外环均分
            List<Integer> unreachable = groups.get(unreachableDepth);
            for (int i = 0; i < unreachable.size(); i++) {
                angle[unreachable.get(i)] = -Math.PI + twoPi * i / Math.max(1, unreachable.size());
            }

            double maxRadius = Math.max(1, unreachableDepth);
            for (int i = 0; i < n; i++) {
                int d = depth[i];
                double radius = d == 0 ? (rootGroup.size() > 1 ? 0.06 : 0) : 0.18 + 0.82 * d / maxRadius;
                double a = angle[i];
                double x = Math.cos(a) * radius;
                double y = Math.sin(a) * radius;
                out.put(nodes.get(i).id(), new Point2D.Double(x, y));
            }

            // 归一化到 [-1,1]（保留纵横比，让画布等比缩放）
            double maxAbs = 1e-9;
            for (Point2D.Double p : out.values()) {
                maxAbs = Math.max(maxAbs, Math.max(Math.abs(p.x), Math.abs(p.y)));
            }
            for (Map.Entry<Long, Point2D.Double> e : out.entrySet()) {
                e.setValue(new Point2D.Double(e.getValue().x / maxAbs, e.getValue().y / maxAbs));
            }
            return out;
        }
    }

    // ================= 展示辅助 =================

    private static Color colorFor(NodeType type) {
        return switch (type) {
            case ORIGIN -> new Color(47, 108, 223);
            case INTERFACE -> new Color(32, 150, 130);
            case PARAMETER -> new Color(140, 146, 158);
            case FUNCTION, BUSINESS_LOGIC -> new Color(150, 100, 200);
            case BUSINESS_ENTITY -> new Color(205, 120, 50);
            case TECHNOLOGY, VERSION -> new Color(150, 120, 40);
            case MODULE -> new Color(70, 150, 90);
            case SERVICE -> new Color(60, 130, 180);
            case VULNERABILITY -> new Color(200, 60, 60);
        };
    }

    private static String typeLabel(NodeType type) {
        return switch (type) {
            case ORIGIN -> "Origin";
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
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    private static String describe(Node n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.type()).append("  ").append(n.name()).append('\n');
        sb.append("id=").append(n.id())
                .append("  status=").append(n.status())
                .append("  confidence=").append(String.format("%.2f", n.confidence())).append('\n');
        sb.append("canonical: ").append(n.canonicalKey()).append('\n');
        sb.append("source: ").append(n.sourceType()).append('/').append(n.sourceId()).append('\n');
        sb.append("updated: ").append(n.updatedAt());
        if (n.properties() != null && !n.properties().isEmpty()) {
            String props = n.properties().toString();
            sb.append("\nproperties: ").append(props.length() > 300 ? props.substring(0, 300) + "…" : props);
        }
        return sb.toString();
    }
}
