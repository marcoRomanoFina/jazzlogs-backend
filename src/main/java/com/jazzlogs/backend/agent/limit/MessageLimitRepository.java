package com.jazzlogs.backend.agent.limit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import lombok.AllArgsConstructor;

/**
 * The two counters behind {@link MessageLimitService}. Plain SQL rather than
 * JPA because each reservation is one {@code INSERT ... ON CONFLICT DO UPDATE}
 * that checks and counts in the same statement: two messages sent at once can
 * never both take the last place.
 */
@Repository
@AllArgsConstructor
public class MessageLimitRepository {

    private final JdbcClient jdbcClient;

    /**
     * Counts one message in the user's window, opening a new window at {@code now}
     * if they have none or theirs started at or before {@code expiredBefore}.
     *
     * @return when the counted message's window started, or empty if the window is
     *         still open and already at {@code limit} — nothing is counted then
     */
    public Optional<Instant> reserveForUser(UUID userId, Instant now, Instant expiredBefore, int limit) {
        return jdbcClient.sql("""
                INSERT INTO agent_usage_windows AS w (user_id, window_started_at, messages_used)
                VALUES (:userId, :now, 1)
                ON CONFLICT (user_id) DO UPDATE SET
                    window_started_at = CASE WHEN w.window_started_at <= :expiredBefore
                                             THEN :now ELSE w.window_started_at END,
                    messages_used     = CASE WHEN w.window_started_at <= :expiredBefore
                                             THEN 1 ELSE w.messages_used + 1 END
                WHERE w.window_started_at <= :expiredBefore OR w.messages_used < :limit
                RETURNING window_started_at
                """)
            .param("userId", userId)
            .param("now", toTimestamp(now))
            .param("expiredBefore", toTimestamp(expiredBefore))
            .param("limit", limit)
            .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
            .optional();
    }

    /** When the user's current window started, if they have ever sent a message. */
    public Optional<Instant> findWindowStart(UUID userId) {
        return jdbcClient.sql("SELECT window_started_at FROM agent_usage_windows WHERE user_id = :userId")
            .param("userId", userId)
            .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
            .optional();
    }

    /**
     * Counts one message against {@code day}'s total for every user together.
     *
     * @return false if that day is already at {@code limit} — nothing is counted then
     */
    public boolean reserveForDay(LocalDate day, int limit) {
        return jdbcClient.sql("""
                INSERT INTO agent_daily_usage AS d (day, messages_used)
                VALUES (:day, 1)
                ON CONFLICT (day) DO UPDATE SET messages_used = d.messages_used + 1
                WHERE d.messages_used < :limit
                RETURNING messages_used
                """)
            .param("day", day)
            .param("limit", limit)
            .query(Integer.class)
            .optional()
            .isPresent();
    }

    /** The Postgres driver binds {@code timestamptz} from an {@link OffsetDateTime}, not an {@link Instant}. */
    private static OffsetDateTime toTimestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
