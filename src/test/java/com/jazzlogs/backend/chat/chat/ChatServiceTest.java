package com.jazzlogs.backend.chat.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.jazzlogs.backend.user.User;
import com.jazzlogs.backend.user.UserRepository;

// createChat is the one place covered here — the fix for the "orphaned
// empty chat if the agent's first turn fails" bug (a Chat used to be saved
// eagerly, before the agent ever ran) hinges entirely on this method no
// longer touching the database at all. getUserChats/getOwnedChat are plain
// delegations to ChatRepository, not worth a dedicated mock-based test.
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private UserRepository userRepository;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(chatRepository, userRepository);
    }

    @Test
    void createChat_neverTouchesTheDatabase_untilItsFirstExchangeIsPersisted() {
        UUID userId = UUID.randomUUID();
        User user = new User(UUID.randomUUID(), "test@example.com");
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Chat chat = service.createChat(userId);

        assertThat(chat.getId()).isNull();
        assertThat(chat.getUserId()).isEqualTo(userId);
        assertThat(chat.getTitle()).isNull();
        verify(chatRepository, never()).save(any());
    }
}
