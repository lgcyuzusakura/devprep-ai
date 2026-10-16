package com.devprep.ai.auth;

import com.devprep.ai.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(10)
@ConditionalOnProperty(name = "devprep.auth-enabled", havingValue = "true")
public class AccountFilter extends OncePerRequestFilter {
    private final UserAccounts accounts;
    private final ObjectMapper mapper;
    public AccountFilter(UserAccounts accounts, ObjectMapper mapper) { this.accounts=accounts; this.mapper=mapper; }
    public static String token(HttpServletRequest request) {
        if (request.getCookies()!=null) for (Cookie cookie:request.getCookies()) if (UserAccounts.COOKIE.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI();
        if (!path.startsWith("/api/")) { chain.doFilter(request,response); return; }
        response.setHeader("Cache-Control","no-store");
        try {
            if (path.indexOf(';')>=0 || path.indexOf('%')>=0 || path.indexOf('\\')>=0)
                throw new ApiException(400,"invalid_path","接口路径无效。");
            if (!accounts.ready()) throw new ApiException(503,"starting","正在准备学习工作区，请稍后刷新。");
            var user=accounts.authenticate(token(request));
            boolean publicEndpoint=path.equals("/api/auth/login")||path.equals("/api/auth/me");
            if (path.equals("/api/health")) { chain.doFilter(request,response); return; }
            if (user==null&&!publicEndpoint) throw new ApiException(401,"login_required","请先登录你的账号。");
            if (user!=null&&user.mustChangePassword()&&!path.startsWith("/api/auth/"))
                throw new ApiException(403,"password_change_required","首次登录请先修改初始密码。");
            if (user!=null&&!user.admin()&&(path.startsWith("/api/auth/accounts")
                    ||path.equals("/api/models")||path.startsWith("/api/settings/")
                    ||path.equals("/api/settings")&&!request.getMethod().equals("GET")))
                throw new ApiException(403,"admin_required","此操作仅管理员可用。");
            try (var scope=WorkspaceContext.use(user)) { chain.doFilter(request,response); }
        } catch (ApiException ex) {
            if (response.isCommitted()) throw ex;
            response.setStatus(ex.getStatus()); response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(),Map.of("error",Map.of("code",ex.getCode(),"message",ex.getMessage())));
        }
    }
}
