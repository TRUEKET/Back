package com.trueket.backend.analyze.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Runs before session invalidation; removes only this browser's temporary analysis. */
@Component
public class AnalysisLogoutHandler implements LogoutHandler {
    private final AnalysisSessionStore sessions;

    public AnalysisLogoutHandler(AnalysisSessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute("analysisOwner") instanceof UUID owner) {
            sessions.deleteByOwner(owner);
            session.removeAttribute("analysisOwner");
        }
    }
}
