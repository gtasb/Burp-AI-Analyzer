package com.ai.analyzer.utils;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public class MarkdownRenderer {
    // 简洁专业配色方案
    private static final Color HEADER1_COLOR = new Color(192, 57, 43);   // 深红色 - 一级标题（高危）
    private static final Color HEADER2_COLOR = new Color(41, 128, 185);  // 蓝色 - 二级标题
    private static final Color HEADER3_COLOR = new Color(44, 62, 80);    // 深灰 - 三级标题
    private static final Color CODE_COLOR = new Color(192, 57, 43);      // 红色 - 行内代码
    private static final Color CODE_BG_COLOR = new Color(245, 245, 245); // 浅灰背景 - 代码背景
    private static final Color CODE_BLOCK_BG = new Color(40, 44, 52);    // 深色背景 - 代码块
    private static final Color CODE_BLOCK_FG = new Color(171, 178, 191); // 浅灰文字 - 代码块
    private static final Color BOLD_COLOR = new Color(44, 62, 80);       // 深灰 - 粗体
    private static final Color ITALIC_COLOR = new Color(127, 140, 141);  // 灰色 - 斜体
    private static final Color LIST_COLOR = new Color(44, 62, 80);       // 深灰 - 列表
    private static final Color LINK_COLOR = new Color(41, 128, 185);     // 蓝色 - 链接
    
    // 工具执行信息 - 灰色斜体小字
    private static final Color TOOL_EXEC_COLOR = new Color(127, 140, 141); // 灰色

    /**
     * 渲染Markdown到JTextPane的末尾（不清空现有内容）
     */
    public static void appendMarkdown(JTextPane textPane, String markdown) {
        StyledDocument doc = textPane.getStyledDocument();

        // 设置默认样式
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        Style regular = getOrCreateStyle(doc, "regular", defaultStyle);
        StyleConstants.setFontFamily(regular, "Microsoft YaHei");
        StyleConstants.setFontSize(regular, 13);
        StyleConstants.setForeground(regular, new Color(51, 51, 51));
        StyleConstants.setBackground(regular, Color.WHITE);

        // 一级标题 - 红色粗体（高危/风险）
        Style header1 = getOrCreateStyle(doc, "header1", regular);
        StyleConstants.setFontSize(header1, 15);
        StyleConstants.setBold(header1, true);
        StyleConstants.setForeground(header1, HEADER1_COLOR);

        // 二级标题 - 蓝色粗体
        Style header2 = getOrCreateStyle(doc, "header2", regular);
        StyleConstants.setFontSize(header2, 14);
        StyleConstants.setBold(header2, true);
        StyleConstants.setForeground(header2, HEADER2_COLOR);

        // 三级标题 - 深灰粗体
        Style header3 = getOrCreateStyle(doc, "header3", regular);
        StyleConstants.setFontSize(header3, 13);
        StyleConstants.setBold(header3, true);
        StyleConstants.setForeground(header3, HEADER3_COLOR);

        // 粗体
        Style bold = getOrCreateStyle(doc, "bold", regular);
        StyleConstants.setBold(bold, true);
        StyleConstants.setForeground(bold, BOLD_COLOR);

        // 斜体
        Style italic = getOrCreateStyle(doc, "italic", regular);
        StyleConstants.setItalic(italic, true);
        StyleConstants.setForeground(italic, ITALIC_COLOR);

        // 行内代码 - 红色带浅灰背景
        Style code = getOrCreateStyle(doc, "code", regular);
        StyleConstants.setFontFamily(code, "Consolas");
        StyleConstants.setFontSize(code, 12);
        StyleConstants.setBackground(code, CODE_BG_COLOR);
        StyleConstants.setForeground(code, CODE_COLOR);

        // 列表
        Style list = getOrCreateStyle(doc, "list", regular);
        StyleConstants.setForeground(list, LIST_COLOR);

        // 链接 - 蓝色下划线
        Style link = getOrCreateStyle(doc, "link", regular);
        StyleConstants.setForeground(link, LINK_COLOR);
        StyleConstants.setUnderline(link, true);

        // 使用CommonMark解析
        Parser parser = Parser.builder().build();
        Node document = parser.parse(markdown);
        
        // 渲染AST到StyledDocument
        try {
            renderNode(doc, document, regular, bold, italic, code, link, header1, header2, header3, list);
        } catch (BadLocationException e) {
            e.printStackTrace();
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
            } else if (child instanceof ThematicBreak) {
                // 分隔线，添加空行
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
     * 特殊处理：如果列表项只包含一个段落，直接渲染其内联内容（不添加段落的额外换行）
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
            // 否则使用标准的节点渲染
            renderNode(doc, listItem, regular, bold, italic, code, link, header1, header2, header3, list);
        }
    }
    
    /**
     * 渲染代码块（使用深色背景）
     */
    private static void renderFencedCodeBlock(StyledDocument doc, FencedCodeBlock codeBlock, Style code) throws BadLocationException {
        String literal = codeBlock.getLiteral();
        if (literal != null && !literal.isEmpty()) {
            // 创建代码块样式（深色背景，浅色文字）
            Style codeBlockStyle = doc.addStyle("codeBlock", null);
            StyleConstants.setFontFamily(codeBlockStyle, "Consolas");
            StyleConstants.setFontSize(codeBlockStyle, 12);
            StyleConstants.setBackground(codeBlockStyle, CODE_BLOCK_BG);
            StyleConstants.setForeground(codeBlockStyle, CODE_BLOCK_FG);
            
            // 添加代码块前的空行和标记
            doc.insertString(doc.getLength(), "\n", codeBlockStyle);
            doc.insertString(doc.getLength(), literal, codeBlockStyle);
            // 确保代码块后有换行
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
            // 使用与代码块相同的样式
            Style codeBlockStyle = doc.addStyle("indentedCodeBlock", null);
            StyleConstants.setFontFamily(codeBlockStyle, "Consolas");
            StyleConstants.setFontSize(codeBlockStyle, 12);
            StyleConstants.setBackground(codeBlockStyle, CODE_BLOCK_BG);
            StyleConstants.setForeground(codeBlockStyle, CODE_BLOCK_FG);
            
            doc.insertString(doc.getLength(), "\n", codeBlockStyle);
            doc.insertString(doc.getLength(), literal, codeBlockStyle);
            if (!literal.endsWith("\n")) {
                doc.insertString(doc.getLength(), "\n", codeBlockStyle);
            }
        }
    }
    
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
     * 渲染文本，检测并特殊处理工具执行标记 [TOOL]...[/TOOL]
     */
    private static void renderTextWithToolMarker(StyledDocument doc, String text, Style regular) throws BadLocationException {
        if (text == null || text.isEmpty()) return;
        
        int pos = 0;
        while (pos < text.length()) {
            int toolStart = text.indexOf("[TOOL]", pos);
            
            if (toolStart < 0) {
                // 没有更多工具标记，渲染剩余文本
                doc.insertString(doc.getLength(), text.substring(pos), regular);
                break;
            }
            
            // 先渲染标记前的普通文本
            if (toolStart > pos) {
                doc.insertString(doc.getLength(), text.substring(pos, toolStart), regular);
            }
            
            int toolEnd = text.indexOf("[/TOOL]", toolStart);
            if (toolEnd < 0) {
                // 没有找到结束标记，将其作为普通文本渲染
                doc.insertString(doc.getLength(), text.substring(toolStart), regular);
                break;
            }
            
            // 提取工具信息内容
            String toolContent = text.substring(toolStart + 6, toolEnd); // 6 = "[TOOL]".length()
            
            // 灰色斜体小字样式 - 低调但可见
            Style toolStyle = doc.addStyle("toolExec", regular);
            StyleConstants.setForeground(toolStyle, TOOL_EXEC_COLOR);
            StyleConstants.setItalic(toolStyle, true);
            StyleConstants.setFontSize(toolStyle, 11);
            
            // 渲染工具执行信息
            doc.insertString(doc.getLength(), toolContent, toolStyle);
            
            pos = toolEnd + 7; // 7 = "[/TOOL]".length()
        }
    }
    
    /**
     * 流式渲染Markdown（增量更新）
     * 每次调用时重新解析并渲染整个缓冲区内容，替换从指定位置开始的内容
     */
    public static void appendMarkdownStreaming(JTextPane textPane, String markdown, int startPos) {
        StyledDocument doc = textPane.getStyledDocument();
        
        try {
            // 删除从startPos开始的所有内容
            int currentLength = doc.getLength();
            if (currentLength > startPos) {
                doc.remove(startPos, currentLength - startPos);
            }
            
            // 设置默认样式（与 appendMarkdown 保持一致）
            Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
            Style regular = getOrCreateStyle(doc, "regular", defaultStyle);
            StyleConstants.setFontFamily(regular, "Microsoft YaHei");
            StyleConstants.setFontSize(regular, 13);
            StyleConstants.setForeground(regular, new Color(51, 51, 51));
            StyleConstants.setBackground(regular, Color.WHITE);

            // 一级标题 - 红色粗体
            Style header1 = getOrCreateStyle(doc, "header1", regular);
            StyleConstants.setFontSize(header1, 15);
            StyleConstants.setBold(header1, true);
            StyleConstants.setForeground(header1, HEADER1_COLOR);

            // 二级标题 - 蓝色粗体
            Style header2 = getOrCreateStyle(doc, "header2", regular);
            StyleConstants.setFontSize(header2, 14);
            StyleConstants.setBold(header2, true);
            StyleConstants.setForeground(header2, HEADER2_COLOR);

            // 三级标题 - 深灰粗体
            Style header3 = getOrCreateStyle(doc, "header3", regular);
            StyleConstants.setFontSize(header3, 13);
            StyleConstants.setBold(header3, true);
            StyleConstants.setForeground(header3, HEADER3_COLOR);

            // 粗体
            Style bold = getOrCreateStyle(doc, "bold", regular);
            StyleConstants.setBold(bold, true);
            StyleConstants.setForeground(bold, BOLD_COLOR);

            // 斜体
            Style italic = getOrCreateStyle(doc, "italic", regular);
            StyleConstants.setItalic(italic, true);
            StyleConstants.setForeground(italic, ITALIC_COLOR);

            // 行内代码 - 红色带浅灰背景
            Style code = getOrCreateStyle(doc, "code", regular);
            StyleConstants.setFontFamily(code, "Consolas");
            StyleConstants.setFontSize(code, 12);
            StyleConstants.setBackground(code, CODE_BG_COLOR);
            StyleConstants.setForeground(code, CODE_COLOR);

            // 列表
            Style list = getOrCreateStyle(doc, "list", regular);
            StyleConstants.setForeground(list, LIST_COLOR);

            // 链接 - 蓝色下划线
            Style link = getOrCreateStyle(doc, "link", regular);
            StyleConstants.setForeground(link, LINK_COLOR);
            StyleConstants.setUnderline(link, true);

            // 使用CommonMark解析（尝试解析，如果失败则使用纯文本）
            try {
                Parser parser = Parser.builder().build();
                Node document = parser.parse(markdown);
                
                // 渲染AST到StyledDocument
                renderNode(doc, document, regular, bold, italic, code, link, header1, header2, header3, list);
            } catch (Exception e) {
                // 如果解析失败（比如不完整的Markdown），先用纯文本显示
                doc.insertString(doc.getLength(), markdown, regular);
            }
        } catch (BadLocationException e) {
            // 如果出错，记录但不中断
            try {
                Style regular = getOrCreateStyle(doc, "regular", 
                    StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE));
                doc.insertString(doc.getLength(), markdown, regular);
            } catch (BadLocationException e2) {
                // 忽略
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