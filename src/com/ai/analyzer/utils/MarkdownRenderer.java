package com.ai.analyzer.utils;
import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.text.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

public class MarkdownRenderer {

    private static boolean isDarkTheme(JTextPane textPane) {
        Color bg = textPane.getBackground();
        if (bg == null) bg = UIManager.getColor("TextArea.background");
        if (bg == null) return false;
        double lum = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        return lum < 128;
    }

    // ---- 浅色主题 ----
    private static final Color L_REGULAR_FG = new Color(51, 51, 51);
    private static final Color L_HEADER1 = new Color(192, 57, 43);
    private static final Color L_HEADER2 = new Color(41, 128, 185);
    private static final Color L_HEADER3 = new Color(44, 62, 80);
    private static final Color L_CODE_FG = new Color(192, 57, 43);
    private static final Color L_CODE_BG = new Color(245, 245, 245);
    private static final Color L_CODE_BLOCK_BG = new Color(243, 244, 246);
    private static final Color L_CODE_BLOCK_FG = new Color(40, 40, 40);
    private static final Color L_BOLD = new Color(44, 62, 80);
    private static final Color L_ITALIC = new Color(127, 140, 141);
    private static final Color L_LIST = new Color(44, 62, 80);
    private static final Color L_LINK = new Color(41, 128, 185);
    private static final Color L_TABLE_HEADER_BG = new Color(229, 236, 246);
    private static final Color L_TABLE_HEADER_FG = new Color(44, 62, 80);
    private static final Color L_TABLE_BORDER = new Color(189, 195, 199);
    private static final Color L_TABLE_EVEN_BG = Color.WHITE;
    private static final Color L_TABLE_ODD_BG = new Color(249, 250, 251);
    private static final Color L_TABLE_CELL_FG = new Color(51, 51, 51);
    private static final Color L_TOOL_BLOCK_BG = new Color(243, 244, 246);
    private static final Color L_TOOL_NAME = new Color(79, 70, 229);
    private static final Color L_TOOL_PARAM_KEY = new Color(107, 114, 128);
    private static final Color L_TOOL_PARAM_VAL = new Color(55, 65, 81);
    private static final Color L_TOOL_ICON = new Color(16, 185, 129);

    // ---- 深色主题 ----
    private static final Color D_REGULAR_FG = new Color(210, 210, 210);
    private static final Color D_HEADER1 = new Color(255, 100, 80);
    private static final Color D_HEADER2 = new Color(100, 180, 255);
    private static final Color D_HEADER3 = new Color(180, 195, 210);
    private static final Color D_CODE_FG = new Color(255, 120, 85);
    private static final Color D_CODE_BG = new Color(55, 55, 60);
    private static final Color D_CODE_BLOCK_BG = new Color(40, 42, 48);
    private static final Color D_CODE_BLOCK_FG = new Color(200, 210, 215);
    private static final Color D_BOLD = new Color(220, 225, 230);
    private static final Color D_ITALIC = new Color(160, 170, 175);
    private static final Color D_LIST = new Color(200, 210, 215);
    private static final Color D_LINK = new Color(100, 180, 255);
    private static final Color D_TABLE_HEADER_BG = new Color(50, 58, 70);
    private static final Color D_TABLE_HEADER_FG = new Color(200, 210, 220);
    private static final Color D_TABLE_BORDER = new Color(80, 88, 100);
    private static final Color D_TABLE_EVEN_BG = new Color(35, 38, 43);
    private static final Color D_TABLE_ODD_BG = new Color(42, 46, 52);
    private static final Color D_TABLE_CELL_FG = new Color(200, 205, 210);
    private static final Color D_TOOL_BLOCK_BG = new Color(40, 44, 52);
    private static final Color D_TOOL_NAME = new Color(140, 130, 255);
    private static final Color D_TOOL_PARAM_KEY = new Color(140, 150, 165);
    private static final Color D_TOOL_PARAM_VAL = new Color(185, 195, 205);
    private static final Color D_TOOL_ICON = new Color(50, 210, 160);

    private static class Theme {
        final Color regularFg, header1, header2, header3, codeFg, codeBg;
        final Color codeBlockBg, codeBlockFg, bold, italic, list, link;
        final Color tableHeaderBg, tableHeaderFg, tableBorder, tableEvenBg, tableOddBg, tableCellFg;
        final Color toolBlockBg, toolName, toolParamKey, toolParamVal, toolIcon;
        final Color regularBg;

