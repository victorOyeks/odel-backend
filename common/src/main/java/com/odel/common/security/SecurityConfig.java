package com.odel.common.security;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.common.ApiExceptionHandler;
import com.odel.common.OdelProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, SessionStore store,
                            ObjectMapper json, OdelProperties props) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.secure(props.session().secureCookies()).sameSite("Lax").path("/"));

        http
            .csrf(c -> c.csrfTokenRepository(csrfRepo)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/api/v1/auth/dev/**", "/api/v1/dev/storage/**", "/internal/**",
                            // Paystack posts webhooks without a session or CSRF token; the HMAC signature is checked instead (B2).
                            "/api/v1/payments/webhooks/**", "/api/v1/dev/payments/**",
                            // Bunny Stream's encoding webhook (checked against Bunny's API) and the fake video host's upload (B3).
                            "/api/v1/content/webhooks/**", "/api/v1/dev/content/videos/**"))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .addFilterBefore(new SessionAuthFilter(store), AnonymousAuthenticationFilter.class)
            .addFilterBefore(new InternalKeyFilter(props.internalApiKey()), SessionAuthFilter.class)
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .requestMatchers("/internal/**").permitAll() // InternalKeyFilter checks the key
                    .requestMatchers("/api/v1/public/**", "/api/v1/auth/google/**", "/api/v1/auth/handoff",
                            "/api/v1/auth/dev/**", "/api/v1/dev/**").permitAll()
                    // Students: activation, sign-in and password reset by matric number and emailed code (B1).
                    .requestMatchers("/api/v1/auth/student/**").permitAll()
                    // Paystack's webhook (signature required, checked by payment-service) (B2).
                    .requestMatchers("/api/v1/payments/webhooks/**").permitAll()
                    // Bunny Stream's webhook (content-service looks the video up through Bunny's API before trusting it) (B3).
                    .requestMatchers("/api/v1/content/webhooks/**").permitAll()
                    .requestMatchers("/api/v1/session", "/api/v1/session/**", "/api/v1/auth/mfa/**",
                            "/api/v1/auth/sign-out", "/api/v1/me/active-role").authenticated()
                    .requestMatchers("/api/v1/platform/**").hasAuthority("ROLE_SUPER_ADMIN")
                    .anyRequest().hasAuthority("STAGE_SIGNED_IN"))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> write(res, json, 401, "signed_out", "Please sign in again."))
                    .accessDeniedHandler((req, res, ex) -> {
                        if (ex instanceof CsrfException) write(res, json, 403, "csrf", "Please reload the page and try again.");
                        else write(res, json, 403, "forbidden", "Your role cannot do this.");
                    }));
        return http.build();
    }

    private static void write(HttpServletResponse res, ObjectMapper json, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), ApiExceptionHandler.body(code, message, null, null));
    }

    /** Spring Security defers the CSRF token; touching it makes sure the XSRF-TOKEN cookie is always sent. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws jakarta.servlet.ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(request, response);
        }
    }
}
