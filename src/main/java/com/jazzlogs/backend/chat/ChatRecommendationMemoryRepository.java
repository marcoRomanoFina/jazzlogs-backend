package com.jazzlogs.backend.chat;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRecommendationMemoryRepository extends JpaRepository<ChatRecommendationMemory, UUID> {

    Optional<ChatRecommendationMemory> findByChatId(UUID chatId);
}