        Theme(boolean dark, Color paneBg) {
            this.regularBg = paneBg;
            if (dark) {
                regularFg = D_REGULAR_FG; header1 = D_HEADER1; header2 = D_HEADER2; header3 = D_HEADER3;
                codeFg = D_CODE_FG; codeBg = D_CODE_BG; codeBlockBg = D_CODE_BLOCK_BG; codeBlockFg = D_CODE_BLOCK_FG;
                bold = D_BOLD; italic = D_ITALIC; list = D_LIST; link = D_LINK;
                tableHeaderBg = D_TABLE_HEADER_BG; tableHeaderFg = D_TABLE_HEADER_FG;
                tableBorder = D_TABLE_BORDER; tableEvenBg = D_TABLE_EVEN_BG; tableOddBg = D_TABLE_ODD_BG;
                tableCellFg = D_TABLE_CELL_FG;
                toolBlockBg = D_TOOL_BLOCK_BG; toolName = D_TOOL_NAME;
                toolParamKey = D_TOOL_PARAM_KEY; toolParamVal = D_TOOL_PARAM_VAL; toolIcon = D_TOOL_ICON;
            } else {
                regularFg = L_REGULAR_FG; header1 = L_HEADER1; header2 = L_HEADER2; header3 = L_HEADER3;
                codeFg = L_CODE_FG; codeBg = L_CODE_BG; codeBlockBg = L_CODE_BLOCK_BG; codeBlockFg = L_CODE_BLOCK_FG;
                bold = L_BOLD; italic = L_ITALIC; list = L_LIST; link = L_LINK;
                tableHeaderBg = L_TABLE_HEADER_BG; tableHeaderFg = L_TABLE_HEADER_FG;
                tableBorder = L_TABLE_BORDER; tableEvenBg = L_TABLE_EVEN_BG; tableOddBg = L_TABLE_ODD_BG;
                tableCellFg = L_TABLE_CELL_FG;
                toolBlockBg = L_TOOL_BLOCK_BG; toolName = L_TOOL_NAME;
                toolParamKey = L_TOOL_PARAM_KEY; toolParamVal = L_TOOL_PARAM_VAL; toolIcon = L_TOOL_ICON;
            }
        }
    }

    private static final ThreadLocal<Theme> currentTheme = new ThreadLocal<>();

    private static Theme resolveTheme(JTextPane textPane) {
        boolean dark = isDarkTheme(textPane);
        Color bg = textPane.getBackground();
        if (bg == null) bg = dark ? new Color(30, 30, 30) : Color.WHITE;
        Theme t = new Theme(dark, bg);
        currentTheme.set(t);
        return t;
    }

    private static Theme theme() {
        Theme t = currentTheme.get();
        return t != null ? t : new Theme(false, Color.WHITE);
    }

