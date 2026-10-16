package com.devprep.ai.api;

import com.devprep.ai.auth.AccountFilter;
import com.devprep.ai.auth.UserAccounts;
import com.devprep.ai.auth.WorkspaceContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
public class AccountController {
    private final ObjectProvider<UserAccounts> provider;
    private final boolean secure;
    public AccountController(ObjectProvider<UserAccounts> provider,@Value("${devprep.auth-secure-cookie:true}") boolean secure) { this.provider=provider; this.secure=secure; }
    private UserAccounts accounts() {
        var accounts=provider.getIfAvailable();
        if(accounts==null)throw new ApiException(404,"accounts_disabled","当前使用本机单人模式。");
        return accounts;
    }
    public static Map<String,Object> info(WorkspaceContext.User user) {
        return Map.of("username",user.username(),"admin",user.admin(),"must_change_password",user.mustChangePassword());
    }
    @GetMapping("/me") public Object me() {
        if(provider.getIfAvailable()==null)return Map.of("enabled",false);
        var user=WorkspaceContext.current();
        return user==null?Map.of("enabled",true,"authenticated",false):Map.of("enabled",true,"authenticated",true,"user",info(user));
    }
    @PostMapping("/login") public Object login(@RequestBody JsonNode body,HttpServletRequest request,HttpServletResponse response) {
        var login=accounts().login(text(body,"username"),text(body,"password"),request.getRemoteAddr());
        // Successful login rotates this browser's old session as well.
        accounts().logout(AccountFilter.token(request)); cookie(response,login.token(),false);
        return Map.of("user",info(login.user()));
    }
    @PostMapping("/logout") public Object logout(HttpServletRequest request,HttpServletResponse response) {
        accounts().logout(AccountFilter.token(request));cookie(response,"",true);return Map.of("ok",true);
    }
    @PostMapping("/password") public Object password(@RequestBody JsonNode body,HttpServletResponse response) {
        var login=accounts().changePassword(requireUser(),text(body,"current_password"),text(body,"new_password"));
        cookie(response,login.token(),false);return Map.of("user",info(login.user()));
    }
    @GetMapping("/accounts") public Object list() { requireAdmin(); return Map.of("accounts",accounts().users().stream().map(AccountController::info).toList()); }
    @PostMapping("/accounts") public Object create(@RequestBody JsonNode body) {
        requireAdmin(); return Map.of("user",info(accounts().create(text(body,"username"),text(body,"password"))));
    }
    @PostMapping("/accounts/{username}/reset-password") public Object reset(@PathVariable String username,@RequestBody JsonNode body) {
        requireAdmin();accounts().resetPassword(username,text(body,"password"));return Map.of("ok",true);
    }
    private WorkspaceContext.User requireUser() {
        var user=WorkspaceContext.current();
        if(user==null)throw new ApiException(401,"login_required","请先登录。");
        return user;
    }
    private void requireAdmin() { if(!requireUser().admin())throw new ApiException(403,"admin_required","此操作仅管理员可用。"); }
    private void cookie(HttpServletResponse response,String token,boolean clear) {
        response.addHeader("Set-Cookie",ResponseCookie.from(UserAccounts.COOKIE,token).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/").maxAge(clear?Duration.ZERO:Duration.ofDays(7)).build().toString());
    }
    private String text(JsonNode body,String key) {
        if(!body.isObject()||!body.path(key).isTextual())throw ApiException.badRequest("invalid_account_input","请填写正确的账号信息。");
        return body.path(key).textValue();
    }
}
