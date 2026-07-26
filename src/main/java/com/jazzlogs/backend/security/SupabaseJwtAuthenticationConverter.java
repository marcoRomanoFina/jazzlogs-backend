package com.jazzlogs.backend.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.jazzlogs.backend.user.UserRepository;

import lombok.AllArgsConstructor;

/**
 * Maps a validated Supabase JWT to Spring Security authorities based on the
 * role stored in our own Postgres User row (Supabase has no notion of it).
 * A JWT for a supabase_user_id that hasn't hit the app yet gets no authorities
 * — the User row gets created on first request (see UserService), and until
 * then it simply can't pass any role-gated endpoint.
 */
@Component
@AllArgsConstructor
public class SupabaseJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository userRepository;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID supabaseUserId = UUID.fromString(jwt.getSubject());

        List<GrantedAuthority> authorities = userRepository.findBySupabaseUserId(supabaseUserId)
            .<List<GrantedAuthority>>map(user -> List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
            .orElseGet(List::of);

        return new JwtAuthenticationToken(jwt, authorities);
    }
}
