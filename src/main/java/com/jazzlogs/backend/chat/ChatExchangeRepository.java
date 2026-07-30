package com.jazzlogs.backend.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatExchangeRepository extends JpaRepository<ChatExchange, UUID> {

    List<ChatExchange> findByChatIdOrderByCreatedAtAsc(UUID chatId);

    // Newest-first, capped — ChatContextBuilder reverses this to ascending
    // order before turning it into conversation turns.
    List<ChatExchange> findTop3ByChatIdOrderByCreatedAtDesc(UUID chatId);
}
