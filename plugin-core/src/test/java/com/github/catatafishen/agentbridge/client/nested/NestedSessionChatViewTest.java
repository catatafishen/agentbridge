package com.github.catatafishen.agentbridge.client.nested;

import com.github.catatafishen.agentbridge.ui.ChatPanelApi;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NestedSessionChatViewTest {

    private static NestedSessionRunner.Result completed(String text) {
        return new NestedSessionRunner.Result(NestedSessionRunner.Status.COMPLETED, text, null);
    }

    private static NestedSessionRunner.Result failed(String error, String partial) {
        return new NestedSessionRunner.Result(NestedSessionRunner.Status.FAILED, partial, error);
    }

    // ── what is shown when the session ends ──

    @Test
    void aCompletedSessionShowsItsAnswerWhenNothingWasStreamed() {
        assertEquals("the answer", NestedSessionChatView.resultToShow(completed("the answer"), false));
    }

    @Test
    void aCompletedSessionDoesNotShowItsAnswerAgainWhenItWasStreamed() {
        assertNull(NestedSessionChatView.resultToShow(completed("the answer"), true));
    }

    @Test
    void aFailedSessionShowsWhyAndNotTheStreamedTextAgain() {
        assertEquals("rate limited", NestedSessionChatView.resultToShow(failed("rate limited", "half"), true));
    }

    @Test
    void aFailedSessionShowsWhyAndWhatItAnsweredWhenNothingWasStreamed() {
        assertEquals("timed out\n\nhalf",
            NestedSessionChatView.resultToShow(failed("timed out", "half"), false));
    }

    @Test
    void aFailedSessionWithoutAnswerShowsJustWhy() {
        assertEquals("not started", NestedSessionChatView.resultToShow(failed("not started", ""), false));
    }

    // ── what the panel is told ──

    @Test
    void theEntryStartsWithoutAStatusSoItIsNotDrawnAsFailed() {
        ChatPanelApi panel = mock(ChatPanelApi.class);

        new NestedSessionChatView(panel).start("Koog", "Review", "do it");

        ArgumentCaptor<ChatPanelApi.SubAgentInitialState> state =
            ArgumentCaptor.forClass(ChatPanelApi.SubAgentInitialState.class);
        verify(panel).addSubAgentEntry(anyString(), eq("Koog"), eq("Review"), eq("do it"), state.capture());
        // A status makes the panel draw a result at once; one that is neither "complete" nor "completed" reads as a
        // failure, which is what a session that has produced nothing yet looked like.
        assertNull(state.getValue().getStatus());
        assertNull(state.getValue().getResult());
    }

    @Test
    void anAnswerThatWasStreamedIsNotSentAgainAtTheEnd() {
        ChatPanelApi panel = mock(ChatPanelApi.class);
        NestedSessionChatView view = new NestedSessionChatView(panel);
        view.start("Koog", "Review", "do it");

        view.onText("the ", null);
        view.onText("answer", null);
        view.finish(completed("the answer"));

        verify(panel).appendSubAgentText(anyString(), eq("the "), eq(null));
        verify(panel).appendSubAgentText(anyString(), eq("answer"), eq(null));
        verify(panel).updateSubAgentResult(anyString(), eq("complete"), eq(null), eq(null), eq(false), eq(null));
    }

    @Test
    void anAnswerThatWasNotStreamedIsShownAtTheEndAsItIs() {
        ChatPanelApi panel = mock(ChatPanelApi.class);
        NestedSessionChatView view = new NestedSessionChatView(panel);
        view.start("Copilot", "Review", "do it");

        view.finish(completed("delivered all at once"));

        verify(panel, never()).appendSubAgentText(anyString(), any(), any());
        verify(panel).updateSubAgentResult(anyString(), eq("complete"), eq("delivered all at once"), eq(null),
            eq(false), eq(null));
    }

    @Test
    void theAnswerIsStreamedWithoutAnyTextAddedToIt() {
        // That the answer belongs to a nested session is shown by the panel's frame, icon and colour, not by text
        // in the answer: such text would be copied with it and stored in the conversation.
        ChatPanelApi panel = mock(ChatPanelApi.class);
        NestedSessionChatView view = new NestedSessionChatView(panel);
        view.start("Koog", "Review", "do it");

        view.onText("one ", null);
        view.onText("two", null);

        InOrder order = inOrder(panel);
        order.verify(panel).appendSubAgentText(anyString(), eq("one "), eq(null));
        order.verify(panel).appendSubAgentText(anyString(), eq("two"), eq(null));
        verify(panel, times(2)).appendSubAgentText(anyString(), anyString(), any());
    }

    // ── how the panel recognises a nested session's entry ──

    @Test
    void theEntryIdMarksTheEntryAsANestedSessions() {
        ChatPanelApi panel = mock(ChatPanelApi.class);
        new NestedSessionChatView(panel).start("Koog", "Review", "do it");

        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        verify(panel).addSubAgentEntry(id.capture(), anyString(), anyString(), anyString(), any());
        assertTrue(NestedSessionChatView.isNestedEntryId(id.getValue()), id.getValue());
    }

    @Test
    void everyNestedSessionGetsAnEntryIdOfItsOwn() {
        ChatPanelApi panel = mock(ChatPanelApi.class);
        new NestedSessionChatView(panel).start("Koog", "A", "do it");
        new NestedSessionChatView(panel).start("Koog", "B", "do it");

        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        verify(panel, times(2)).addSubAgentEntry(id.capture(), anyString(), anyString(), anyString(), any());
        assertNotEquals(id.getAllValues().get(0), id.getAllValues().get(1));
    }

    @Test
    void otherSubAgentsAreNotTakenForNestedSessions() {
        assertFalse(NestedSessionChatView.isNestedEntryId("sa1"));
        assertFalse(NestedSessionChatView.isNestedEntryId("a1b2c3d4-0000-0000-0000-000000000000"));
        assertFalse(NestedSessionChatView.isNestedEntryId(""));
        assertFalse(NestedSessionChatView.isNestedEntryId(null));
    }

    @Test
    void aSessionThatSaidNothingShowsJustWhyItFailed() {
        ChatPanelApi panel = mock(ChatPanelApi.class);
        NestedSessionChatView view = new NestedSessionChatView(panel);
        view.start("Koog", "Review", "do it");

        view.finish(failed("not started", ""));

        verify(panel, never()).appendSubAgentText(anyString(), any(), any());
        verify(panel).updateSubAgentResult(anyString(), eq("failed"), eq("not started"), eq(null), eq(false),
            eq(null));
    }
}
