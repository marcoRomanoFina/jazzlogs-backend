package com.jazzlogs.backend.user;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.syncfailure.Neo4jAsyncSyncExecutor;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final GraphService graphService;
    private final Neo4jAsyncSyncExecutor syncExecutor;

    public User resolveFromJwt(Jwt jwt) {
        UUID supabaseUserId = UUID.fromString(jwt.getSubject());
        String email = jwt.getClaimAsString("email");

        Optional<User> existing = userRepository.findBySupabaseUserId(supabaseUserId);
        User user = existing.orElseGet(() -> new User(supabaseUserId, email));

        user.recordLogin(email);
        User saved = userRepository.save(user);

        // Same "a graph outage must never block the Postgres write it's
        // attached to" contract as every other Neo4jAsyncSyncExecutor call
        // site — this used to call GraphService directly and unguarded,
        // which meant a slow/down Neo4j on a user's very first login could
        // fail the whole login, and (since nothing retried it) permanently
        // leave that user without a graph node — see UserCreatedSyncRetryHandler.
        if (existing.isEmpty()) {
            UUID userId = saved.getId();
            syncExecutor.sync(
                SyncFailureEntityType.USER_CREATED,
                Map.of("userId", userId.toString()),
                () -> graphService.createUserNode(userId)
            );
        }

        return saved;
    }
}
