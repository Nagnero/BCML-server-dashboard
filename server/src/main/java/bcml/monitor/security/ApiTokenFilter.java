package bcml.monitor.security;

import bcml.monitor.node.NodeInfo;
import bcml.monitor.node.NodeRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api/** 전용 Bearer 토큰 필터. 경계: 여기서 토큰을 주체(ApiPrincipal)로 바꾸고, 컨트롤러는 주체만 믿는다.
 * 토큰이 없거나 틀리면 401.
 */
final class ApiTokenFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Bearer ";

    private final NodeRegistry nodes;

    ApiTokenFilter(NodeRegistry nodes) {
        this.nodes = nodes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<NodeInfo> node = bearerToken(request).flatMap(nodes::authenticate);
        if (node.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new PreAuthenticatedAuthenticationToken(
                new ApiPrincipal.Agent(node.get()), null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return Optional.empty();
        }
        String token = header.substring(SCHEME.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
