package com.jazzlogs.backend.user;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    
    public User resolveFromJwt(Jwt jwt) {
        UUID supabaseUserId = UUID.fromString(jwt.getSubject());
        String email = jwt.getClaimAsString("email");

        User user = userRepository.findBySupabaseUserId(supabaseUserId)
            .orElseGet(() -> new User(supabaseUserId, email));

        user.recordLogin(email);
        return userRepository.save(user);
    }
}
