package com.jazzlogs.backend.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.jazzlogs.backend.graph.GraphService;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final GraphService graphService;

    public User resolveFromJwt(Jwt jwt) {
        UUID supabaseUserId = UUID.fromString(jwt.getSubject());
        String email = jwt.getClaimAsString("email");

        Optional<User> existing = userRepository.findBySupabaseUserId(supabaseUserId);
        User user = existing.orElseGet(() -> new User(supabaseUserId, email));

        user.recordLogin(email);
        User saved = userRepository.save(user);

        if (existing.isEmpty()) {
            graphService.createUserNode(saved.getId());
        }

        return saved;
    }
}
