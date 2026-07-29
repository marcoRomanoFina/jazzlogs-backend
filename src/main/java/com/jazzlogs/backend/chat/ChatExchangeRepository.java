package com.jazzlogs.backend.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatExchangeRepository extends JpaRepository<ChatExchange, UUID> {

    List<ChatExchange> findByChatIdOrderByCreatedAtAsc(UUID chatId);
}
