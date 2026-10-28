package com.jazzlogs.backend.vocabulary;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared validation for tag codes (style/mood/context/rhythm/instrument) against
 * their controlled-vocabulary enum — used by both Album and Track tagging, since
 * a MATCH on a nonexistent Neo4j node fails silently rather than raising an error.
 */
public final class VocabularyCodes {

    private VocabularyCodes() {
    }

    public static <E extends Enum<E>> void validate(Class<E> enumClass, String code, String kind) {
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing " + kind + " code");
        }
        try {
            Enum.valueOf(enumClass, code);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + kind + " code: " + code);
        }
    }
}
