package com.jazzlogs.backend.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

    // Explicit JPQL, not a derived findByUserIdOrderByLastMessageAtDesc —
    // Chat also has a convenience getUserId() (delegating to user.getId()),
    // which trips up Spring Data's property-path resolver the same way it
    // does for Note/TrackRating/Review/ChatExchange's equivalent id clashes
    // (see ChatExchangeRepository). Spelling out user.id sidesteps it.
    @Query("SELECT c FROM Chat c WHERE c.user.id = :userId ORDER BY c.lastMessageAt DESC")
    List<Chat> findByUserIdOrderByLastMessageAtDesc(@Param("userId") UUID userId);

    // JOIN FETCH, not plain findById: the Chat this returns gets handed to
    // AgentOrchestrator, which reads it back on a different thread
    // (CompletableFuture.runAsync) well after this method's transaction has
    // closed — chat.user is FetchType.LAZY, so a plain proxy would throw
    // LazyInitializationException the moment ChatContextBuilder calls
    // chat.getUser().getDisplayName() outside any open session.
    @Query("SELECT c FROM Chat c JOIN FETCH c.user WHERE c.id = :id")
    Optional<Chat> findByIdWithUser(@Param("id") UUID id);
}
