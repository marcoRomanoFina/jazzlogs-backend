package com.jazzlogs.backend.agent.limit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jazzlogs.backend.agent.limit.MessageLimitExceededException.Scope;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRole;

/**
 * How many agent messages a user may send: a fixed number per 24-hour window,
 * the window opened by the first message sent in it, plus a ceiling on every
 * user's messages together per UTC day. Admins are exempt from both, so testing
 * and the evaluation set are never blocked.
 */
@Service
public class MessageLimitService {

    static final Duration WINDOW = Duration.ofHours(24);

    private final MessageLimitRepository repository;
    private final Clock clock;
    private final int messagesPerUser;
    private final int messagesPerDay;

    public MessageLimitService(
        MessageLimitRepository repository,
        Clock clock,
        @Value("${agent.limits.messages-per-user:20}") int messagesPerUser,
        @Value("${agent.limits.messages-per-day:200}") int messagesPerDay
    ) {
        this.repository = repository;
        this.clock = clock;
        this.messagesPerUser = messagesPerUser;
        this.messagesPerDay = messagesPerDay;
    }

    /**
     * Counts one message for {@code user}, before it is sent to the model — a turn
     * that fails afterwards still spent tokens, so it still counts. One transaction,
     * so a message the daily ceiling refuses is not left counted in the user's window.
     *
     * @throws MessageLimitExceededException if either limit is spent; nothing is counted then
     */
    @Transactional
    public void reserve(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            return;
        }
        Instant now = clock.instant();

        if (repository.reserveForUser(user.getId(), now, now.minus(WINDOW), messagesPerUser).isEmpty()) {
            Instant resetsAt = repository.findWindowStart(user.getId()).orElseThrow().plus(WINDOW);
            throw new MessageLimitExceededException(Scope.USER, resetsAt, Duration.between(now, resetsAt));
        }

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!repository.reserveForDay(today, messagesPerDay)) {
            Instant resetsAt = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            throw new MessageLimitExceededException(Scope.GLOBAL, resetsAt, Duration.between(now, resetsAt));
        }
    }
}
