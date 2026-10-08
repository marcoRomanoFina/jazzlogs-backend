package com.jazzlogs.backend.agent;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputItem;

import com.jazzlogs.backend.chat.chat.Chat;
import com.jazzlogs.backend.chat.chatexchange.ChatExchange;
import com.jazzlogs.backend.chat.chatexchange.ChatExchangeRepository;
import com.jazzlogs.backend.chat.chatexchange.ChatRecommendationMemory;
import com.jazzlogs.backend.chat.chatexchange.ChatRecommendationMemoryRepository;
import com.jazzlogs.backend.chat.chatexchange.WinnerReference;

import lombok.AllArgsConstructor;

/**
 * Assembles the {@code List<ResponseInputItem>} sent to the Responses API
 * for one exchange: a developer message (the fixed instructions, the chat's
 * narrator persona, canonical vocabulary, runtime context, session summary,
 * recommendation history) followed by up to the 3 most recent exchanges as user/assistant
 * turns, then the new user message. Read-only and side-effect free — this
 * only reads prior state, it never persists anything.
 */
@Service
@AllArgsConstructor
public class ChatContextBuilder {

    /** Day of week included — "Friday 23:40" and "Tuesday 23:40" are different moments to pick music for. */
    private static final DateTimeFormatter RUNTIME_DATETIME_FORMAT = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH);

    private final ChatExchangeRepository chatExchangeRepository;
    private final ChatRecommendationMemoryRepository chatRecommendationMemoryRepository;
    private final VocabularyProvider vocabularyProvider;
    private final NarratorPersonas narratorPersonas;

    /**
     * Builds one exchange's input: the developer message plus recent history
     * plus {@code userMessage}. A brand-new, not-yet-persisted {@code chat}
     * (no id) skips both repository lookups entirely rather than querying
     * with a null id — it can't have prior exchanges or memory by definition.
     *
     * @param chat        the chat this exchange belongs to; may not have an id yet
     * @param userMessage the user's new message, appended last
     * @param timezone    IANA zone id used to work out the user's local time; null/invalid means that time is unknown
     * @return the full input list, ready to send to the Responses API
     */
    @Transactional(readOnly = true)
    public List<ResponseInputItem> buildInput(Chat chat, String userMessage, String timezone) {
        
        // A brand-new chat (built in memory by ChatService.createChat, not
        // yet saved — see ChatExchangeService.persist) has no id yet and, by
        // definition, no prior exchanges or recommendation memory to load —
        // skipped explicitly rather than querying with a null chatId.
        boolean isNewChat = chat.getId() == null;

        List<ChatExchange> recentExchanges = new ArrayList<>();
        Optional<ChatRecommendationMemory> memory = Optional.empty();
        if (!isNewChat) {
            recentExchanges.addAll(chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId()));
            Collections.reverse(recentExchanges);
            memory = chatRecommendationMemoryRepository.findByChatId(chat.getId());
        }

        // Ordered from least to most variable, on purpose: OpenAI caches a prompt by its
        // longest unchanged prefix, so anything that differs between requests also throws
        // away the cache for everything after it. Identical for every request first
        // (instructions, vocabulary), then one of eight variants (the narrator), then what
        // changes on every single call (the clock, this chat's memory).
        String developerText = String.join(
            "\n\n",
            AgentPromptTemplates.STATIC_INSTRUCTIONS,
            buildVocabularySection(),
            narratorPersonas.render(chat.getNarrator()),
            buildRuntimeContextSection(chat, timezone),
            buildSessionSummarySection(memory),
            buildRecommendationHistorySection(memory)
        );

        List<ResponseInputItem> input = new ArrayList<>();
        input.add(message(EasyInputMessage.Role.DEVELOPER, developerText));
        for (ChatExchange exchange : recentExchanges) {
            input.add(message(EasyInputMessage.Role.USER, exchange.getUserMessage()));
            input.add(message(EasyInputMessage.Role.ASSISTANT, exchange.getFinalResponse()));
        }
        input.add(message(EasyInputMessage.Role.USER, userMessage));

        return input;
    }

    /** Renders the canonical filter vocabulary (styles, moods, rhythms, ...) the model must stick to. */
    private String buildVocabularySection() {
        return """
            CANONICAL FILTER VOCABULARY
            Use only canonical values from these lists when you prepare structured
            search filters.
            Do not invent new labels, synonyms, blends, translations, or near-matches.
            If a filter is not clearly supported by these canonical values, leave it empty.
            Prefer fewer correct filters over more speculative ones.

            styles: %s
            moods: %s
            rhythms: %s
            contexts: %s
            instruments: %s
            editorial categories: %s""".formatted(
            vocabularyProvider.styles(),
            vocabularyProvider.moods(),
            vocabularyProvider.rhythms(),
            vocabularyProvider.contexts(),
            vocabularyProvider.instruments(),
            vocabularyProvider.editorialCategories()
        );
    }

    /**
     * Renders the user's current local date and time, display name, and chat
     * id. The zone itself is never shown — the model only needs the moment,
     * already converted — and a missing/invalid zone renders the time as
     * unknown rather than falling back to some other zone's clock: the hour
     * steers which tracks get picked (see THE USER'S MOMENT), so a wrong one
     * is worse than none.
     */
    private String buildRuntimeContextSection(Chat chat, String timezone) {
        String localTime = resolveZone(timezone)
            .map(zone -> ZonedDateTime.now(zone).format(RUNTIME_DATETIME_FORMAT))
            .orElse("unknown");
        String displayName = chat.getUser().getResolvedDisplayName();

        return """
            RUNTIME CONTEXT
            Current local date and time for the user: %s
            User display name: %s
            Chat session id: %s""".formatted(
            localTime,
            displayName,
            chat.getId() == null ? "(new chat, not yet created)" : chat.getId()
        );
    }

    /** Never fails the request over a missing/invalid timezone — it just means the user's local time isn't known. */
    private Optional<ZoneId> resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZoneId.of(timezone));
        } catch (DateTimeException invalidZone) {
            return Optional.empty();
        }
    }

    /** Renders the session summary, or a placeholder when there's no memory yet. */
    private String buildSessionSummarySection(Optional<ChatRecommendationMemory> memory) {
        String summary = memory.map(ChatRecommendationMemory::getSessionSummary)
            .filter(s -> s != null && !s.isBlank())
            .orElse(null);

        if (summary == null) {
            return "SESSION SUMMARY\n(none yet — this is the start of the conversation)";
        }
        return "SESSION SUMMARY\n" + summary;
    }

    /** Renders prior recommendations to avoid repeating, or a placeholder when there's none. */
    private String buildRecommendationHistorySection(Optional<ChatRecommendationMemory> memory) {
        List<WinnerReference> history = memory.map(ChatRecommendationMemory::getWinnersHistory).orElse(List.of());

        if (history.isEmpty()) {
            return "RECOMMENDATION HISTORY\n(none yet)";
        }

        String lines = history.stream().map(this::formatWinnerLine).collect(Collectors.joining("\n"));
        return "RECOMMENDATION HISTORY\nAlready recommended in this session, avoid repeating unless the user asks again:\n" + lines;
    }

    /** One "- name" or "- name — artist" line; omits the artist separator when there isn't one. */
    private String formatWinnerLine(WinnerReference winner) {
        if (winner.primaryArtist() == null) {
            return "- \"" + winner.name() + "\"";
        }
        return "- \"" + winner.name() + "\" — " + winner.primaryArtist();
    }

    /** Wraps a role + text into the {@code ResponseInputItem} shape the API expects. */
    private ResponseInputItem message(EasyInputMessage.Role role, String content) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(content).build());
    }
}
