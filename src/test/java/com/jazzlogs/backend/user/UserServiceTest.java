package com.jazzlogs.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import com.jazzlogs.backend.graph.GraphService;
import com.jazzlogs.backend.syncfailure.Neo4jAsyncSyncExecutor;
import com.jazzlogs.backend.syncfailure.SyncFailureEntityType;

// Pure Mockito unit test — GraphService/Neo4jAsyncSyncExecutor are both
// mocked, so this only covers UserService's own job: whether a Neo4j User-node
// sync gets dispatched at all (new users only, never a returning login) and
// with the right payload/graph write — not whether the sync itself survives a
// down Neo4j (that's Neo4jAsyncSyncExecutor's own, already-covered concern;
// see ListenServiceTest for that class of coverage against this same executor).
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private GraphService graphService;

    @Mock
    private Neo4jAsyncSyncExecutor syncExecutor;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, graphService, syncExecutor);
    }

    @Test
    void newUser_dispatchesUserCreatedSync_withRealUserIdAndWorkingGraphWrite() {
        UUID supabaseUserId = UUID.randomUUID();
        Jwt jwt = mockJwt(supabaseUserId, "marco@example.com");
        when(userRepository.findBySupabaseUserId(supabaseUserId)).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            return user;
        });

        User saved = service.resolveFromJwt(jwt);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Runnable> graphWriteCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(syncExecutor).sync(eq(SyncFailureEntityType.USER_CREATED), payloadCaptor.capture(), graphWriteCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("userId", saved.getId().toString());

        // The Runnable itself is never invoked by the mocked executor — run
        // it here to prove it actually calls createUserNode with the right
        // id, not just that *some* Runnable was passed.
        graphWriteCaptor.getValue().run();
        verify(graphService).createUserNode(saved.getId());
    }

    @Test
    void returningUser_neverDispatchesUserCreatedSync() {
        UUID supabaseUserId = UUID.randomUUID();
        Jwt jwt = mockJwt(supabaseUserId, "marco@example.com");
        User existing = new User(supabaseUserId, "marco@example.com");
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(userRepository.findBySupabaseUserId(supabaseUserId)).thenReturn(Optional.of(existing));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.resolveFromJwt(jwt);

        verify(syncExecutor, never()).sync(any(), any(), any());
    }

    private static Jwt mockJwt(UUID subject, String email) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject.toString());
        when(jwt.getClaimAsString("email")).thenReturn(email);
        return jwt;
    }
}
