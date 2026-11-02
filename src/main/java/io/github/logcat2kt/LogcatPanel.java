package io.github.logcat2kt;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.plaf.basic.*;
import javax.swing.text.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.beans.PropertyChangeListener;
import java.util.concurrent.*;
import java.util.regex.*;

/** A browser-independent rendition of the upstream page, usable with any IDE runtime. */
public final class LogcatPanel extends JPanel implements AutoCloseable {
    static final Color BG = new Color(0xf5f6f8), LINE = new Color(0xe4e7ec), INK = new Color(0x1f2328),
        MUTED = new Color(0x6b7280), ACCENT = new Color(0x4f46e5), SOFT = new Color(0xeef0ff);
    private static final Font UI = new Font("Dialog", Font.PLAIN, 12), MONO = new Font("Monospaced", Font.PLAIN, 13);
    private final JTextArea input = new JTextArea();
    private final JTextPane kotlin = new JTextPane();
    private final JTextField root = new JTextField("Response", 10);
    private final JComboBox<String> annotation = combo("无", "Gson", "kotlinx.serialization", "Moshi");
    private final JComboBox<String> nullable = combo("全部可空", "按样本推断", "全部非空");
    private final JComboBox<String> naming = combo("转 camelCase", "保持原样");
    private final JCheckBox timestamps = new JCheckBox("时间戳字段用 Long", true);
    private final JTree tree = new JTree(new DefaultMutableTreeNode("等待输入…"));
    private final JLabel status = new JLabel("就绪"), empty = new JLabel("等待输入…", SwingConstants.CENTER);
    private final JPanel output = new JPanel(new CardLayout());
    private final JButton jsonTab = button("格式化 JSON", false), kotlinTab = button("Kotlin", false), copy = button("复制", true);
    private final JSplitPane split;
    private final Timer debounce;
    private final ConversionEngine engine = new ConversionEngine();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "logcat2kt-parser"); thread.setDaemon(true); return thread;
    });
    private Future<?> pending;
    private volatile boolean closed;
    private long generation;
    private ConversionEngine.Result result;
    private boolean kotlinSelected;
    private boolean themeUpdatePending;
    private record ThemeSelection(int inputDot, int inputMark, int outputDot, int outputMark, int rootDot, int rootMark, long generation) {}
    private final PropertyChangeListener themeListener = event -> {
        if ("lookAndFeel".equals(event.getPropertyName()) || "Panel.background".equals(event.getPropertyName())) {
            if (SwingUtilities.isEventDispatchThread()) queueThemeRefresh();
            else SwingUtilities.invokeLater(this::queueThemeRefresh);
        }
    };

    private void queueThemeRefresh() {
        if (closed || themeUpdatePending) return;
        ThemeSelection saved = new ThemeSelection(input.getCaret().getDot(), input.getCaret().getMark(),
            kotlin.getCaret().getDot(), kotlin.getCaret().getMark(), root.getCaret().getDot(), root.getCaret().getMark(), generation);
        java.util.Map<JViewport, Point> scrollPositions = new java.util.IdentityHashMap<>();
        rememberScrollPositions(this, scrollPositions);
        themeUpdatePending = true;
        SwingUtilities.invokeLater(() -> {
            themeUpdatePending = false;
            if (closed) return;
            refreshTheme();
            if (generation == saved.generation()) {
                restoreSelection(input, saved.inputDot(), saved.inputMark());
                restoreSelection(kotlin, saved.outputDot(), saved.outputMark());
                restoreSelection(root, saved.rootDot(), saved.rootMark());
                scrollPositions.forEach(JViewport::setViewPosition);
            }
        });
    }
    private static void rememberScrollPositions(Container parent, java.util.Map<JViewport, Point> positions) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JViewport viewport) positions.put(viewport, viewport.getViewPosition());
            if (component instanceof Container child) rememberScrollPositions(child, positions);
        }
    }
    private static void restoreSelection(JTextComponent text, int dot, int mark) {
        text.setCaretPosition(Math.min(mark, text.getDocument().getLength()));
        text.moveCaretPosition(Math.min(dot, text.getDocument().getLength()));
    }

    public LogcatPanel() {
        super(new BorderLayout()); setBackground(Color.WHITE); setMinimumSize(new Dimension(280, 300));
        JPanel header = new JPanel(new BorderLayout(8, 8)); header.setBackground(Color.WHITE);
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(12, 14, 10, 14)));
        JPanel brand = flow();
        JLabel logo = new JLabel(" {} "); logo.setOpaque(true); logo.setBackground(ACCENT); logo.setForeground(Color.WHITE); logo.setFont(MONO.deriveFont(Font.BOLD, 16));
        logo.putClientProperty("logcat2kt.role", "logo");
        brand.add(logo); JLabel title = label("Logcat → Kotlin"); title.setFont(UI.deriveFont(Font.BOLD, 15)); brand.add(title);
        JPanel top = new JPanel(new WrapLayout()); top.setOpaque(false); top.add(brand);
        JPanel actions = flow(); JButton sample = button("载入示例", false), clear = button("清空", false);
        actions.add(sample); actions.add(clear); top.add(actions); header.add(top, BorderLayout.NORTH);
        JPanel options = new JPanel(new WrapLayout()); options.setOpaque(false);
        root.setFont(MONO); root.setForeground(INK); root.setBackground(Color.WHITE); root.setCaretColor(ACCENT);
        root.setBorder(new CompoundBorder(new LineBorder(LINE, 1, true), new EmptyBorder(4, 6, 4, 6)));
        annotation.setSelectedIndex(1);
        options.add(option("根类名", root)); options.add(option("注解", annotation));
        options.add(option("可空策略", nullable)); options.add(option("命名", naming));
        timestamps.setOpaque(false); timestamps.setFont(UI); timestamps.setForeground(MUTED); options.add(timestamps);
        header.add(options, BorderLayout.CENTER); add(header, BorderLayout.NORTH);

        input.setFont(MONO); input.setBackground(Color.WHITE); input.setForeground(INK); input.setCaretColor(ACCENT);
        input.setMargin(new Insets(12, 14, 12, 14)); input.setTabSize(2); input.setLineWrap(true); input.setWrapStyleWord(false);
        input.getAccessibleContext().setAccessibleName("Logcat 日志输入");
        input.setToolTipText("粘贴 logcat、JSON、toString 或 Protobuf；支持截断拼接，完全离线处理");
        ((AbstractDocument) input.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs) throws BadLocationException { replace(fb, offset, 0, text, attrs); }
            @Override public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (fb.getDocument().getLength() - length + (text == null ? 0 : text.length()) > ConversionEngine.MAX_INPUT) {
                    status.setText("输入超过 200 万字符，请分段粘贴"); return;
                }
                super.replace(fb, offset, length, text, attrs);
            }
        });
        JPanel inputHead = flow(); inputHead.add(label("输入")); JButton paste = button("粘贴", false); inputHead.add(paste);
        JPanel left = pane(inputHead, scroll(input));

        tree.setUI(new BasicTreeUI()); tree.setFont(MONO); tree.setBackground(Color.WHITE); tree.setForeground(INK);
        tree.setRootVisible(false); tree.setShowsRootHandles(true); tree.setRowHeight(23); tree.setBorder(new EmptyBorder(10, 10, 10, 10));
        tree.setCellRenderer(new JsonRenderer()); tree.setToggleClickCount(1);
        tree.getAccessibleContext().setAccessibleName("格式化 JSON 树");
        kotlin.setEditable(false); kotlin.setFont(MONO); kotlin.setBackground(Color.WHITE); kotlin.setForeground(INK);
        kotlin.setMargin(new Insets(12, 14, 12, 14)); kotlin.getAccessibleContext().setAccessibleName("Kotlin 输出");
        empty.setForeground(new Color(0x9aa1ad)); empty.setFont(UI); empty.setOpaque(true); empty.setBackground(Color.WHITE);
        output.add(scroll(tree), "json"); output.add(scroll(kotlin), "kotlin"); output.add(empty, "empty");
        JPanel outputHead = new JPanel(new WrapLayout()); outputHead.setOpaque(false);
        outputHead.add(jsonTab); outputHead.add(kotlinTab);
        JButton fold = button("全部折叠", false), unfold = button("全部展开", false);
        outputHead.add(fold); outputHead.add(unfold); outputHead.add(copy);
        JPanel right = pane(outputHead, output);
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, left, right); split.setResizeWeight(.45); split.setContinuousLayout(true);
        split.setUI(new BasicSplitPaneUI()); split.setBorder(null); split.setDividerSize(5); split.setBackground(LINE);
        add(split, BorderLayout.CENTER);
        status.setFont(UI.deriveFont(11f)); status.setForeground(MUTED); status.setOpaque(true); status.setBackground(new Color(0xfbfbfc));
        status.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, LINE), new EmptyBorder(6, 14, 6, 14))); add(status, BorderLayout.SOUTH);
        debounce = new Timer(180, event -> convert()); debounce.setRepeats(false);
        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { schedule(); }
            public void removeUpdate(DocumentEvent e) { schedule(); }
            public void changedUpdate(DocumentEvent e) { schedule(); }
        };
        input.getDocument().addDocumentListener(listener); root.getDocument().addDocumentListener(listener);
        annotation.addActionListener(e -> schedule()); nullable.addActionListener(e -> schedule());
        naming.addActionListener(e -> schedule()); timestamps.addActionListener(e -> schedule());
        sample.addActionListener(e -> input.setText(ConversionEngine.resource("/web/sample.log")));
        clear.addActionListener(e -> { input.setText(""); input.requestFocusInWindow(); });
        paste.addActionListener(e -> paste()); copy.addActionListener(e -> copy());
        jsonTab.addActionListener(e -> selectTab(false)); kotlinTab.addActionListener(e -> selectTab(true));
        fold.addActionListener(e -> { for (int i = tree.getRowCount() - 1; i >= 0; i--) tree.collapseRow(i); });
        unfold.addActionListener(e -> { for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i); });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                int orientation = getWidth() > 900 ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT;
                if (split.getOrientation() != orientation) { split.setOrientation(orientation); split.setDividerLocation(.45); }
            }
        });
        selectTab(false); copy.setEnabled(false);
        input.setText(ConversionEngine.resource("/web/sample.log")); input.setCaretPosition(0);
        UIManager.addPropertyChangeListener(themeListener);
        UIManager.getDefaults().addPropertyChangeListener(themeListener);
        refreshTheme();
        SwingUtilities.invokeLater(() -> { if (!closed) split.setDividerLocation(.45); });
    }

    private void schedule() {
        if (closed) return;
        generation++; if (pending != null) pending.cancel(true);
        result = null; copy.setEnabled(false); empty.setText(input.getText().isBlank() ? "等待输入…" : "正在解析…");
        ((CardLayout) output.getLayout()).show(output, "empty");
        status.setText(input.getText().isBlank() ? "就绪" : "正在解析…");
        debounce.restart();
    }
    /** A new selection replaces the current batch, matching paste semantics. Never alters the clipboard. */
    public void acceptLog(String text) {
        if (closed) return;
        if (text.length() > ConversionEngine.MAX_INPUT) { status.setText("所选日志超过 200 万字符，请减少选择范围"); return; }
        input.setText(text); input.setCaretPosition(0);
        debounce.stop(); convert(); input.requestFocusInWindow();
    }
    private void convert() {
        if (closed) return;
        String raw = input.getText(); if (raw.isBlank()) return;
        long request = generation;
        var options = new ConversionEngine.Options(root.getText(), new String[]{"none", "gson", "kotlinx", "moshi"}[annotation.getSelectedIndex()],
            new String[]{"all", "smart", "none"}[nullable.getSelectedIndex()], naming.getSelectedIndex() == 0 ? "camel" : "keep", timestamps.isSelected());
        pending = executor.submit(() -> {
            try {
                var parsed = engine.convert(raw, options);
                SwingUtilities.invokeLater(() -> { if (!closed && generation == request) showResult(parsed); });
            } catch (CancellationException ignored) {
            } catch (RuntimeException error) {
                SwingUtilities.invokeLater(() -> {
                    if (closed || generation != request) return;
                    empty.setText("无法完成解析，请检查内容或减少日志长度");
                    status.setText("解析失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                });
            }
        });
    }
    private void showResult(ConversionEngine.Result parsed) {
        result = parsed;
        if (parsed.count() == 0) {
            empty.setText("未找到 JSON / toString / Protobuf 结构");
            status.setText("日志行 " + parsed.lines() + "  ·  可识别条目 " + parsed.entries() + "  ·  结构 0"); return;
        }
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("JSON");
        for (var group : parsed.roots()) {
            if (parsed.roots().size() == 1) for (var node : group.children()) rootNode.add(treeNode(node));
            else rootNode.add(treeNode(group));
        }
        if (parsed.limited()) rootNode.add(new DefaultMutableTreeNode("树视图最多显示 5000 个节点；复制仍包含完整结果"));
        tree.setModel(new DefaultTreeModel(rootNode));
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath path = tree.getPathForRow(i); if (path != null && path.getPathCount() <= 3) tree.expandPath(path);
        }
        setKotlin(parsed.kotlin()); copy.setEnabled(true); selectTab(kotlinSelected);
        String info = "日志行 " + parsed.lines() + "  ·  条目 " + parsed.entries() + "  ·  结构 " + parsed.count();
        if (parsed.merged() > 0) info += "  ·  自动拼接 " + parsed.merged() + " 次";
        if (parsed.lost() > 0) info += "  ·  日志缺失 " + parsed.lost() + " 处已跳过";
        if (parsed.limited()) info += "  ·  树视图已限量显示";
        status.setText(info); status.setToolTipText(info);
    }
    private void selectTab(boolean isKotlin) {
        kotlinSelected = isKotlin;
        jsonTab.putClientProperty("logcat2kt.active", !isKotlin); kotlinTab.putClientProperty("logcat2kt.active", isKotlin);
        jsonTab.setBackground(isKotlin ? PanelTheme.current().panel() : PanelTheme.current().soft());
        kotlinTab.setBackground(isKotlin ? PanelTheme.current().soft() : PanelTheme.current().panel());
        ((CardLayout) output.getLayout()).show(output, result == null || result.count() == 0 ? "empty" : isKotlin ? "kotlin" : "json");
    }
    private void paste() {
        try {
            Object value = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (value instanceof String text) input.setText(text);
        } catch (Exception error) { status.setText("无法读取剪贴板，请在输入区使用 Ctrl/Cmd+V"); }
    }
    private void copy() {
        if (result == null) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(kotlinSelected ? result.kotlin() : result.json()), null);
            status.setText("已复制 " + (kotlinSelected ? "Kotlin" : "格式化 JSON"));
        } catch (IllegalStateException error) { status.setText("剪贴板正忙，请重试"); }
    }
    private static final Pattern TOKENS = Pattern.compile("//[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|@[\\w.]+|\\b(?:data|class|val|var|import|package|null|true|false)\\b|\\b(?:String|Int|Long|Double|Boolean|List|Any)\\b");
    private void setKotlin(String text) {
        kotlin.setText(text); kotlin.setCaretPosition(0);
        highlightKotlin();
    }
    private void highlightKotlin() {
        String text = kotlin.getText();
        var palette = PanelTheme.current();
        StyledDocument doc = kotlin.getStyledDocument();
        SimpleAttributeSet plain = new SimpleAttributeSet(); StyleConstants.setForeground(plain, palette.text());
        doc.setCharacterAttributes(0, doc.getLength(), plain, false);
        if (text.length() > 200_000) return;
        Matcher tokens = TOKENS.matcher(text);
        while (tokens.find()) {
            String token = tokens.group(); Color color = token.startsWith("//") ? palette.muted() : token.startsWith("\"") ? palette.string()
                : token.startsWith("@") ? palette.number() : Character.isUpperCase(token.charAt(0)) ? palette.type() : palette.keyword();
            SimpleAttributeSet style = new SimpleAttributeSet(); StyleConstants.setForeground(style, color);
            doc.setCharacterAttributes(tokens.start(), tokens.end() - tokens.start(), style, false);
        }
    }
    private static DefaultMutableTreeNode treeNode(ConversionEngine.Node node) {
        var value = new DefaultMutableTreeNode(node);
        for (var child : node.children()) value.add(treeNode(child)); return value;
    }
    private void refreshTheme() {
        PanelTheme.apply(this);
        status.setBackground(PanelTheme.current().status());
        highlightKotlin(); selectTab(kotlinSelected); revalidate(); repaint();
    }
    @Override public void close() {
        closed = true; generation++; debounce.stop(); if (pending != null) pending.cancel(true); executor.shutdownNow();
        UIManager.removePropertyChangeListener(themeListener);
        UIManager.getDefaults().removePropertyChangeListener(themeListener);
    }

    private static JPanel flow() { JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); panel.setOpaque(false); return panel; }
    private static JLabel label(String text) { JLabel label = new JLabel(text); label.setFont(UI); label.setForeground(MUTED); return label; }
    private static JPanel option(String text, JComponent value) { JPanel panel = flow(); JLabel label = label(text); label.setLabelFor(value); panel.add(label); panel.add(value); return panel; }
    private static JComboBox<String> combo(String... options) {
        JComboBox<String> combo = new JComboBox<>(options); combo.setFont(UI); combo.setForeground(INK); combo.setBackground(Color.WHITE);
        combo.setUI(new BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                JButton arrow = new JButton("⌄"); arrow.setUI(new BasicButtonUI()); arrow.setFont(UI); arrow.setForeground(MUTED);
                arrow.setBackground(Color.WHITE); arrow.setBorder(new EmptyBorder(0, 5, 2, 5)); return arrow;
            }
        }); combo.setBorder(new CompoundBorder(new LineBorder(LINE, 1, true), new EmptyBorder(2, 4, 2, 2))); return combo;
    }
    private static JButton button(String text, boolean primary) {
        JButton button = new JButton(text); button.setUI(new BasicButtonUI()); button.setFont(UI); button.setForeground(primary ? Color.WHITE : INK);
        button.putClientProperty("logcat2kt.primary", primary);
        button.setBackground(primary ? ACCENT : Color.WHITE); button.setOpaque(true); button.setFocusPainted(true);
        button.setBorder(new CompoundBorder(new LineBorder(primary ? ACCENT : LINE, 1, true), new EmptyBorder(5, 9, 5, 9)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return button;
    }
    private static JScrollPane scroll(JComponent component) {
        JScrollPane scroll = new JScrollPane(component); scroll.setBorder(null); scroll.getViewport().setBackground(Color.WHITE);
        for (JScrollBar bar : new JScrollBar[]{scroll.getVerticalScrollBar(), scroll.getHorizontalScrollBar()}) {
            bar.setUI(new BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() { thumbColor = new Color(0xd8dce3); trackColor = Color.WHITE; }
                @Override protected JButton createDecreaseButton(int orientation) { return invisibleButton(); }
                @Override protected JButton createIncreaseButton(int orientation) { return invisibleButton(); }
                private JButton invisibleButton() { JButton button = new JButton(); button.setPreferredSize(new Dimension(0, 0)); return button; }
                @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle bounds) {
                    Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(thumbColor); g.fillRoundRect(bounds.x + 2, bounds.y + 2, Math.max(3, bounds.width - 4), Math.max(3, bounds.height - 4), 8, 8); g.dispose();
                }
            });
            if (bar.getOrientation() == Adjustable.VERTICAL) bar.setPreferredSize(new Dimension(10, 0));
            else bar.setPreferredSize(new Dimension(0, 10));
        }
        scroll.setMinimumSize(new Dimension(0, 70)); return scroll;
    }
    private static JPanel pane(JPanel head, JComponent body) {
        JPanel pane = new JPanel(new BorderLayout()); pane.setBackground(Color.WHITE);
        head.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(7, 8, 7, 8)));
        pane.add(head, BorderLayout.NORTH); pane.add(body, BorderLayout.CENTER); pane.setMinimumSize(new Dimension(0, 100)); return pane;
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static final class JsonRenderer extends DefaultTreeCellRenderer {
        JsonRenderer() { setBackgroundNonSelectionColor(Color.WHITE); setTextNonSelectionColor(INK); setBackgroundSelectionColor(SOFT); setTextSelectionColor(INK); setBorderSelectionColor(LINE); }
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            var palette = PanelTheme.current();
            setBackgroundNonSelectionColor(palette.panel()); setTextNonSelectionColor(palette.text());
            setBackgroundSelectionColor(palette.soft()); setTextSelectionColor(palette.text()); setBorderSelectionColor(palette.line());
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            setFont(MONO); setIcon(null);
            Object data = ((DefaultMutableTreeNode) value).getUserObject();
            if (data instanceof ConversionEngine.Node node) {
                String text = node.value(); if (text.length() > 240) text = text.substring(0, 240) + "…";
                String color = PanelTheme.hex(switch (node.type()) { case "string" -> palette.string(); case "number" -> palette.number(); case "null", "boolean" -> palette.keyword(); default -> palette.muted(); });
                String key = node.key().isEmpty() ? "" : "<font color='" + PanelTheme.hex(palette.key()) + "'>" + escape(node.key()) + "</font>: ";
                setText("<html>" + key + "<font color='" + color + "'>" + (node.type().equals("string") ? "&quot;" : "") + escape(text).replace("\n", "\\n") + (node.type().equals("string") ? "&quot;" : "") + "</font></html>");
            }
            return this;
        }
    }
    /** FlowLayout whose preferred height follows the available width of a narrow tool window. */
    private static final class WrapLayout extends FlowLayout {
        WrapLayout() { super(FlowLayout.LEFT, 6, 5); }
        @Override public Dimension preferredLayoutSize(Container target) {
            int width = target.getWidth();
            if (width <= 0) return super.preferredLayoutSize(target);
            Insets insets = target.getInsets(); int available = Math.max(1, width - insets.left - insets.right - 2 * getHgap());
            int x = 0, rowHeight = 0, height = 0;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) continue;
                Dimension size = child.getPreferredSize();
                if (x > 0 && x + getHgap() + size.width > available) { height += rowHeight + getVgap(); x = 0; rowHeight = 0; }
                x += (x == 0 ? 0 : getHgap()) + size.width; rowHeight = Math.max(rowHeight, size.height);
            }
            return new Dimension(width, height + rowHeight + insets.top + insets.bottom + 2 * getVgap());
        }
    }
}
