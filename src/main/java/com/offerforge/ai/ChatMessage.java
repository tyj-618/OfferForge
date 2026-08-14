package com.offerforge.ai;

public record ChatMessage(Role role, String content) {

    public enum Role {
        SYSTEM, USER, ASSISTANT
    }

    public String providerRole() {
        return role.name().toLowerCase();
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }
}
