package io.github.logcat2kt;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.*;
import javax.swing.text.JTextComponent;
import java.awt.*;

/** Pure Swing theme adapter: no dependency on IntelliJ's internal theme implementation. */
final class PanelTheme {
    record Palette(Color panel, Color line, Color text, Color muted, Color accent, Color soft,
                   Color status, Color thumb, Color key, Color string, Color number, Color keyword, Color type) {}
    private static Color c(int rgb) { return new Color(rgb); }
    private static final Palette LIGHT = new Palette(c(0xffffff), c(0xe4e7ec), c(0x1f2328), c(0x6b7280), c(0x4f46e5), c(0xeef0ff),
        c(0xfbfbfc), c(0xd8dce3), c(0x1a56db), c(0x0a7d4b), c(0xb45309), c(0x9333ea), c(0x0e7490));
    private static final Palette DARK = new Palette(c(0x202126), c(0x3d404a), c(0xe5e7eb), c(0xa6adbb), c(0x635bdf), c(0x383454),
        c(0x27292f), c(0x555a66), c(0x8ab4ff), c(0x84c99a), c(0xefb369), c(0xc6a0f6), c(0x7dcad6));
    static Palette current() {
        Color background = UIManager.getColor("Panel.background");
        return background != null && (background.getRed() * 299 + background.getGreen() * 587 + background.getBlue() * 114) < 128000 ? DARK : LIGHT;
    }
    static String hex(Color color) { return String.format("#%06x", color.getRGB() & 0xffffff); }

    static void apply(Component component) {
        Palette p = current();
        component.setBackground(p.panel()); component.setForeground(p.text());
        if (component instanceof JComponent jc) {
            if (jc.getBorder() != null) jc.setBorder(border(jc.getBorder(), p.line()));
            if ("logo".equals(jc.getClientProperty("logcat2kt.role"))) {
                jc.setBackground(p.accent()); jc.setForeground(Color.WHITE);
            } else if (component instanceof JLabel || component instanceof JCheckBox) jc.setForeground(p.muted());
        }
        if (component instanceof JButton button && button.getClientProperty("logcat2kt.primary") != null) {
            button.setUI(new BasicButtonUI() {
                @Override protected void paintButtonPressed(Graphics graphics, AbstractButton b) {
                    graphics.setColor(current().soft()); graphics.fillRect(0, 0, b.getWidth(), b.getHeight());
                }
                @Override protected void paintText(Graphics graphics, AbstractButton b, Rectangle r, String text) {
                    // BasicButtonUI otherwise uses the light LAF's disabled foreground.
                    graphics.setColor(b.isEnabled() ? b.getForeground() : current().muted());
                    graphics.drawString(text, r.x, r.y + graphics.getFontMetrics().getAscent());
                }
            });
            boolean primary = Boolean.TRUE.equals(button.getClientProperty("logcat2kt.primary"));
            boolean active = Boolean.TRUE.equals(button.getClientProperty("logcat2kt.active"));
            button.setBackground(primary ? p.accent() : active ? p.soft() : p.panel());
            button.setForeground(primary ? Color.WHITE : p.text());
            button.setBorder(new CompoundBorder(new LineBorder(primary ? p.accent() : p.line(), 1, true), new EmptyBorder(5, 9, 5, 9)));
        } else if (component instanceof JTextComponent text) {
            text.setCaretColor(p.text()); text.setSelectionColor(p.soft()); text.setSelectedTextColor(p.text()); text.setDisabledTextColor(p.muted());
        } else if (component instanceof JComboBox<?> combo) {
            combo.setUI(new BasicComboBoxUI() {
                @Override public void paintCurrentValueBackground(Graphics graphics, Rectangle bounds, boolean focus) {
                    graphics.setColor(current().panel()); graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                }
                @Override public void paintCurrentValue(Graphics graphics, Rectangle bounds, boolean focus) {
                    graphics.setFont(combo.getFont()); graphics.setColor(combo.isEnabled() ? current().text() : current().muted());
                    FontMetrics metrics = graphics.getFontMetrics();
                    Rectangle textBounds = new Rectangle(), iconBounds = new Rectangle();
                    String value = String.valueOf(combo.getSelectedItem());
                    String clipped = SwingUtilities.layoutCompoundLabel(metrics, value, null, SwingConstants.CENTER, SwingConstants.LEFT,
                        SwingConstants.CENTER, SwingConstants.RIGHT, bounds, iconBounds, textBounds, 0);
                    graphics.drawString(clipped, textBounds.x, textBounds.y + metrics.getAscent());
                }
                @Override protected JButton createArrowButton() {
                    JButton button = new JButton("⌄"); button.setUI(new BasicButtonUI()); button.setForeground(current().muted());
                    button.setBackground(current().panel()); button.setBorder(new EmptyBorder(0, 5, 2, 5)); return button;
                }
            });
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, selected, focus);
                    setBackground(selected ? current().soft() : current().panel()); setForeground(current().text()); return this;
                }
            });
        } else if (component instanceof JScrollBar bar) {
            bar.setUI(new BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() { thumbColor = current().thumb(); trackColor = current().panel(); }
                @Override protected JButton createDecreaseButton(int orientation) { return emptyButton(); }
                @Override protected JButton createIncreaseButton(int orientation) { return emptyButton(); }
                private JButton emptyButton() { JButton b = new JButton(); b.setPreferredSize(new Dimension()); return b; }
                @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle bounds) {
                    Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(current().thumb()); g.fillRoundRect(bounds.x + 2, bounds.y + 2, Math.max(3, bounds.width - 4), Math.max(3, bounds.height - 4), 8, 8); g.dispose();
                }
            });
        } else if (component instanceof JScrollPane scroll) {
            scroll.setBorder(null);
        } else if (component instanceof JSplitPane split) {
            int divider = split.getDividerLocation();
            split.setUI(new BasicSplitPaneUI()); split.setBorder(null); split.setDividerSize(5); split.setDividerLocation(divider);
            split.setBackground(p.line());
        } else if (component instanceof BasicSplitPaneDivider divider) {
            divider.setBackground(p.line()); divider.setBorder(null);
        }
        if (component instanceof JCheckBox check) {
            check.setIcon(new Icon() {
                public int getIconWidth() { return 15; }
                public int getIconHeight() { return 15; }
                public void paintIcon(Component c, Graphics graphics, int x, int y) {
                    Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(check.isSelected() ? current().accent() : current().panel()); g.fillRoundRect(x, y, 14, 14, 4, 4);
                    g.setColor(check.isSelected() ? current().accent() : current().muted()); g.drawRoundRect(x, y, 14, 14, 4, 4);
                    if (check.isSelected()) { g.setColor(Color.WHITE); g.setStroke(new BasicStroke(1.6f)); g.drawLine(x + 3, y + 7, x + 6, y + 10); g.drawLine(x + 6, y + 10, x + 11, y + 4); }
                    g.dispose();
                }
            });
        }
        if (component instanceof Container parent) for (Component child : parent.getComponents()) apply(child);
    }
    private static Border border(Border b, Color line) {
        if (b instanceof CompoundBorder compound) return new CompoundBorder(border(compound.getOutsideBorder(), line), border(compound.getInsideBorder(), line));
        if (b instanceof LineBorder lb) return new LineBorder(line, lb.getThickness(), lb.getRoundedCorners());
        if (b instanceof MatteBorder mb) { Insets i = mb.getBorderInsets(); return new MatteBorder(i.top, i.left, i.bottom, i.right, line); }
        return b;
    }
}
