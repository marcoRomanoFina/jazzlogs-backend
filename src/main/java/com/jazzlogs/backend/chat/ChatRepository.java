package com.jazzlogs.backend.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

    List<Chat> findByUserIdOrderByLastMessageAtDesc(UUID userId);

    // JOIN FETCH, not plain findById: the Chat this returns gets handed to
    // AgentOrchestrator, which reads it back on a different thread
    // (CompletableFuture.runAsync) well after this method's transaction has
    // closed — chat.user is FetchType.LAZY, so a plain proxy would throw
    // LazyInitializationException the moment ChatContextBuilder calls
    // chat.getUser().getDisplayName() outside any open session.
    @Query("SELECT c FROM Chat c JOIN FETCH c.user WHERE c.id = :id")
    Optional<Chat> findByIdWithUser(@Param("id") UUID id);
}
