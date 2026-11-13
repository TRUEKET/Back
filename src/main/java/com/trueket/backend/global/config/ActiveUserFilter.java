package com.trueket.backend.global.config;

import com.trueket.backend.user.repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

// Registered in the security chain only, not as a servlet-filter bean.
public class ActiveUserFilter extends OncePerRequestFilter {
    private final UserRepository users;
    public ActiveUserFilter(UserRepository users) { this.users=users; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            boolean active;
            try { active=users.existsByIdAndStatus(Long.valueOf(authentication.getName()),"ACTIVE"); }
            catch (NumberFormatException e) { active=false; }
            if (!active) {
                SecurityContextHolder.clearContext();
                var session=request.getSession(false);
                if (session!=null) session.invalidate();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
