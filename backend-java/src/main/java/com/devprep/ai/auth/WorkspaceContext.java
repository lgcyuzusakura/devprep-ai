package com.devprep.ai.auth;

/** Request and worker scope; never derive the workspace from client input. */
public final class WorkspaceContext implements AutoCloseable {
    public record User(String id, String username, String schema, boolean admin, boolean mustChangePassword) {}
    private static final ThreadLocal<User> CURRENT = new ThreadLocal<>();
    private final User previous;
    private WorkspaceContext(User user) { previous = CURRENT.get(); if (user == null) CURRENT.remove(); else CURRENT.set(user); }
    public static WorkspaceContext use(User user) { return new WorkspaceContext(user); }
    public static User current() { return CURRENT.get(); }
    public static String schema() { return current() == null ? "public" : current().schema(); }
    public static boolean isAdmin() { return current() == null || current().admin(); }
    public static Runnable capture(Runnable task) {
        User captured = current();
        return () -> { try (var scope = use(captured)) { task.run(); } };
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