    /**
     * 渲染Markdown到JTextPane的末尾（不清空现有内容）
     */
    public static void appendMarkdown(JTextPane textPane, String markdown) {
        if (textPane == null) {
            throw new IllegalArgumentException("textPane cannot be null");
        }
        if (markdown == null) {
            markdown = "";
        }
        Theme t = resolveTheme(textPane);
        StyledDocument doc = textPane.getStyledDocument();
        Style[] styles = buildStyles(doc, t);

        try {
            renderWithToolBlocks(doc, markdown, styles[0], styles[1], styles[2], styles[3], styles[4], styles[5], styles[6], styles[7], styles[8]);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private static Style[] buildStyles(StyledDocument doc, Theme t) {
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        Style regular = getOrCreateStyle(doc, "regular", defaultStyle);
        StyleConstants.setFontFamily(regular, "Microsoft YaHei");
        StyleConstants.setFontSize(regular, 13);
        StyleConstants.setForeground(regular, t.regularFg);
        StyleConstants.setBackground(regular, t.regularBg);
        StyleConstants.setLineSpacing(regular, 0.3f);

        Style header1 = getOrCreateStyle(doc, "header1", regular);
        StyleConstants.setFontSize(header1, 15);
        StyleConstants.setBold(header1, true);
        StyleConstants.setForeground(header1, t.header1);

        Style header2 = getOrCreateStyle(doc, "header2", regular);
        StyleConstants.setFontSize(header2, 14);
        StyleConstants.setBold(header2, true);
        StyleConstants.setForeground(header2, t.header2);

        Style header3 = getOrCreateStyle(doc, "header3", regular);
        StyleConstants.setFontSize(header3, 13);
        StyleConstants.setBold(header3, true);
        StyleConstants.setForeground(header3, t.header3);

        Style bold = getOrCreateStyle(doc, "bold", regular);
        StyleConstants.setBold(bold, true);
        StyleConstants.setForeground(bold, t.bold);

        Style italic = getOrCreateStyle(doc, "italic", regular);
        StyleConstants.setItalic(italic, true);
        StyleConstants.setForeground(italic, t.italic);

        Style code = getOrCreateStyle(doc, "code", regular);
        StyleConstants.setFontFamily(code, "Consolas");
        StyleConstants.setFontSize(code, 12);
        StyleConstants.setBackground(code, t.codeBg);
        StyleConstants.setForeground(code, t.codeFg);

        Style list = getOrCreateStyle(doc, "list", regular);
        StyleConstants.setForeground(list, t.list);

        Style link = getOrCreateStyle(doc, "link", regular);
        StyleConstants.setForeground(link, t.link);
        StyleConstants.setUnderline(link, true);

        return new Style[]{regular, bold, italic, code, link, header1, header2, header3, list};
    }
    
    /**
     * 预处理并渲染文本，先提取工具块单独渲染，避免 Markdown 解析器破坏
     */
    private static void renderWithToolBlocks(StyledDocument doc, String markdown, 
            Style regular, Style bold, Style italic, Style code, Style link,
            Style header1, Style header2, Style header3, Style list) throws BadLocationException {
        
        if (markdown == null || markdown.isEmpty()) return;
        
        List<Extension> extensions = Collections.singletonList(TablesExtension.create());
        Parser parser = Parser.builder().extensions(extensions).build();
        int pos = 0;
        
        while (pos < markdown.length()) {
            // 查找 [TOOL_BLOCK] 和 [TOOL] 标记
            int toolBlockStart = markdown.indexOf("[TOOL_BLOCK]", pos);
            int toolStart = markdown.indexOf("[TOOL]", pos);
            
            // 确定哪个先出现
            int nextStart = -1;
            boolean isBlockFormat = false;
            
            if (toolBlockStart >= 0 && (toolStart < 0 || toolBlockStart < toolStart)) {
                nextStart = toolBlockStart;
                isBlockFormat = true;
            } else if (toolStart >= 0) {
                nextStart = toolStart;
                isBlockFormat = false;
            }
            
            if (nextStart < 0) {
                // 没有更多工具块，渲染剩余的 Markdown
                if (pos < markdown.length()) {
                    String remaining = markdown.substring(pos);
                    if (!remaining.isEmpty()) {
                        Node document = parser.parse(remaining);
                        renderNode(doc, document, regular, bold, italic, code, link, header1, header2, header3, list);
                    }
                }
                break;
            }
            
            // 先渲染工具块之前的 Markdown 内容
            if (nextStart > pos && nextStart <= markdown.length()) {
                String beforeTool = markdown.substring(pos, nextStart);
                if (!beforeTool.isEmpty()) {
                    Node document = parser.parse(beforeTool);
                    renderNode(doc, document, regular, bold, italic, code, link, header1, header2, header3, list);
                }
            }
            
            // 渲染工具块
            if (isBlockFormat) {
                int toolEnd = markdown.indexOf("[/TOOL_BLOCK]", nextStart);
                if (toolEnd < 0) {
                    // 未闭合的标记，作为普通文本
                    if (nextStart < markdown.length()) {
                        doc.insertString(doc.getLength(), markdown.substring(nextStart), regular);
                    }
                    break;
                }
                int contentStart = nextStart + 12; // 12 = "[TOOL_BLOCK]".length()
                if (contentStart < toolEnd && toolEnd <= markdown.length()) {
                    String toolContent = markdown.substring(contentStart, toolEnd);
                    renderToolBlock(doc, toolContent, regular);
                    pos = toolEnd + 13; // 13 = "[/TOOL_BLOCK]".length()
                } else {
                    // 无效的范围，跳过
                    pos = nextStart + 12;
                }
            } else {
                int toolEnd = markdown.indexOf("[/TOOL]", nextStart);
                if (toolEnd < 0) {
                    if (nextStart < markdown.length()) {
                        doc.insertString(doc.getLength(), markdown.substring(nextStart), regular);
                    }
                    break;
                }
                int contentStart = nextStart + 6; // 6 = "[TOOL]".length()
                if (contentStart < toolEnd && toolEnd <= markdown.length()) {
                    String toolContent = markdown.substring(contentStart, toolEnd);
                    renderToolBlockSimple(doc, toolContent, regular);
                    pos = toolEnd + 7; // 7 = "[/TOOL]".length()
                } else {
                    // 无效的范围，跳过
                    pos = nextStart + 6;
                }
            }
        }
    }
    
    /**
     * 获取或创建样式
     */
    private static Style getOrCreateStyle(StyledDocument doc, String name, Style parent) {
        Style style = doc.getStyle(name);
        if (style == null) {
            style = doc.addStyle(name, parent);
        }
        return style;
    }
    
    /**
     * 清空并渲染Markdown（用于完全替换内容）
     */
    public static void renderMarkdown(JTextPane textPane, String markdown) {
        StyledDocument doc = textPane.getStyledDocument();
        
        // 清空现有内容
        try {
            doc.remove(0, doc.getLength());
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        
        // 追加Markdown内容
        appendMarkdown(textPane, markdown);
    }
    
    /**
     * 递归渲染AST节点
     */
    private static void renderNode(StyledDocument doc, Node node, Style regular, 
            Style bold, Style italic, Style code, Style link, 
            Style header1, Style header2, Style header3, Style list) throws BadLocationException {
        
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Heading) {
                renderHeading(doc, (Heading) child, regular, header1, header2, header3, bold, italic, code, link, list);
            } else if (child instanceof Paragraph) {
                renderParagraph(doc, (Paragraph) child, regular, bold, italic, code, link);
            } else if (child instanceof BlockQuote) {
                renderBlockQuote(doc, (BlockQuote) child, regular, bold, italic, code, link, header1, header2, header3, list);
            } else if (child instanceof BulletList) {
                renderBulletList(doc, (BulletList) child, regular, bold, italic, code, link, header1, header2, header3, list);
            } else if (child instanceof OrderedList) {
                renderOrderedList(doc, (OrderedList) child, regular, bold, italic, code, link, header1, header2, header3, list);
            } else if (child instanceof FencedCodeBlock) {
                renderFencedCodeBlock(doc, (FencedCodeBlock) child, code);
            } else if (child instanceof IndentedCodeBlock) {
                renderIndentedCodeBlock(doc, (IndentedCodeBlock) child, code);
            } else if (child instanceof TableBlock) {
                renderTable(doc, (TableBlock) child, regular, bold, italic, code, link);
            } else if (child instanceof ThematicBreak) {
                doc.insertString(doc.getLength(), "\n", regular);
            }
            
            child = child.getNext();
        }
    }
    
    /**
     * 渲染标题
     */
    private static void renderHeading(StyledDocument doc, Heading heading, Style regular, 
            Style header1, Style header2, Style header3, 
            Style bold, Style italic, Style code, Style link, Style list) throws BadLocationException {
        
        Style headerStyle;
        switch (heading.getLevel()) {
            case 1: headerStyle = header1; break;
            case 2: headerStyle = header2; break;
            default: headerStyle = header3; break;
        }
        
        // 标题前添加空行（除非是文档开始）
        if (doc.getLength() > 0) {
            doc.insertString(doc.getLength(), "\n", regular);
        }
        
        // 渲染标题内容
        renderInlineContent(doc, heading, headerStyle, bold, italic, code, link);
        
        // 添加换行
        doc.insertString(doc.getLength(), "\n", regular);
    }
    
    /**
     * 渲染段落
     */
    private static void renderParagraph(StyledDocument doc, Paragraph paragraph, 
            Style regular, Style bold, Style italic, Style code, Style link) throws BadLocationException {
        
        renderInlineContent(doc, paragraph, regular, bold, italic, code, link);
        // 段落后添加换行，保持适当间距
        doc.insertString(doc.getLength(), "\n", regular);
    }
    
    /**
     * 渲染引用块
     */
    private static void renderBlockQuote(StyledDocument doc, BlockQuote blockQuote, 
            Style regular, Style bold, Style italic, Style code, Style link, 
            Style header1, Style header2, Style header3, Style list) throws BadLocationException {
        
        renderNode(doc, blockQuote, regular, bold, italic, code, link, header1, header2, header3, list);
    }
    
    /**
     * 渲染无序列表
     */
    private static void renderBulletList(StyledDocument doc, BulletList bulletList, 
            Style regular, Style bold, Style italic, Style code, Style link, 
            Style header1, Style header2, Style header3, Style list) throws BadLocationException {
        
        Node item = bulletList.getFirstChild();
        while (item != null) {
            if (item instanceof ListItem) {
                doc.insertString(doc.getLength(), "• ", list); // 简洁的圆点
                renderListItemContent(doc, (ListItem) item, regular, bold, italic, code, link, header1, header2, header3, list);
            }
            item = item.getNext();
        }
    }
    
    /**
     * 渲染有序列表
     */
    private static void renderOrderedList(StyledDocument doc, OrderedList orderedList, 
            Style regular, Style bold, Style italic, Style code, Style link, 
            Style header1, Style header2, Style header3, Style list) throws BadLocationException {
        
        Node item = orderedList.getFirstChild();
        int index = orderedList.getStartNumber();
        while (item != null) {
            if (item instanceof ListItem) {
                doc.insertString(doc.getLength(), index + ". ", list); // 简洁的数字
                renderListItemContent(doc, (ListItem) item, regular, bold, italic, code, link, header1, header2, header3, list);
                index++;
            }
            item = item.getNext();
        }
    }
    
    /**
     * 渲染列表项内容
     * 特殊处理：
     * 1. 如果列表项只包含一个段落，直接渲染其内联内容
     * 2. 如果列表项包含段落+代码块，确保它们之间有正确的换行
     */
    private static void renderListItemContent(StyledDocument doc, ListItem listItem, 
            Style regular, Style bold, Style italic, Style code, Style link,
            Style header1, Style header2, Style header3, Style list) throws BadLocationException {
        
        Node child = listItem.getFirstChild();
        
        // 如果列表项只有一个段落子节点，直接渲染内联内容
        if (child instanceof Paragraph && child.getNext() == null) {
            renderInlineContent(doc, child, regular, bold, italic, code, link);
            doc.insertString(doc.getLength(), "\n", regular);
        } else {
            // 处理包含多个子节点的列表项（如段落+代码块）
            while (child != null) {
                if (child instanceof Paragraph) {
                    renderInlineContent(doc, child, regular, bold, italic, code, link);
                    // 段落后添加换行
                    doc.insertString(doc.getLength(), "\n", regular);
                } else if (child instanceof FencedCodeBlock) {
                    // 代码块前确保有换行分隔
                    renderFencedCodeBlock(doc, (FencedCodeBlock) child, code);
                } else if (child instanceof IndentedCodeBlock) {
                    renderIndentedCodeBlock(doc, (IndentedCodeBlock) child, code);
                } else if (child instanceof BulletList) {
                    // 嵌套的无序列表
                    renderBulletList(doc, (BulletList) child, regular, bold, italic, code, link, header1, header2, header3, list);
                } else if (child instanceof OrderedList) {
                    // 嵌套的有序列表
                    renderOrderedList(doc, (OrderedList) child, regular, bold, italic, code, link, header1, header2, header3, list);
                }
                child = child.getNext();
            }
        }
    }
    
    /**
     * 渲染代码块（使用深色背景）
     */
    private static void renderFencedCodeBlock(StyledDocument doc, FencedCodeBlock codeBlock, Style code) throws BadLocationException {
        String literal = codeBlock.getLiteral();
        if (literal != null && !literal.isEmpty()) {
            Theme t = theme();
            Style codeBlockStyle = getOrCreateStyle(doc, "codeBlock", null);
            StyleConstants.setFontFamily(codeBlockStyle, "Consolas");
            StyleConstants.setFontSize(codeBlockStyle, 12);
            StyleConstants.setBackground(codeBlockStyle, t.codeBlockBg);
            StyleConstants.setForeground(codeBlockStyle, t.codeBlockFg);
            
            doc.insertString(doc.getLength(), "\n", codeBlockStyle);
            doc.insertString(doc.getLength(), literal, codeBlockStyle);
            if (!literal.endsWith("\n")) {
                doc.insertString(doc.getLength(), "\n", codeBlockStyle);
            }
        }
    }
    
    /**
     * 渲染缩进代码块
     */
    private static void renderIndentedCodeBlock(StyledDocument doc, IndentedCodeBlock codeBlock, Style code) throws BadLocationException {
        String literal = codeBlock.getLiteral();
        if (literal != null && !literal.isEmpty()) {
            Theme t = theme();
            Style codeBlockStyle = getOrCreateStyle(doc, "indentedCodeBlock", null);
            StyleConstants.setFontFamily(codeBlockStyle, "Consolas");
            StyleConstants.setFontSize(codeBlockStyle, 12);
            StyleConstants.setBackground(codeBlockStyle, t.codeBlockBg);
            StyleConstants.setForeground(codeBlockStyle, t.codeBlockFg);
            
            doc.insertString(doc.getLength(), "\n", codeBlockStyle);
            doc.insertString(doc.getLength(), literal, codeBlockStyle);
            if (!literal.endsWith("\n")) {
                doc.insertString(doc.getLength(), "\n", codeBlockStyle);
            }
        }
    }
    
    // ======================== 表格渲染 ========================

    /**
     * 渲染 GFM 表格：两趟处理 — 先收集数据计算列宽，再对齐输出。
     */
    /**
     * 将表格渲染为嵌入式 JTable 组件，支持任意长度内容和水平滚动。
     * 与纯文本 ASCII 艺术方案相比，这种方式不受 JTextPane 换行宽度限制，内容始终完整显示。
     */
    private static void renderTable(StyledDocument doc, TableBlock table,
            Style regular, Style bold, Style italic, Style code, Style link) throws BadLocationException {

        List<List<String>> headerRows = new ArrayList<>();
        List<List<String>> bodyRows   = new ArrayList<>();

        // --- Pass 1: 收集单元格纯文本 ---
        Node section = table.getFirstChild();
        while (section != null) {
            boolean isHead = section instanceof TableHead;
            List<List<String>> target = isHead ? headerRows : bodyRows;
            Node row = section.getFirstChild();
            while (row != null) {
                if (row instanceof TableRow) {
                    List<String> cells = new ArrayList<>();
                    Node cell = row.getFirstChild();
                    while (cell != null) {
                        if (cell instanceof TableCell) {
                            cells.add(extractText(cell).trim());
                        }
                        cell = cell.getNext();
                    }
                    target.add(cells);
                }
                row = row.getNext();
            }
            section = section.getNext();
        }

        int numCols = 0;
        for (List<String> r : headerRows) numCols = Math.max(numCols, r.size());
        for (List<String> r : bodyRows)   numCols = Math.max(numCols, r.size());
        if (numCols == 0) return;

        // --- 构造列名（取第一个 header 行，没有则用序号）---
        final String[] colNames = new String[numCols];
        if (!headerRows.isEmpty()) {
            List<String> h = headerRows.get(0);
            for (int i = 0; i < numCols; i++) colNames[i] = i < h.size() ? h.get(i) : "";
        } else {
            for (int i = 0; i < numCols; i++) colNames[i] = "Col " + (i + 1);
        }

        // --- 构造行数据 ---
        final Object[][] rowData = new Object[bodyRows.size()][numCols];
        for (int r = 0; r < bodyRows.size(); r++) {
            List<String> row = bodyRows.get(r);
            for (int c = 0; c < numCols; c++) {
                rowData[r][c] = c < row.size() ? row.get(c) : "";
            }
        }

        Theme t = theme();

        // --- 创建 JTable（需在 EDT 上，调用者保证在 EDT） ---
        javax.swing.table.DefaultTableModel model =
                new javax.swing.table.DefaultTableModel(rowData, colNames) {
                    @Override public boolean isCellEditable(int r, int c) { return false; }
                };

        JTable jTable = new JTable(model);
        Font cellFont   = new Font("Microsoft YaHei", Font.PLAIN, 12);
        Font headerFont = new Font("Microsoft YaHei", Font.BOLD,  12);
        jTable.setFont(cellFont);
        jTable.setBackground(t.tableEvenBg);
        jTable.setForeground(t.tableCellFg);
        jTable.setGridColor(t.tableBorder);
        jTable.setRowHeight(28);
        jTable.setShowGrid(true);
        jTable.setIntercellSpacing(new Dimension(1, 1));
        jTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // 交替行颜色渲染器
        final Color evenBg = t.tableEvenBg;
        final Color oddBg  = t.tableOddBg;
        final Color cellFg = t.tableCellFg;
        final Font rendererFont = cellFont;
        jTable.setDefaultRenderer(Object.class, new javax.swing.table.TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable tbl, Object value, boolean sel, boolean focus, int row, int col) {
                JTextArea area = new JTextArea(value != null ? value.toString() : "");
                area.setFont(rendererFont);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                area.setOpaque(true);
                area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                area.setBackground(sel ? tbl.getSelectionBackground() : (row % 2 == 0 ? evenBg : oddBg));
                area.setForeground(sel ? tbl.getSelectionForeground() : cellFg);
                area.setToolTipText(value != null ? value.toString() : "");

                int width = Math.max(40, tbl.getColumnModel().getColumn(col).getWidth() - 8);
                area.setSize(width, Short.MAX_VALUE);
                int preferredHeight = Math.max(28, area.getPreferredSize().height + 8);
                if (tbl.getRowHeight(row) != preferredHeight) {
                    tbl.setRowHeight(row, preferredHeight);
                }
                return area;
            }
        });

