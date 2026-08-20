package com.jazzlogs.backend.agent;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import com.jazzlogs.backend.chat.chatexchange.WinnerRef;

import lombok.AllArgsConstructor;

// Pure prompt assembly for the Responses API — no tool loop, no actual API
// call, no persistence. Just turns a Chat + its recent history into the
// message list AgentOrchestrator sends as-is.
@Service
@AllArgsConstructor
public class ChatContextBuilder {

    private static final ZoneId DEFAULT_ZONE = ZoneOffset.UTC;
    private static final DateTimeFormatter RUNTIME_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ChatExchangeRepository chatExchangeRepository;
    private final ChatRecommendationMemoryRepository chatRecommendationMemoryRepository;
    private final VocabularyProvider vocabularyProvider;

    // Convenience overload — no timezone available yet where this is called
    // from, falls back to UTC. See buildInput(Chat, String, String).
    public List<ResponseInputItem> buildInput(Chat chat, String userMessage) {
        return buildInput(chat, userMessage, null);
    }

    @Transactional(readOnly = true)
    public List<ResponseInputItem> buildInput(Chat chat, String userMessage, String timezone) {
        List<ChatExchange> recentExchanges = new ArrayList<>(
            chatExchangeRepository.findTop3ByChatIdOrderByCreatedAtDesc(chat.getId())
        );
        Collections.reverse(recentExchanges);

        Optional<ChatRecommendationMemory> memory = chatRecommendationMemoryRepository.findByChatId(chat.getId());

        String developerText = String.join(
            "\n\n",
            AgentPromptTemplates.STATIC_INSTRUCTIONS,
            buildVocabularySection(),
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

    private String buildRuntimeContextSection(Chat chat, String timezone) {
        ZoneId zone = resolveZone(timezone);
        String datetime = ZonedDateTime.now(zone).format(RUNTIME_DATETIME_FORMAT);
        String displayName = chat.getUser().getResolvedDisplayName();

        return """
            RUNTIME CONTEXT
            Current local datetime for the user: %s
            User timezone: %s
            User display name: %s
            Chat session id: %s""".formatted(
            datetime,
            zone.getId(),
            displayName,
            chat.getId()
        );
    }

    // Never fails the request for a missing/invalid timezone — UTC fallback,
    // per the same "don't block the exchange on a soft-context detail" spirit
    // as ChatRecommendationMemoryService's fire-and-forget.
    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException invalidZone) {
            return DEFAULT_ZONE;
        }
    }

    private String buildSessionSummarySection(Optional<ChatRecommendationMemory> memory) {
        String summary = memory.map(ChatRecommendationMemory::getSessionSummary)
            .filter(s -> s != null && !s.isBlank())
            .orElse(null);

        if (summary == null) {
            return "SESSION SUMMARY\n(none yet — this is the start of the conversation)";
        }
        return "SESSION SUMMARY\n" + summary;
    }

    private String buildRecommendationHistorySection(Optional<ChatRecommendationMemory> memory) {
        List<WinnerRef> history = memory.map(ChatRecommendationMemory::getWinnersHistory).orElse(List.of());

        if (history.isEmpty()) {
            return "RECOMMENDATION HISTORY\n(none yet)";
        }

        String lines = history.stream().map(this::formatWinnerLine).collect(Collectors.joining("\n"));
        return "RECOMMENDATION HISTORY\nAlready recommended in this session, avoid repeating unless the user asks again:\n" + lines;
    }

    private String formatWinnerLine(WinnerRef winner) {
        if (winner.primaryArtist() == null) {
            return "- \"" + winner.name() + "\"";
        }
        return "- \"" + winner.name() + "\" — " + winner.primaryArtist();
    }

    private ResponseInputItem message(EasyInputMessage.Role role, String content) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(content).build());
    }
}
