package bcml.monitor.security;

import bcml.monitor.node.NodeRegistry;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * 필터 체인 두 개.
 *  1) /api/** : 상태 없음, CSRF 끔, ApiTokenFilter. /api/v1/agent/** 는 ROLE_AGENT.
 *  2) 그 밖   : /actuator/health 만 익명 허용, 나머지는 전부 거부(화면과 폼 로그인은 아직 없다).
 */
@Configuration
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http, NodeRegistry nodes) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiTokenFilter(nodes), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/agent/**").hasRole("AGENT")
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a
                        // 오류 응답(/error 로의 내부 전달)까지 막으면 400 이 403 으로 바뀌어 나간다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