        // 表头样式
        JTableHeader tableHeader = jTable.getTableHeader();
        tableHeader.setFont(headerFont);
        tableHeader.setBackground(t.tableHeaderBg);
        tableHeader.setForeground(t.tableHeaderFg);
        tableHeader.setReorderingAllowed(false);

        // --- 自动计算列宽（基于 FontMetrics 估算，每列最宽内容决定，上限 400px）---
        // 使用临时 FontMetrics 估算（Consolas 12pt：ASCII ~7px/char，CJK ~14px/char）
        final int CHAR_PX = 7;
        final int CJK_PX  = 14;
        final int CELL_PAD = 16;
        final int MIN_COL_PX = 90;
        final int MAX_COL_PX = 360;
        final int MAX_VIEW_PX = 720;

        int[] widths = new int[numCols];
        int totalWidth = 0;
        for (int c = 0; c < numCols; c++) {
            int maxPx = estimateTextPx(colNames[c], CHAR_PX, CJK_PX) + CELL_PAD;
            for (Object[] row : rowData) {
                String txt = row[c] != null ? row[c].toString() : "";
                int px = estimateTextPx(txt, CHAR_PX, CJK_PX) + CELL_PAD;
                maxPx = Math.max(maxPx, px);
            }
            int colPx = Math.max(MIN_COL_PX, Math.min(maxPx, MAX_COL_PX));
            widths[c] = colPx;
            totalWidth += colPx;
        }

