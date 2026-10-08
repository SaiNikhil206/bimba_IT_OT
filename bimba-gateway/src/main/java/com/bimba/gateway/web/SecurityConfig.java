package com.bimba.gateway.web;

import com.bimba.gateway.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Spring Security setup of the gateway console.
 * <ul>
 *   <li>Sign-in state: our own database sessions (SessionAuthFilter reads the HttpOnly SameSite=Strict cookie),
 *       so Spring keeps no HTTP session of its own (STATELESS).</li>
 *   <li>CSRF: Spring's SPA mode: XSRF-TOKEN cookie, which the React app sends back as the X-XSRF-TOKEN header.
 *       /system/* is exempt: site agents authenticate with their client certificate or secret and send no cookies.</li>
 *   <li>Authorization: /api/gw/** needs a signed-in user; each controller method names its permission
 *       with @PreAuthorize("hasAuthority('...')").</li>
 *   <li>Headers: CSP without inline script, frame DENY, nosniff, no-referrer, HSTS over HTTPS.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SessionAuthFilter sessions) throws Exception {
        http
                .csrf(c -> c.spa().ignoringRequestMatchers("/system/**"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/gw/session", "/api/gw/login", "/api/gw/password/forgot", "/api/gw/password/reset").permitAll()
                        .requestMatchers("/api/gw/**").authenticated()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        // the console's static files, the SPA routes and the site agent / status endpoints
                        .anyRequest().permitAll())
                .addFilterBefore(sessions, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, resp, ex) ->
                                json(resp, 401, "signed_out", "Your session has ended. Sign in again."))
                        .accessDeniedHandler((req, resp, ex) -> {
                            if (ex instanceof CsrfException) {
                                json(resp, 403, "csrf", "Request refused. Reload the page.");
                            } else {
                                json(resp, 403, "forbidden", "Your role does not allow this.");
                            }
                        }))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; img-src 'self' data:; style-src 'self';"
                                + " script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    /** Sign-in goes through SecurityService only: no Spring default user (and no generated password in the log). */
    @Bean
    UserDetailsService noBuiltInUsers() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    /** The session filter runs inside the security chain only, not a second time as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<SessionAuthFilter> sessionFilterRegistration(SessionAuthFilter filter) {
        FilterRegistrationBean<SessionAuthFilter> r = new FilterRegistrationBean<>(filter);
        r.setEnabled(false);
        return r;
    }

    static void json(HttpServletResponse resp, int status, String code, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(Json.GSON.toJson(Map.of("error", message, "code", code)));
    }

    /** Makes sure the XSRF-TOKEN cookie is issued on every response (Spring creates it lazily otherwise). */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(req, resp);
        }
    }
}
