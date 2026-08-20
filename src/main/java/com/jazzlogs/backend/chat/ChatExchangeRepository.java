package com.jazzlogs.backend.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatExchangeRepository extends JpaRepository<ChatExchange, UUID> {

    // Explicit JPQL, not a derived findByChatIdOrderByCreatedAtAsc —
    // ChatExchange also has a convenience getChatId() (delegating to
    // chat.getId()), and Spring Data's property-path resolver picks that
    // plain method up as if "chatId" were its own mapped attribute,
    // generating invalid JPQL ("Could not resolve attribute 'chatId' of
    // ChatExchange") instead of drilling into the chat association.
    // Spelling out chat.id sidesteps the ambiguity entirely — same fix
    // already applied to Note/TrackRating/Review's equivalent userId clash.
    @Query("SELECT ce FROM ChatExchange ce WHERE ce.chat.id = :chatId ORDER BY ce.createdAt ASC")
    List<ChatExchange> findByChatIdOrderByCreatedAtAsc(@Param("chatId") UUID chatId);

    // Newest-first, capped — ChatContextBuilder reverses this to ascending
    // order before turning it into conversation turns. Same chatId
    // ambiguity as above, same fix; LIMIT in HQL is a Hibernate 6+
    // extension (no need for a Pageable parameter just to cap 3 rows).
    @Query("SELECT ce FROM ChatExchange ce WHERE ce.chat.id = :chatId ORDER BY ce.createdAt DESC LIMIT 3")
    List<ChatExchange> findTop3ByChatIdOrderByCreatedAtDesc(@Param("chatId") UUID chatId);
}
