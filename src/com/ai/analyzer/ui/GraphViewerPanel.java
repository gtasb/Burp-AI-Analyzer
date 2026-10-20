package com.ai.analyzer.ui;

import com.ai.analyzer.graph.GraphStore;
import com.ai.analyzer.graph.GraphTypes.Edge;
import com.ai.analyzer.graph.GraphTypes.FactStatus;
import com.ai.analyzer.graph.GraphTypes.Node;
import com.ai.analyzer.graph.GraphTypes.NodeType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 知识图谱可视化面板：按 Origin(root) 查看当前图谱。
 *
 * <p>节点颜色区分类型，虚线边表示未确认的推测关系（INFERRED/REJECTED），
 * 点击节点在底部查看详情。SQLite 读取与力导向布局都在 SwingWorker 中执行，
 * 不阻塞 EDT。
 */
public class GraphViewerPanel extends JPanel {

    private static final int MAX_NODES = 500;
    private static final int MAX_EDGES = 2000;

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
        final int width = Math.max(canvas.getWidth(), 800);
        final int height = Math.max(canvas.getHeight(), 520);
        new SwingWorker<GraphData, Void>() {
            @Override
            protected GraphData doInBackground() {
                GraphStore store = GraphStore.getInstance();
                List<Node> nodes = store.listNodes(rootId, MAX_NODES);
                List<Edge> edges = store.listEdges(rootId, MAX_EDGES);
                Map<Long, Point2D.Double> positions = ForceLayout.layout(nodes, edges, width, height);
                return new GraphData(nodes, edges, positions);
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
                    canvas.setData(data.nodes(), data.edges(), data.positions());
                    statusLabel.setText("节点 " + data.nodes().size() + " / 边 " + data.edges().size());
                    detailArea.setText("点击节点查看详情");
                } catch (Exception ex) {
                    statusLabel.setText("加载失败: " + rootMessage(ex));
                }
            }
        }.execute();
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg;
    }

    private record GraphData(List<Node> nodes, List<Edge> edges, Map<Long, Point2D.Double> positions) {
    }

    // ================= Canvas =================

    private final class GraphCanvas extends JComponent {
        private List<Node> nodes = List.of();
        private List<Edge> edges = List.of();
        private Map<Long, Point2D.Double> positions = Map.of();
        private Map<Long, Integer> degrees = Map.of();
        private Node selected;
        private String emptyText = "选择站点后加载图谱";

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
            nodes = List.of();
            edges = List.of();
            positions = Map.of();
            degrees = Map.of();
            selected = null;
            emptyText = text;
            repaint();
        }

        void setData(List<Node> nodes, List<Edge> edges, Map<Long, Point2D.Double> positions) {
            this.nodes = nodes;
            this.edges = edges;
            this.positions = positions;
            Map<Long, Integer> deg = new HashMap<>();
            for (Edge e : edges) {
                deg.merge(e.sourceNodeId(), 1, Integer::sum);
                deg.merge(e.targetNodeId(), 1, Integer::sum);
            }
            this.degrees = deg;
            this.selected = null;
            repaint();
        }

        private Node nearestNode(int mx, int my) {
            Node best = null;
            double bestDist = 14;
            for (Node n : nodes) {
                Point2D.Double p = positions.get(n.id());
                if (p == null) continue;
                double d = Math.hypot(p.x - mx, p.y - my);
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

                if (nodes.isEmpty()) {
                    g.setColor(UIStyles.hintColor());
                    g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
                    drawCenteredLines(g, emptyText, bg);
                    return;
                }

                Color edgeObserved = new Color(150, 156, 166, 180);
                Color edgeInferred = new Color(190, 150, 60, 170);
                for (Edge e : edges) {
                    Point2D.Double a = positions.get(e.sourceNodeId());
                    Point2D.Double b = positions.get(e.targetNodeId());
                    if (a == null || b == null) continue;
                    boolean inferred = e.status() == FactStatus.INFERRED || e.status() == FactStatus.REJECTED;
                    g.setColor(inferred ? edgeInferred : edgeObserved);
                    if (inferred) {
                        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                                1f, new float[]{4f, 4f}, 0f));
                    } else {
                        g.setStroke(new BasicStroke(1.2f));
                    }
                    g.draw(new Line2D.Double(a, b));
                }

                Font labelFont = new Font("Microsoft YaHei", Font.PLAIN, 11);
                g.setFont(labelFont);
                FontMetrics fm = g.getFontMetrics();
                int labelsDrawn = 0;
                for (Node n : nodes) {
                    Point2D.Double p = positions.get(n.id());
                    if (p == null) continue;
                    int radius = radiusOf(n);
                    Color fill = colorFor(n.type());
                    g.setColor(fill);
                    g.fillOval((int) p.x - radius, (int) p.y - radius, radius * 2, radius * 2);
                    if (n.id() == (selected == null ? -1 : selected.id())) {
                        g.setColor(UIStyles.ACCENT);
                        g.setStroke(new BasicStroke(3f));
                    } else {
                        g.setColor(fill.darker());
                        g.setStroke(new BasicStroke(1f));
                    }
                    g.drawOval((int) p.x - radius, (int) p.y - radius, radius * 2, radius * 2);

                    if (labelsDrawn < 120 && shouldLabel(n)) {
                        String label = truncate(n.name(), 26);
                        int tw = fm.stringWidth(label);
                        int lx = (int) p.x - tw / 2;
                        int ly = (int) p.y + radius + fm.getAscent() + 4;
                        g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 210));
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

        private boolean shouldLabel(Node n) {
            int degree = degrees.getOrDefault(n.id(), 0);
            return switch (n.type()) {
                case ORIGIN, INTERFACE, TECHNOLOGY, MODULE, SERVICE, VULNERABILITY -> true;
                default -> degree >= 3;
            };
        }

        private int radiusOf(Node n) {
            int degree = degrees.getOrDefault(n.id(), 0);
            return 6 + Math.min(6, degree);
        }
    }

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

    // ================= 力导向布局 =================

    /**
     * Fruchterman-Reingold 布局。节点数受 MAX_NODES 限制，O(n²) 排斥在后台线程执行。
     * 结果坐标限制在带边距的画布范围内。
     */
    private static final class ForceLayout {
        private static final double MARGIN = 40;

        static Map<Long, Point2D.Double> layout(List<Node> nodes, List<Edge> edges, int width, int height) {
            int n = nodes.size();
            Map<Long, Point2D.Double> out = new HashMap<>();
            if (n == 0) return out;
            if (n == 1) {
                out.put(nodes.get(0).id(), new Point2D.Double(width / 2.0, height / 2.0));
                return out;
            }

            double[] x = new double[n];
            double[] y = new double[n];
            double[] dispX = new double[n];
            double[] dispY = new double[n];
            Map<Long, Integer> index = new HashMap<>();
            Random random = new Random(42);
            double radius = Math.min(width, height) / 2.0 - MARGIN;
            double cx = width / 2.0;
            double cy = height / 2.0;
            for (int i = 0; i < n; i++) {
                index.put(nodes.get(i).id(), i);
                double angle = 2 * Math.PI * i / n;
                x[i] = cx + radius * Math.cos(angle) + random.nextDouble() * 4;
                y[i] = cy + radius * Math.sin(angle) + random.nextDouble() * 4;
            }

            double area = Math.max(1, (width - 2 * MARGIN)) * Math.max(1, (height - 2 * MARGIN));
            double k = Math.sqrt(area / n);
            int iterations = n > 300 ? 60 : n > 150 ? 100 : 160;
            double temperature = Math.min(width, height) / 10.0;

            int[][] endpoints = new int[edges.size()][2];
            int edgeCount = 0;
            for (Edge e : edges) {
                Integer s = index.get(e.sourceNodeId());
                Integer t = index.get(e.targetNodeId());
                if (s != null && t != null) {
                    endpoints[edgeCount][0] = s;
                    endpoints[edgeCount][1] = t;
                    edgeCount++;
                }
            }

            for (int iter = 0; iter < iterations; iter++) {
                java.util.Arrays.fill(dispX, 0);
                java.util.Arrays.fill(dispY, 0);
                // 排斥力
                for (int i = 0; i < n; i++) {
                    for (int j = i + 1; j < n; j++) {
                        double dx = x[i] - x[j];
                        double dy = y[i] - y[j];
                        double dist = Math.max(0.01, Math.hypot(dx, dy));
                        double force = k * k / dist;
                        double fx = dx / dist * force;
                        double fy = dy / dist * force;
                        dispX[i] += fx;
                        dispY[i] += fy;
                        dispX[j] -= fx;
                        dispY[j] -= fy;
                    }
                }
                // 边吸引力
                for (int e = 0; e < edgeCount; e++) {
                    int i = endpoints[e][0];
                    int j = endpoints[e][1];
                    double dx = x[i] - x[j];
                    double dy = y[i] - y[j];
                    double dist = Math.max(0.01, Math.hypot(dx, dy));
                    double force = dist * dist / k;
                    double fx = dx / dist * force;
                    double fy = dy / dist * force;
                    dispX[i] -= fx;
                    dispY[i] -= fy;
                    dispX[j] += fx;
                    dispY[j] += fy;
                }
                // 位移 + 边界约束
                for (int i = 0; i < n; i++) {
                    double len = Math.max(0.01, Math.hypot(dispX[i], dispY[i]));
                    double step = Math.min(len, temperature);
                    x[i] = clamp(x[i] + dispX[i] / len * step, MARGIN, width - MARGIN);
                    y[i] = clamp(y[i] + dispY[i] / len * step, MARGIN, height - MARGIN);
                }
                temperature *= 0.95;
                if (temperature < 0.5) break;
            }

            for (int i = 0; i < n; i++) {
                out.put(nodes.get(i).id(), new Point2D.Double(x[i], y[i]));
            }
            return out;
        }

        private static double clamp(double v, double min, double max) {
            return v < min ? min : Math.min(v, max);
        }
    }
}
