package com.bimba.gateway.web;

import com.bimba.gateway.security.SecurityService;
import com.bimba.gateway.security.SecurityService.Principal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the console session cookie into the Spring Security principal (authorities = the user's permissions).
 * Accounts that must change their password only reach the session, profile and sign-out calls. Platform admins also get
 * the authority platform.admin (organizations, machine owners).
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {
    public static final String COOKIE = "bimba_gw";
    /** the organization the web app shows (its id), sent with every call */
    public static final String ORG_HEADER = "X-BIMBA-Org";
    public static final String PLATFORM_ADMIN = "platform.admin";
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Security");

    private final SecurityService security;

    public SessionAuthFilter(SecurityService security) {
        this.security = security;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/gw/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String token = cookie(req);
        if (token != null) {
            Principal p;
            try {
                // the web app marks its automatic refreshes so they do not keep an unattended session alive
                p = security.authenticate(token, req.getRemoteAddr(), !"1".equals(req.getHeader("X-BIMBA-Background")));
            } catch (DataAccessException e) {
                log.warn("session check failed: {}", e.getMessage());
                SecurityConfig.json(resp, 503, "database", "The user database is not reachable. Try again shortly.");
                return;
            }
            if (p != null) {
                String path = req.getRequestURI();
                boolean passwordRoute = path.equals("/api/gw/session") || path.equals("/api/gw/logout") || path.startsWith("/api/gw/profile");
                if (p.mustChangePassword() && !passwordRoute) {
                    SecurityConfig.json(resp, 403, "password_change_required", "Choose a new password first.");
                    return;
                }
                // The web app names the organization it shows. When another tab of this browser switched the session to
                // another organization, refuse instead of acting in an organization the user is not looking at.
                String shown = req.getHeader(ORG_HEADER);
                boolean anyOrgRoute = passwordRoute || path.equals("/api/gw/session/organization");
                if (shown != null && !shown.isBlank() && !anyOrgRoute && !shown.equals(p.orgId() == null ? "" : String.valueOf(p.orgId()))) {
                    SecurityConfig.json(resp, 409, "org_changed", "You switched to " + (p.orgName() == null ? "another organization" : p.orgName())
                            + " in another tab. This page now shows that organization.");
                    return;
                }
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                p.permissions().forEach(perm -> authorities.add(new SimpleGrantedAuthority(perm)));
                if (p.superuser()) {
                    authorities.add(new SimpleGrantedAuthority(PLATFORM_ADMIN));
                }
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p, null, authorities));
            }
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static String cookie(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