        if (totalWidth < MAX_VIEW_PX && numCols > 0) {
            int extra = (MAX_VIEW_PX - totalWidth) / numCols;
            for (int c = 0; c < numCols; c++) {
                widths[c] += extra;
            }
            totalWidth = 0;
            for (int width : widths) totalWidth += width;
        }

        for (int c = 0; c < numCols; c++) {
            jTable.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
        }

        // --- 包装进 JScrollPane，总宽限制，窄面板内水平滚动 ---
        int viewW = Math.min(totalWidth + 4, MAX_VIEW_PX);
        int rowH   = jTable.getRowHeight();
        int hdrH   = tableHeader.getPreferredSize().height > 0
                ? tableHeader.getPreferredSize().height : 26;
        int viewH  = Math.min(hdrH + rowH * Math.max(1, jTable.getRowCount()) + 80, 420);

        JScrollPane scrollPane = new JScrollPane(jTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(viewW, viewH));
        scrollPane.setBorder(BorderFactory.createLineBorder(t.tableBorder, 1));
        scrollPane.getViewport().setBackground(t.tableEvenBg);

        // --- 作为组件嵌入 StyledDocument ---
        String styleName = "tableComp_" + System.nanoTime();
        Style compStyle = doc.addStyle(styleName, regular);
        StyleConstants.setComponent(compStyle, scrollPane);

