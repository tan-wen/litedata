package com.litedata.security;

import com.litedata.store.PlatformMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService userDetailsService(PlatformMapper mapper) {
        return name -> {
            var user = mapper.findUser(name);
            if (user == null || !user.isEnabled()) throw new UsernameNotFoundException("用户不存在");
            return User.withUsername(user.getUsername()).password(user.getPasswordHash()).roles(user.getRole()).build();
        };
    }
    @Bean @Order(1) SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/**", "/internal/**", "/mcp", "/mcp/**")
                .csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
    @Bean SecurityFilterChain pageSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a
                .requestMatchers("/login", "/css/**", "/js/**", "/vendor/**", "/favicon.ico", "/actuator/health").permitAll()
                .requestMatchers("/users", "/users/**").hasRole("ADMIN")
                .requestMatchers("/settings/api-keys", "/settings/api-keys/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/services/*/credentials").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET,"/scheduling/*/*/instances/*/tasks/*/log").hasAnyRole("ADMIN","DEVELOPER")
                .requestMatchers(HttpMethod.GET, "/sources/new", "/sources/*/edit", "/pipelines/new", "/pipelines/*/edit",
                        "/pipelines/source-tables",
                        "/services/new", "/services/*/edit", "/metadata/collect", "/quality/new", "/quality/*/edit",
                        "/metrics/new", "/metrics/*/edit", "/assets/*/*/edit").hasAnyRole("ADMIN", "DEVELOPER")
                .requestMatchers(HttpMethod.GET, "/**").authenticated()
                .anyRequest().hasAnyRole("ADMIN", "DEVELOPER"))
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                .csrf(Customizer.withDefaults()).build();
    }
}

