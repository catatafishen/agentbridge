package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.util.messages.Topic;

/**
 * Message bus topic for review session state changes.
 * Fired when: session starts, session ends, file accepted/rejected, bulk operations.
 * Listeners should rebuild their UI from {@link AgentEditSession#getReviewItems()}.
 */
public interface ReviewSessionTopic {

    @Topic.ProjectLevel
    Topic<ReviewSessionTopic> TOPIC = Topic.create("AgentBridge.ReviewSession", ReviewSessionTopic.class);

    /**
     * Called whenever the review session state changes. Listeners should query the session
     * for the current list of review items rather than relying on parameters.
     */
    void reviewStateChanged();
}
