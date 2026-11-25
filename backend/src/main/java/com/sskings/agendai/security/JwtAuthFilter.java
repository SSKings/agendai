package com.sskings.agendai.security;

import com.sskings.agendai.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Autentica pelo cabeçalho "Authorization: Bearer". Carrega o usuário do banco a cada requisição: token de versão
 * antiga (logout, troca de senha) ou de usuário que não existe mais não autentica.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            JwtService.Claims claims = jwt.validate(header.substring(7).trim());
            if (claims != null) {
                try {
                    users.findById(claims.userId())
                            .filter(u -> u.getTokenVersion() == claims.version())
                            .ifPresent(u -> SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken(u, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
                } catch (org.springframework.transaction.CannotCreateTransactionException e) {
                    // pool sem conexão a tempo: aqui é filtro (o GlobalExceptionHandler não alcança) — 503, não 500
                    response.setStatus(503);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"status\":503,\"message\":\"Busy, try again\",\"errors\":null}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