        if (doc.getLength() > 0) doc.insertString(doc.getLength(), "\n", regular);
        doc.insertString(doc.getLength(), " ", compStyle);  // 组件占位符
        doc.insertString(doc.getLength(), "\n", regular);
    }

    /** 估算文本渲染像素宽度（不依赖 Graphics 上下文）。 */
    private static int estimateTextPx(String text, int asciiPx, int cjkPx) {
        if (text == null || text.isEmpty()) return 0;
        int px = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
            boolean wide = b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                    || b == Character.UnicodeBlock.HIRAGANA
                    || b == Character.UnicodeBlock.KATAKANA
                    || b == Character.UnicodeBlock.HANGUL_SYLLABLES;
            px += wide ? cjkPx : asciiPx;
            i += Character.charCount(cp);
        }
        return px;
    }

    // ======================== 内联内容渲染 ========================

    /**
     * 渲染内联内容（文本、粗体、斜体、代码、链接）
     */
    private static void renderInlineContent(StyledDocument doc, Node node, 
            Style regular, Style bold, Style italic, Style code, Style link) throws BadLocationException {
        
        if (node == null) return;
        
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Text) {
                Text text = (Text) child;
                String literal = text.getLiteral();
                // 检测并渲染工具执行标记 [TOOL]...[/TOOL]
                renderTextWithToolMarker(doc, literal, regular);
            } else if (child instanceof StrongEmphasis) {
                // 粗体：递归渲染子节点，当前样式使用bold
                renderInlineContent(doc, child, bold, bold, italic, code, link);
            } else if (child instanceof Emphasis) {
                // 斜体：递归渲染子节点，当前样式使用italic
                renderInlineContent(doc, child, italic, bold, italic, code, link);
            } else if (child instanceof Code) {
                Code codeNode = (Code) child;
                String literal = codeNode.getLiteral();
                doc.insertString(doc.getLength(), literal, code);
            } else if (child instanceof Link) {
                Link linkNode = (Link) child;
                String destination = linkNode.getDestination();
                
                // 渲染链接文本（使用link样式）
                doc.insertString(doc.getLength(), "[", link);
                renderInlineContent(doc, child, link, bold, italic, code, link);
                doc.insertString(doc.getLength(), "](" + destination + ")", link);
            } else if (child instanceof org.commonmark.node.Image) {
                org.commonmark.node.Image imgNode = (org.commonmark.node.Image) child;
                String altText = imgNode.getTitle();
                if (altText == null || altText.isEmpty()) {
                    altText = imgNode.getDestination();
                }
                doc.insertString(doc.getLength(), "[" + altText + "]", link);
                doc.insertString(doc.getLength(), "(", link);
            } else if (child instanceof HardLineBreak || child instanceof SoftLineBreak) {
                doc.insertString(doc.getLength(), "\n", regular);
            }
            
            child = child.getNext();
        }
    }
    
    /**
     * 渲染文本，检测并特殊处理工具执行标记
     * 支持两种格式：
     * - [TOOL]...[/TOOL] - 旧格式，简单文本
     * - [TOOL_BLOCK]工具名|参数[/TOOL_BLOCK] - 新格式，带参数
     */
    private static void renderTextWithToolMarker(StyledDocument doc, String text, Style regular) throws BadLocationException {
        if (text == null || text.isEmpty()) return;
        
        int pos = 0;
        while (pos < text.length()) {
            // 查找两种标记
            int toolBlockStart = text.indexOf("[TOOL_BLOCK]", pos);
            int toolStart = text.indexOf("[TOOL]", pos);
            
            // 确定哪个标记先出现
            int nextMarkerStart = -1;
            boolean isBlockFormat = false;
            
            if (toolBlockStart >= 0 && (toolStart < 0 || toolBlockStart < toolStart)) {
                nextMarkerStart = toolBlockStart;
                isBlockFormat = true;
            } else if (toolStart >= 0) {
                nextMarkerStart = toolStart;
                isBlockFormat = false;
            }
            
            if (nextMarkerStart < 0) {
                // 没有更多工具标记，渲染剩余文本
                doc.insertString(doc.getLength(), text.substring(pos), regular);
                break;
            }
            
            // 先渲染标记前的普通文本
            if (nextMarkerStart > pos) {
                doc.insertString(doc.getLength(), text.substring(pos, nextMarkerStart), regular);
            }
            
            if (isBlockFormat) {
                // 处理 [TOOL_BLOCK]工具名|参数[/TOOL_BLOCK] 格式
                int toolEnd = text.indexOf("[/TOOL_BLOCK]", nextMarkerStart);
                if (toolEnd < 0) {
                    doc.insertString(doc.getLength(), text.substring(nextMarkerStart), regular);
                    break;
                }
                
                String toolContent = text.substring(nextMarkerStart + 12, toolEnd); // 12 = "[TOOL_BLOCK]".length()
                renderToolBlock(doc, toolContent, regular);
                pos = toolEnd + 13; // 13 = "[/TOOL_BLOCK]".length()
            } else {
                // 处理旧的 [TOOL]...[/TOOL] 格式
                int toolEnd = text.indexOf("[/TOOL]", nextMarkerStart);
                if (toolEnd < 0) {
                    doc.insertString(doc.getLength(), text.substring(nextMarkerStart), regular);
                    break;
                }
                
                String toolContent = text.substring(nextMarkerStart + 6, toolEnd);
                // 使用新样式渲染旧格式
                renderToolBlockSimple(doc, toolContent, regular);
                pos = toolEnd + 7;
            }
        }
    }
    
    /**
     * 渲染工具执行块 - 类似 Cursor 风格
     * 格式: 工具名（独立一行）+ 每个参数一行
     */
    private static void renderToolBlock(StyledDocument doc, String content, Style regular) throws BadLocationException {
        String[] parts = content.split("\\|", 2);
        String toolName = parts[0];
        String params = parts.length > 1 ? parts[1] : "";
        
        Theme t = theme();
        Style blockBgStyle = getOrCreateStyle(doc, "toolBlockBg", regular);
        StyleConstants.setBackground(blockBgStyle, t.toolBlockBg);
        
        Style iconStyle = getOrCreateStyle(doc, "toolIcon", blockBgStyle);
        StyleConstants.setForeground(iconStyle, t.toolIcon);
        StyleConstants.setFontSize(iconStyle, 13);
        StyleConstants.setBold(iconStyle, true);
        StyleConstants.setBackground(iconStyle, t.toolBlockBg);
        
        Style nameStyle = getOrCreateStyle(doc, "toolName", blockBgStyle);
        StyleConstants.setForeground(nameStyle, t.toolName);
        StyleConstants.setFontFamily(nameStyle, "Consolas");
        StyleConstants.setFontSize(nameStyle, 13);
        StyleConstants.setBold(nameStyle, true);
        StyleConstants.setBackground(nameStyle, t.toolBlockBg);
        
        Style paramKeyStyle = getOrCreateStyle(doc, "toolParamKey", blockBgStyle);
        StyleConstants.setForeground(paramKeyStyle, t.toolParamKey);
        StyleConstants.setFontFamily(paramKeyStyle, "Consolas");
        StyleConstants.setFontSize(paramKeyStyle, 11);
        StyleConstants.setBackground(paramKeyStyle, t.toolBlockBg);
        
        Style paramValStyle = getOrCreateStyle(doc, "toolParamVal", blockBgStyle);
        StyleConstants.setForeground(paramValStyle, t.toolParamVal);
        StyleConstants.setFontFamily(paramValStyle, "Microsoft YaHei");
        StyleConstants.setFontSize(paramValStyle, 11);
        StyleConstants.setBackground(paramValStyle, t.toolBlockBg);
        
        // 渲染：▶ 工具名（独立一行）
        doc.insertString(doc.getLength(), "▶ ", iconStyle);
        doc.insertString(doc.getLength(), toolName, nameStyle);
        doc.insertString(doc.getLength(), "\n", blockBgStyle);
        
        // 如果有参数，每个参数一行
        if (!params.isEmpty()) {
            renderToolParams(doc, params, paramKeyStyle, paramValStyle, blockBgStyle);
        }
    }
    
    /**
     * 渲染工具参数 - 每个参数一行
     */
    private static void renderToolParams(StyledDocument doc, String params, 
            Style keyStyle, Style valStyle, Style bgStyle) throws BadLocationException {
        // 解析参数 - 按 ||| 分隔符分割（避免换行符破坏 Markdown 解析）
        String[] paramLines = params.split("\\|\\|\\|");
        
        for (String line : paramLines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // 缩进
            doc.insertString(doc.getLength(), "   ", bgStyle);
            
            int eqIdx = line.indexOf('=');
            if (eqIdx > 0) {
                String key = line.substring(0, eqIdx);
                String val = line.substring(eqIdx + 1);
                
                doc.insertString(doc.getLength(), key, keyStyle);
                doc.insertString(doc.getLength(), "=", keyStyle);
                doc.insertString(doc.getLength(), val, valStyle);
            } else {
                // 没有等号（如 "..."），整个作为值显示
                doc.insertString(doc.getLength(), line, valStyle);
            }
            doc.insertString(doc.getLength(), "\n", bgStyle);
        }
    }
    
    /**
     * 简单渲染工具块（用于旧格式兼容）
     */
    private static void renderToolBlockSimple(StyledDocument doc, String content, Style regular) throws BadLocationException {
        Theme t = theme();
        Style blockStyle = getOrCreateStyle(doc, "toolBlockSimple", regular);
        StyleConstants.setBackground(blockStyle, t.toolBlockBg);
        StyleConstants.setForeground(blockStyle, t.toolName);
        StyleConstants.setFontFamily(blockStyle, "Consolas");
        StyleConstants.setFontSize(blockStyle, 12);
        StyleConstants.setBold(blockStyle, true);
        
        doc.insertString(doc.getLength(), "▶ " + content + "\n", blockStyle);
    }
    
    /**
     * 流式渲染Markdown（增量更新）
     * 每次调用时重新解析并渲染整个缓冲区内容，替换从指定位置开始的内容
     */
    public static void appendMarkdownStreaming(JTextPane textPane, String markdown, int startPos) {
        if (textPane == null) {
            throw new IllegalArgumentException("textPane cannot be null");
        }
        if (markdown == null) {
            markdown = "";
        }
        if (startPos < 0) {
            startPos = 0;
        }
        Theme t = resolveTheme(textPane);
        StyledDocument doc = textPane.getStyledDocument();
        
        try {
            int currentLength = doc.getLength();
            if (currentLength > startPos) {
                doc.remove(startPos, currentLength - startPos);
            }
            
            Style[] styles = buildStyles(doc, t);

            try {
                renderWithToolBlocks(doc, markdown, styles[0], styles[1], styles[2], styles[3], styles[4], styles[5], styles[6], styles[7], styles[8]);
            } catch (Exception e) {
                doc.insertString(doc.getLength(), markdown, styles[0]);
            }
        } catch (BadLocationException e) {
            try {
                Style regular = getOrCreateStyle(doc, "regular", 
                    StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE));
                doc.insertString(doc.getLength(), markdown, regular);
            } catch (BadLocationException e2) {
                // ignored
            }
        }
    }
    
    /**
     * 提取节点文本内容
     */
    private static String extractText(Node node) {
        StringBuilder sb = new StringBuilder();
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Text) {
                sb.append(((Text) child).getLiteral());
            } else {
                sb.append(extractText(child));
            }
            child = child.getNext();
        }
        return sb.toString();
    }
}