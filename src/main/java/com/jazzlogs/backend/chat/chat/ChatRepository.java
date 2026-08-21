package com.jazzlogs.backend.chat.chat;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for {@link Chat}.
 */
public interface ChatRepository extends JpaRepository<Chat, UUID> {

    /**
     * Pages a user's chats.
     *
     * @param userId   the owning user
     * @param pageable page/size/sort requested by the caller
     * @return a page of the user's chats
     */
    @Query("SELECT c FROM Chat c WHERE c.user.id = :userId")
    Page<Chat> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    /**
     * By id, with {@code user} eagerly fetched instead of the default lazy
     * proxy — the returned {@link Chat} is handed to the agent's async
     * loop, which reads {@code chat.getUser()} on a different thread, after
     * this method's transaction has already closed; a lazy proxy would
     * throw {@code LazyInitializationException} at that point.
     *
     * @param id the chat to look up
     * @return the chat, with its user already loaded
     */
    @Query("SELECT c FROM Chat c JOIN FETCH c.user WHERE c.id = :id")
    Optional<Chat> findByIdWithUser(@Param("id") UUID id);
}
