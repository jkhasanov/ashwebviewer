package com.ashweb.security;

import com.ashweb.config.AshWebProperties;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // For load balancers and monitoring; says only UP or DOWN
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        // Viewers may read everything else; any change needs the admin role
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        // Static front end
                        .anyRequest().permitAll())
                .securityContext(context -> context.securityContextRepository(contextRepository))
                // Cookie based CSRF token for the single page app. Login is exempt: there is no session to ride yet.
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/auth/login"))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsername(username)
                .map(u -> User.withUsername(u.username())
                        .password(u.passwordHash())
                        .roles(u.role())
                        .disabled(!u.enabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /** Creates the first admin account so a fresh installation can be logged in to. */
    @Bean
    ApplicationRunner initialAdmin(UserRepository users, PasswordEncoder encoder, AshWebProperties properties) {
        return args -> {
            if (users.count() > 0) {
                return;
            }
            String password = properties.security().initialAdminPassword();
            boolean generated = password == null || password.isBlank();
            if (generated) {
                byte[] bytes = new byte[12];
                new SecureRandom().nextBytes(bytes);
                password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }
            users.insert("admin", encoder.encode(password), "ADMIN");
            if (generated) {
                log.warn("Created user 'admin' with password '{}'. Change it after the first login.", password);
            } else {
                log.info("Created user 'admin' with the configured initial password.");
            }
        };
    }
}
