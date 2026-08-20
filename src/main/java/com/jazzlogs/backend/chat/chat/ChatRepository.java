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

    // JOIN FETCH, not plain findById: the Chat this returns gets handed to
    // AgentOrchestrator, which reads it back on a different thread
    // (CompletableFuture.runAsync) well after this method's transaction has
    // closed — chat.user is FetchType.LAZY, so a plain proxy would throw
    // LazyInitializationException the moment ChatContextBuilder calls
    // chat.getUser().getDisplayName() outside any open session.
    @Query("SELECT c FROM Chat c JOIN FETCH c.user WHERE c.id = :id")
    Optional<Chat> findByIdWithUser(@Param("id") UUID id);
}
