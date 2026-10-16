package com.devprep.ai.auth;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.service.SeedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "devprep.auth-enabled", havingValue = "true")
public class UserAccounts {
    public static final String COOKIE = "devprep_session";
    private static final long SESSION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private final JdbcTemplate root;
    private final DataSource physical;
    private final SeedService seeds;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final String bootstrapFile;
    private final String dummyHash = PasswordHash.hash("dummy-" + UUID.randomUUID());
    private final Map<String, Attempts> failures = new LinkedHashMap<>();
    private volatile boolean ready;
    private record Attempts(int count, long expires) {}
    public record Login(WorkspaceContext.User user, String token) {}

    public UserAccounts(@Qualifier("physicalDataSource") DataSource physical, SeedService seeds, Clock clock,
                        ObjectMapper mapper, @Value("${devprep.auth-bootstrap-file:}") String bootstrapFile) {
        this.physical = physical; this.root = new JdbcTemplate(new WorkspaceDataSource(physical, () -> "public"));
        this.seeds = seeds; this.clock = clock; this.mapper = mapper; this.bootstrapFile = bootstrapFile;
    }
    public boolean ready() { return ready; }
    private WorkspaceContext.User user(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkspaceContext.User(rs.getString("id"), rs.getString("username"), rs.getString("workspace_schema"),
                rs.getBoolean("is_admin"), rs.getBoolean("must_change_password"));
    }
    public List<WorkspaceContext.User> users() { return root.query("SELECT * FROM app_users WHERE enabled=TRUE ORDER BY created_at,username", (rs,n) -> user(rs)); }
    public synchronized void initialize() throws Exception {
        if (users().isEmpty()) {
            if (bootstrapFile.isBlank()) throw new IllegalStateException("启用账号模式时必须提供私有的 DEVPREP_AUTH_BOOTSTRAP_FILE。");
            var path = Path.of(bootstrapFile);
            if (Files.size(path) > 32_000) throw new IllegalStateException("Account bootstrap file too large");
            var rows = mapper.readTree(Files.readString(path)).path("accounts");
            if (!rows.isArray() || rows.isEmpty() || rows.size() > 20) throw new IllegalStateException("Account bootstrap requires 1–20 accounts");
            var names = new HashSet<String>();
            // Validate every row before associating the existing private workspace with its owner.
            for (var row : rows) {
                String name = validUsername(row.path("username").asText());
                validPassword(row.path("password").asText());
                if (!names.add(name)) throw new IllegalStateException("Duplicate bootstrap username");
            }
            var owner = rows.get(0);
            insert(owner.path("username").asText(), owner.path("password").asText(), "public", true, false);
        }
        // A partial bootstrap can be resumed without resetting any existing password.
        if (!bootstrapFile.isBlank()) {
            var rows = mapper.readTree(Files.readString(Path.of(bootstrapFile))).path("accounts");
            for (var row : rows) {
                String name = validUsername(row.path("username").asText());
                if (!exists(name)) create(name, row.path("password").asText());
            }
        }
        for (var account : users()) if (!account.schema().equals("public")) provision(account, true);
        root.update("DELETE FROM app_sessions WHERE expires_at<=?", clock.millis());
        ready = true;
    }
    private boolean exists(String name) { return root.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?", Integer.class, name) != 0; }
    public synchronized WorkspaceContext.User create(String username, String password) {
        String name = validUsername(username); validPassword(password);
        if (exists(name)) throw new ApiException(409,"username_exists","这个用户名已存在。");
        if (users().size() >= 20) throw new ApiException(409,"account_limit","这台个人学习服务最多创建 20 个账号。");
        String schema = "workspace_" + UUID.randomUUID().toString().replace("-", "");
        var provisional = new WorkspaceContext.User("", name, schema, false, true);
        provision(provisional, true);
        return insert(name, password, schema, false, true);
    }
    private WorkspaceContext.User insert(String name, String password, String schema, boolean admin, boolean mustChange) {
        String id = UUID.randomUUID().toString();
        root.update("INSERT INTO app_users(id,username,password_hash,workspace_schema,is_admin,must_change_password,created_at) VALUES(?,?,?,?,?,?,?)",
                id, validUsername(name), PasswordHash.hash(password), schema, admin, mustChange, clock.millis());
        return new WorkspaceContext.User(id, name, schema, admin, mustChange);
    }
    private void provision(WorkspaceContext.User account, boolean seed) {
        Flyway.configure().dataSource(physical).schemas(account.schema()).defaultSchema(account.schema())
                .locations("classpath:db/migration").load().migrate();
        if (seed) try (var scope = WorkspaceContext.use(account)) { seeds.initialize(); }
    }
    public Login login(String username, String password, String address) {
        String name = username == null ? "" : username.toLowerCase(Locale.ROOT);
        if (name.length() > 32 || password == null || password.length() > 128) throw rejected();
        limit("ip:" + address, 60); limit("name:" + name, 8);
        var rows = root.queryForList("SELECT * FROM app_users WHERE username=? AND enabled=TRUE", name);
        String hash = rows.isEmpty() ? dummyHash : rows.get(0).get("password_hash").toString();
        if (!PasswordHash.matches(password, hash) || rows.isEmpty()) {
            recordFailure("ip:" + address); recordFailure("name:" + name); throw rejected();
        }
        synchronized (failures) { failures.remove("name:" + name); }
        var account = root.query("SELECT * FROM app_users WHERE username=? AND enabled=TRUE", (rs,n) -> user(rs), name).get(0);
        return issue(account);
    }
    private ApiException rejected() { return new ApiException(401,"invalid_credentials","用户名或密码不正确。"); }
    private void limit(String key, int maximum) {
        synchronized (failures) {
            var attempts = failures.get(key);
            if (attempts != null && attempts.expires > clock.millis() && attempts.count >= maximum)
                throw new ApiException(429,"login_limited","登录尝试过多，请在 10 分钟后重试。");
        }
    }
    private void recordFailure(String key) {
        synchronized (failures) {
            failures.entrySet().removeIf(e -> e.getValue().expires <= clock.millis());
            if (failures.size() >= 10_000) failures.remove(failures.keySet().iterator().next());
            var old = failures.get(key);
            failures.put(key,new Attempts(old == null ? 1 : old.count+1, old == null ? clock.millis()+600_000 : old.expires));
        }
    }
    public WorkspaceContext.User authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        return root.query("SELECT u.* FROM app_users u JOIN app_sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires_at>? AND u.enabled=TRUE",
                (rs,n) -> user(rs), digest(token), clock.millis()).stream().findFirst().orElse(null);
    }
    public void logout(String token) { if (token != null) root.update("DELETE FROM app_sessions WHERE token_hash=?", digest(token)); }
    private Login issue(WorkspaceContext.User account) {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        root.update("DELETE FROM app_sessions WHERE expires_at<=?",clock.millis());
        // Bound accumulated browser sessions without invalidating other users.
        if (root.queryForObject("SELECT COUNT(*) FROM app_sessions WHERE user_id=?",Integer.class,account.id()) >= 20)
            root.update("DELETE FROM app_sessions WHERE user_id=?",account.id());
        root.update("INSERT INTO app_sessions(token_hash,user_id,expires_at) VALUES(?,?,?)",digest(token),account.id(),clock.millis()+SESSION_MILLIS);
        return new Login(account,token);
    }
    public synchronized Login changePassword(WorkspaceContext.User account, String current, String replacement) {
        validPassword(replacement);
        if (current == null || current.length()>128 || !PasswordHash.matches(current, root.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,account.id())))
            throw new ApiException(400,"incorrect_password","当前密码不正确。");
        if (replacement.equals(current)) throw ApiException.badRequest("same_password","新密码需要与当前密码不同。");
        replacePassword(account.id(),replacement,false);
        return issue(new WorkspaceContext.User(account.id(),account.username(),account.schema(),account.admin(),false));
    }
    public synchronized void resetPassword(String username, String password) {
        validPassword(password);
        var account = users().stream().filter(u -> u.username().equals(username)).findFirst().orElseThrow(() -> ApiException.notFound("account_not_found","账号不存在。"));
        if (account.admin()) throw ApiException.badRequest("owner_password","管理员请使用修改自己的密码功能。");
        replacePassword(account.id(),password,true);
    }
    private void replacePassword(String id, String password, boolean mustChange) {
        root.update("UPDATE app_users SET password_hash=?,must_change_password=? WHERE id=?",PasswordHash.hash(password),mustChange,id);
        root.update("DELETE FROM app_sessions WHERE user_id=?",id);
    }
    public static String validUsername(String value) {
        String name = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z][a-z0-9_]{2,31}")) throw ApiException.badRequest("invalid_username","用户名为 3–32 位小写字母、数字或下划线，以字母开头。");
        return name;
    }
    public static void validPassword(String password) {
        if (password == null || password.length()<8 || password.length()>128 || password.isBlank())
            throw ApiException.badRequest("invalid_password","密码需要 8–128 个字符。");
    }
    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
