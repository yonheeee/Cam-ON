package com.camon.global.security;

import java.security.Principal;
import java.util.UUID;

public record GuestPrincipal(UUID participantId) implements Principal {

    @Override
    public String getName() {
        return participantId.toString();
    }
}
