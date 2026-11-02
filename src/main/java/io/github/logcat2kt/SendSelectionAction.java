package io.github.logcat2kt;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;

/** Uses Logcat's editor data key, avoiding a binary dependency on Android implementation classes. */
public final class SendSelectionAction extends DumbAwareAction {
    // LogcatMainPanel intentionally does not publish CommonDataKeys.EDITOR (2025.1.1 onward).
    private static final DataKey<Editor> LOGCAT_EDITOR = DataKey.create("LogcatEditor");
    private static Editor editor(AnActionEvent event) {
        Editor logcat = event.getData(LOGCAT_EDITOR);
        return logcat != null ? logcat : event.getData(CommonDataKeys.EDITOR);
    }
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(AnActionEvent event) {
        Editor editor = editor(event);
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && editor != null
            && editor.getSelectionModel().hasSelection());
    }
    @Override public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = editor(event);
        if (project == null || editor == null) return;
        // Capture before activation moves focus away from Logcat.
        String selected = editor.getSelectionModel().getSelectedText();
        if (selected == null || selected.isBlank()) return;
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Logcat2Kt");
        if (window == null) return;
        window.activate(() -> {
            if (project.isDisposed()) return;
            for (Content content : window.getContentManager().getContents()) {
                if (content.getComponent() instanceof LogcatPanel panel) {
                    panel.acceptLog(selected);
                    window.getContentManager().setSelectedContent(content);
                    break;
                }
            }
        });
    }
}
