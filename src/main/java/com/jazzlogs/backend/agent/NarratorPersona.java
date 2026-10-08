package com.jazzlogs.backend.agent;

/**
 * One narrator's voice, as loaded from its file — the variable part of the
 * agent's prompt. Everything else in the prompt is identical across the
 * eight narrators.
 *
 * @param traits    who the narrator is and how that shows when they talk
 * @param chatVoice how the narrator behaves in a chat specifically, as opposed to in a written log
 * @param sample    a passage in the narrator's own voice, used as a few-shot
 * @param onFriends how this narrator talks about a log one of the other seven wrote
 */
public record NarratorPersona(String traits, String chatVoice, String sample, String onFriends) {
}
