package com.jazzlogs.backend.agent.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jazzlogs.backend.agent.limit.MessageLimitExceededException.Scope;
import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;
import com.jazzlogs.backend.user.UserRole;

// Against the real Postgres: the limits are the upserts themselves, so only a
// real database can vouch for them. Not @Transactional, unlike the other
// database tests — a refused message has to roll back on its own, and inside a
// test-wide transaction it couldn't — so what each test writes is deleted
// afterwards instead. Days are pinned to 2001, which no real usage row has.
@SpringBootTest
class MessageLimitServiceTest {

    private static final Instant FIRST_MESSAGE = Instant.parse("2001-03-10T14:30:00Z");

    @Autowired
    private MessageLimitRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(new User(UUID.randomUUID(), "limit-test@example.com"));
    }

    @AfterEach
    void tearDown() {
        userRepository.delete(user);
        jdbcClient.sql("DELETE FROM agent_daily_usage WHERE day < DATE '2002-01-01'").update();
    }

    @Test
    void allowsUpToTheLimit_thenRefusesUntilTheWindowEnds() {
        send(FIRST_MESSAGE, 2, 100);
        send(FIRST_MESSAGE.plus(Duration.ofHours(3)), 2, 100);

        Instant later = FIRST_MESSAGE.plus(Duration.ofHours(5));
        assertThatThrownBy(() -> send(later, 2, 100))
            .isInstanceOfSatisfying(MessageLimitExceededException.class, ex -> {
                assertThat(ex.getScope()).isEqualTo(Scope.USER);
                assertThat(ex.getResetsAt()).isEqualTo(FIRST_MESSAGE.plus(Duration.ofHours(24)));
                assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofHours(19));
            });
    }

    @Test
    void windowReopens24HoursAfterItsFirstMessage_notAfterTheLast() {
        send(FIRST_MESSAGE, 2, 100);
        send(FIRST_MESSAGE.plus(Duration.ofHours(23)), 2, 100);

        assertThatThrownBy(() -> send(FIRST_MESSAGE.plus(Duration.ofHours(24)).minusSeconds(1), 2, 100))
            .isInstanceOf(MessageLimitExceededException.class);

        Instant reopened = FIRST_MESSAGE.plus(Duration.ofHours(24));
        send(reopened, 2, 100);
        assertThat(repository.findWindowStart(user.getId())).contains(reopened);
        send(reopened.plusSeconds(60), 2, 100);
    }

    @Test
    void dailyCeiling_refusesUntilUtcMidnight_withoutSpendingTheUsersWindow() {
        send(FIRST_MESSAGE, 5, 1);

        assertThatThrownBy(() -> send(FIRST_MESSAGE.plusSeconds(60), 5, 1))
            .isInstanceOfSatisfying(MessageLimitExceededException.class, ex -> {
                assertThat(ex.getScope()).isEqualTo(Scope.GLOBAL);
                assertThat(ex.getResetsAt()).isEqualTo(Instant.parse("2001-03-11T00:00:00Z"));
            });
        assertThat(messagesUsedInWindow()).isEqualTo(1);

        assertThatCode(() -> send(Instant.parse("2001-03-11T00:00:00Z"), 5, 1)).doesNotThrowAnyException();
    }

    @Test
    void admins_areNeverLimited_norCounted() {
        ReflectionTestUtils.setField(user, "role", UserRole.ADMIN);

        for (int i = 0; i < 3; i++) {
            send(FIRST_MESSAGE.plusSeconds(i), 1, 1);
        }
        assertThat(repository.findWindowStart(user.getId())).isEmpty();
    }

    /** One message at {@code now}, through a service whose clock is fixed there — committed, like a real request. */
    private void send(Instant now, int messagesPerUser, int messagesPerDay) {
        MessageLimitService service = new MessageLimitService(
            repository, Clock.fixed(now, ZoneOffset.UTC), messagesPerUser, messagesPerDay
        );
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> service.reserve(user));
    }

    private int messagesUsedInWindow() {
        return jdbcClient.sql("SELECT messages_used FROM agent_usage_windows WHERE user_id = :userId")
            .param("userId", user.getId())
            .query(Integer.class)
            .single();
    }
}
