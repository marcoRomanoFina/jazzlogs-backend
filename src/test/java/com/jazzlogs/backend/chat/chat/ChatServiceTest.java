package com.jazzlogs.backend.chat.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.jazzlogs.backend.user.User;

// createChat: covers the fix for the "orphaned empty chat if the agent's
// first turn fails" bug (a Chat used to be saved eagerly, before the agent
// ever ran) — hinges entirely on this method no longer touching the
// database at all. getUserChats is a plain delegation to ChatRepository,
// not worth a dedicated mock-based test. getOwnedChat's ownership check IS
// covered — collapsing "doesn't exist" and "exists but isn't yours" onto
// the exact same 404 is a real security property worth locking in with a test.
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatRepository chatRepository;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(chatRepository);
    }

    @Test
    void createChat_neverTouchesTheDatabase_untilItsFirstExchangeIsPersisted() {
        UUID userId = UUID.randomUUID();
        User user = new User(UUID.randomUUID(), "test@example.com");
        ReflectionTestUtils.setField(user, "id", userId);

        Chat chat = service.createChat(user);

        assertThat(chat.getId()).isNull();
        assertThat(chat.getUserId()).isEqualTo(userId);
        assertThat(chat.getTitle()).isNull();
        verify(chatRepository, never()).save(any());
    }

    @Test
    void getOwnedChat_returnsTheChat_whenTheCallerOwnsIt() {
        UUID chatId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Chat chat = chatOwnedBy(ownerId);
        when(chatRepository.findByIdWithUser(chatId)).thenReturn(Optional.of(chat));

        assertThat(service.getOwnedChat(chatId, ownerId)).isSameAs(chat);
    }

    @Test
    void getOwnedChat_throws404_whenNoSuchChatExists() {
        UUID chatId = UUID.randomUUID();
        when(chatRepository.findByIdWithUser(chatId)).thenReturn(Optional.empty());

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> service.getOwnedChat(chatId, UUID.randomUUID())
        );
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // The regression this guards: a chat that exists but belongs to someone
    // else must throw the exact same 404 as a chat that doesn't exist at
    // all — never a 403, which would confirm to a non-owner that this id
    // is a real chat (see ChatService.getOwnedChat's Javadoc).
    @Test
    void getOwnedChat_throws404_notForbidden_whenTheChatBelongsToSomeoneElse() {
        UUID chatId = UUID.randomUUID();
        Chat chat = chatOwnedBy(UUID.randomUUID());
        when(chatRepository.findByIdWithUser(chatId)).thenReturn(Optional.of(chat));

        ResponseStatusException ex = catchThrowableOfType(
            ResponseStatusException.class, () -> service.getOwnedChat(chatId, UUID.randomUUID())
        );
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static Chat chatOwnedBy(UUID ownerId) {
        User owner = new User(UUID.randomUUID(), "owner@example.com");
        ReflectionTestUtils.setField(owner, "id", ownerId);
        return new Chat(owner, null);
    }
}
