package com.github.catatafishen.agentbridge.client;

/**
 * Represents a slash command with an optional description for display in the autocomplete popup.
 */
public record SlashCommandInfo(String name, String description) {
}
