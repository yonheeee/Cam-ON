package com.camon.domain.room.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class RoomCodeGenerator {

    private static final char[] ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;

    private final SecureRandom secureRandom;

    public RoomCodeGenerator() {
        this(new SecureRandom());
    }

    RoomCodeGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        char[] code = new char[CODE_LENGTH];
        for (int index = 0; index < CODE_LENGTH; index++) {
            code[index] = ALPHABET[secureRandom.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
