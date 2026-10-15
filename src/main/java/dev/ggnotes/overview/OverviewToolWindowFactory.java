package dev.ggnotes.overview;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Java on purpose: {@link ToolWindowFactory}'s default methods are
 * {@code @ApiStatus.Internal}, and a Kotlin implementor would get bridge
 * methods for every one of them (verifier: INTERNAL_API_USAGES). See CLAUDE.md.
 */
public final class OverviewToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        OverviewToolWindowPanel panel = new OverviewToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        Disposer.register(content, panel);
        toolWindow.getContentManager().addContent(content);
    }
}
