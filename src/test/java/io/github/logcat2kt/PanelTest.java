package io.github.logcat2kt;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.swing.plaf.metal.MetalLookAndFeel;

/** Exercises actual Swing input, async conversion, tabs, clearing and narrow/wide layout. */
public final class PanelTest {
    private static LogcatPanel panel;
    public static void main(String[] args) throws Exception {
        LookAndFeel originalLookAndFeel = UIManager.getLookAndFeel();
        SwingUtilities.invokeAndWait(() -> setLookAndFeel(false));
        SwingUtilities.invokeAndWait(() -> { panel = new LogcatPanel(); panel.setSize(1120, 740); layout(panel); });
        try {
            awaitCopy();
            SwingUtilities.invokeAndWait(() -> {
                JButton kotlin = findButton(panel, "Kotlin"); kotlin.doClick();
                if (!findButton(panel, "复制").isEnabled()) throw new AssertionError("copy not enabled");
                panel.acceptLog("D/Api: {\"user_id\":9007199254740993123,\"name\":\"张三\"}");
                if (findButton(panel, "复制").isEnabled()) throw new AssertionError("stale output remains copyable");
            });
            awaitCopy();
            SwingUtilities.invokeAndWait(() -> {
                JTextPane text = find(panel, JTextPane.class);
                if (!text.getText().contains("userId: Long?")) throw new AssertionError("selection was not parsed");
                findButton(panel, "清空").doClick();
                if (findButton(panel, "复制").isEnabled()) throw new AssertionError("clear retained copy");
                findButton(panel, "载入示例").doClick();
            });
            awaitCopy();
            Files.createDirectories(Path.of("build/previews"));
            SwingUtilities.invokeAndWait(() -> {
                findButton(panel, "格式化 JSON").doClick();
                render(1120, 740, "wide-json");
                render(460, 850, "narrow-json");
                render(320, 850, "compact-json");
                findButton(panel, "Kotlin").doClick();
                render(460, 850, "narrow-kotlin");
            });
            String[] preserved = new String[2];
            SwingUtilities.invokeAndWait(() -> {
                JTextPane text = find(panel, JTextPane.class); text.select(10, 30);
                preserved[0] = find(panel, JTextArea.class).getText(); preserved[1] = text.getText();
                setLookAndFeel(true); SwingUtilities.updateComponentTreeUI(panel);
            });
            drainThemeEvents();
            SwingUtilities.invokeAndWait(() -> {
                JTextPane text = find(panel, JTextPane.class);
                if (!panel.getBackground().equals(PanelTheme.current().panel()) || panel.getBackground().getRed() > 100) throw new AssertionError("dark theme not applied live");
                if (!text.getText().equals(preserved[1]) || !find(panel, JTextArea.class).getText().equals(preserved[0])) throw new AssertionError("theme switch changed content");
                if (text.getSelectionStart() != 10 || text.getSelectionEnd() != 30) throw new AssertionError("theme switch lost selection");
                var keyword = text.getStyledDocument().getCharacterElement(text.getText().indexOf("data class")).getAttributes();
                if (!javax.swing.text.StyleConstants.getForeground(keyword).equals(PanelTheme.current().keyword())) throw new AssertionError("Kotlin highlight not recolored");
                render(460, 850, "dark-kotlin");
                findButton(panel, "格式化 JSON").doClick(); render(460, 850, "dark-json");
                setLookAndFeel(false); SwingUtilities.updateComponentTreeUI(panel);
            });
            drainThemeEvents();
            SwingUtilities.invokeAndWait(() -> {
                if (panel.getBackground().getRed() < 240) throw new AssertionError("light theme not restored live");
                if (!findButton(panel, "复制").isEnabled()) throw new AssertionError("theme change invalidated conversion");
            });
            System.out.println("Swing UI checks passed, including live light/dark/light switching; previews in build/previews");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                panel.close();
                try { UIManager.setLookAndFeel(originalLookAndFeel); } catch (UnsupportedLookAndFeelException e) { throw new RuntimeException(e); }
            });
        }
    }
    private static void drainThemeEvents() throws Exception {
        for (int i = 0; i < 4; i++) SwingUtilities.invokeAndWait(() -> {});
    }
    private static void setLookAndFeel(boolean dark) {
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel() {
                @Override public UIDefaults getDefaults() {
                    UIDefaults defaults = super.getDefaults();
                    defaults.put("Panel.background", dark ? new Color(0x2b2d30) : Color.WHITE);
                    return defaults;
                }
            });
        } catch (UnsupportedLookAndFeelException e) { throw new RuntimeException(e); }
    }
    private static void awaitCopy() throws Exception {
        long until = System.nanoTime() + 12_000_000_000L;
        while (System.nanoTime() < until) {
            boolean[] ready = {false};
            SwingUtilities.invokeAndWait(() -> ready[0] = findButton(panel, "复制").isEnabled());
            if (ready[0]) return;
            Thread.sleep(40);
        }
        throw new AssertionError("UI conversion did not finish");
    }
    private static JButton findButton(Container parent, String name) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JButton button && button.getText().equals(name)) return button;
            if (c instanceof Container child) { JButton found = findButton(child, name); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T find(Container parent, Class<T> type) {
        for (Component c : parent.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container child) { T found = find(child, type); if (found != null) return found; }
        }
        return null;
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component c : container.getComponents()) if (c instanceof Container child) layout(child);
    }
    private static void render(int width, int height, String name) {
        panel.setSize(width, height);
        for (var listener : panel.getComponentListeners()) listener.componentResized(new java.awt.event.ComponentEvent(panel, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
        for (int i = 0; i < 4; i++) layout(panel);
        JSplitPane split = find(panel, JSplitPane.class); split.setDividerLocation(.45); layout(panel);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        panel.printAll(graphics); graphics.dispose();
        try { ImageIO.write(image, "png", Path.of("build/previews/" + name + ".png").toFile()); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
}
