package com.sskings.agendai.user;

import com.sskings.agendai.security.CurrentUser;
import com.sskings.agendai.tenant.TenantService;
import com.sskings.agendai.tenant.TenantService.TenantView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class MeController {

    private final TenantService tenants;

    public record MeResponse(UUID id, String name, String email, List<TenantView> tenants) {}

    @GetMapping("/api/me")
    public MeResponse me() {
        User u = CurrentUser.get();
        return new MeResponse(u.getId(), u.getName(), u.getEmail(), tenants.mine(u.getId()));
    }
}
