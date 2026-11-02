package io.github.logcat2kt;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/** Keep the IDE integration small: the parser and UI only depend on Java. */
public final class LogcatToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        LogcatPanel panel = new LogcatPanel();
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel::close);
        toolWindow.getContentManager().addContent(content);
    }
}
