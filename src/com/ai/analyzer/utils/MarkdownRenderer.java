package com.ai.analyzer.utils;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public class MarkdownRenderer {
    private static final Color HEADER_COLOR = new Color(0, 102, 204);  // 蓝色 - 标题
    private static final Color CODE_COLOR = new Color(220, 50, 47);     // 红色 - 代码
    private static final Color CODE_BG_COLOR = new Color(248, 248, 248); // 浅灰 - 代码背景
    private static final Color BOLD_COLOR = Color.BLACK;                // 黑色 - 粗体
    private static final Color ITALIC_COLOR = new Color(136, 136, 136);  // 灰色 - 斜体（用于工具信息）
    private static final Color LIST_COLOR = Color.BLACK;                // 黑色 - 列表
    private static final Color LINK_COLOR = new Color(0, 102, 204);     // 蓝色 - 链接
    private static final Color TOOL_INFO_COLOR = new Color(136, 136, 136); // 灰色 - 工具信息

    /**
     * 渲染Markdown到JTextPane的末尾（不清空现有内容）
     */
    public static void appendMarkdown(JTextPane textPane, String markdown) {
        StyledDocument doc = textPane.getStyledDocument();

        // 设置默认样式
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        Style regular = getOrCreateStyle(doc, "regular", defaultStyle);
        StyleConstants.setFontFamily(regular, "Microsoft YaHei");
        StyleConstants.setFontSize(regular, 12);
        StyleConstants.setForeground(regular, Color.BLACK);
        StyleConstants.setBackground(regular, Color.WHITE);

        // 创建各种样式
        Style header1 = getOrCreateStyle(doc, "header1", regular);
        StyleConstants.setFontSize(header1, 18);
        StyleConstants.setBold(header1, true);
        StyleConstants.setForeground(header1, HEADER_COLOR);

        Style header2 = getOrCreateStyle(doc, "header2", regular);
        StyleConstants.setFontSize(header2, 16);
        StyleConstants.setBold(header2, true);
        StyleConstants.setForeground(header2, HEADER_COLOR);

        Style header3 = getOrCreateStyle(doc, "header3", regular);
        StyleConstants.setFontSize(header3, 14);
        StyleConstants.setBold(header3, true);
        StyleConstants.setForeground(header3, HEADER_COLOR);

        Style bold = getOrCreateStyle(doc, "bold", regular);
        StyleConstants.setBold(bold, true);
        StyleConstants.setForeground(bold, BOLD_COLOR);

        Style italic = getOrCreateStyle(doc, "italic", regular);
        StyleConstants.setItalic(italic, true);
        StyleConstants.setForeground(italic, ITALIC_COLOR);
        StyleConstants.setFontSize(italic, 11); // 工具信息使用较小字体
        
        // 工具信息样式（灰色小字斜体）
        Style toolInfo = getOrCreateStyle(doc, "toolInfo", regular);
        StyleConstants.setItalic(toolInfo, true);
        StyleConstants.setForeground(toolInfo, TOOL_INFO_COLOR);
        StyleConstants.setFontSize(toolInfo, 11);

        Style code = getOrCreateStyle(doc, "code", regular);
        StyleConstants.setFontFamily(code, "Consolas");
        StyleConstants.setBackground(code, CODE_BG_COLOR);
        StyleConstants.setForeground(code, CODE_COLOR);

        Style list = getOrCreateStyle(doc, "list", regular);
        StyleConstants.setForeground(list, LIST_COLOR);

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
        
        // 渲染标题内容（可能包含内联格式）
        renderInlineContent(doc, heading, headerStyle, bold, italic, code, link);
        
        // 添加单个换行
        doc.insertString(doc.getLength(), "\n", regular);
    }
    
    /**
     * 渲染段落
     */
    private static void renderParagraph(StyledDocument doc, Paragraph paragraph, 
            Style regular, Style bold, Style italic, Style code, Style link) throws BadLocationException {
        
        renderInlineContent(doc, paragraph, regular, bold, italic, code, link);
        // 只添加一个换行，避免产生过多空行
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
                doc.insertString(doc.getLength(), "• ", list);
                // 渲染列表项内容（段落或其他块元素会自带换行，无需额外添加）
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
                doc.insertString(doc.getLength(), index + ". ", list);
                // 渲染列表项内容（段落或其他块元素会自带换行，无需额外添加）
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
     * 渲染代码块
     */
    private static void renderFencedCodeBlock(StyledDocument doc, FencedCodeBlock codeBlock, Style code) throws BadLocationException {
        String literal = codeBlock.getLiteral();
        if (literal != null && !literal.isEmpty()) {
            doc.insertString(doc.getLength(), literal, code);
            doc.insertString(doc.getLength(), "\n", code);
        }
    }
    
    /**
     * 渲染缩进代码块
     */
    private static void renderIndentedCodeBlock(StyledDocument doc, IndentedCodeBlock codeBlock, Style code) throws BadLocationException {
        String literal = codeBlock.getLiteral();
        if (literal != null && !literal.isEmpty()) {
            doc.insertString(doc.getLength(), literal, code);
            doc.insertString(doc.getLength(), "\n", code);
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
                doc.insertString(doc.getLength(), literal, regular);
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
            
            // 设置默认样式
            Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
            Style regular = getOrCreateStyle(doc, "regular", defaultStyle);
            StyleConstants.setFontFamily(regular, "Microsoft YaHei");
            StyleConstants.setFontSize(regular, 12);
            StyleConstants.setForeground(regular, Color.BLACK);
            StyleConstants.setBackground(regular, Color.WHITE);

            // 创建各种样式
            Style header1 = getOrCreateStyle(doc, "header1", regular);
            StyleConstants.setFontSize(header1, 18);
            StyleConstants.setBold(header1, true);
            StyleConstants.setForeground(header1, HEADER_COLOR);

            Style header2 = getOrCreateStyle(doc, "header2", regular);
            StyleConstants.setFontSize(header2, 16);
            StyleConstants.setBold(header2, true);
            StyleConstants.setForeground(header2, HEADER_COLOR);

            Style header3 = getOrCreateStyle(doc, "header3", regular);
            StyleConstants.setFontSize(header3, 14);
            StyleConstants.setBold(header3, true);
            StyleConstants.setForeground(header3, HEADER_COLOR);

            Style bold = getOrCreateStyle(doc, "bold", regular);
            StyleConstants.setBold(bold, true);
            StyleConstants.setForeground(bold, BOLD_COLOR);

            Style italic = getOrCreateStyle(doc, "italic", regular);
            StyleConstants.setItalic(italic, true);
            StyleConstants.setForeground(italic, ITALIC_COLOR);
            StyleConstants.setFontSize(italic, 11); // 工具信息使用较小字体
            
            // 工具信息样式（灰色小字斜体）
            Style toolInfo = getOrCreateStyle(doc, "toolInfo", regular);
            StyleConstants.setItalic(toolInfo, true);
            StyleConstants.setForeground(toolInfo, TOOL_INFO_COLOR);
            StyleConstants.setFontSize(toolInfo, 11);

            Style code = getOrCreateStyle(doc, "code", regular);
            StyleConstants.setFontFamily(code, "Consolas");
            StyleConstants.setBackground(code, CODE_BG_COLOR);
            StyleConstants.setForeground(code, CODE_COLOR);

            Style list = getOrCreateStyle(doc, "list", regular);
            StyleConstants.setForeground(list, LIST_COLOR);

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