package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import org.jetbrains.annotations.Nullable;

/**
 * Gets recent IntelliJ balloon notifications.
 */
public final class GetNotificationsTool extends InfrastructureTool {

    public GetNotificationsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_notifications";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Notifications";
    }

    @Override
    public @NotNull String description() {
        return "Get recent IntelliJ balloon notifications";
    }

    

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }
@Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        StringBuilder result = new StringBuilder();
        try {
            var notifications = com.intellij.notification.NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(com.intellij.notification.Notification.class, project);
            if (notifications.length == 0) {
                return "No recent notifications.";
            }
            for (var notification : notifications) {
                result.append("[").append(notification.getType()).append("] ");
                if (!notification.getTitle().isEmpty()) {
                    result.append(notification.getTitle()).append(": ");
                }
                result.append(notification.getContent()).append("\n");
            }
        } catch (Exception e) {
            return "Could not read notifications: " + e.getMessage();
        }
        return result.toString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }
}
