package com.sskings.agendai.catalog;

import com.sskings.agendai.catalog.InviteService.AcceptRequest;
import com.sskings.agendai.catalog.InviteService.AcceptedView;
import com.sskings.agendai.catalog.InviteService.InviteView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class InviteController {

    private final InviteService invites;

    @PostMapping("/api/tenants/{tenantId}/professionals/{professionalId}/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteView create(@PathVariable UUID tenantId, @PathVariable UUID professionalId) {
        return invites.create(tenantId, professionalId);
    }

    @DeleteMapping("/api/tenants/{tenantId}/professionals/{professionalId}/invite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID tenantId, @PathVariable UUID professionalId) {
        invites.revoke(tenantId, professionalId);
    }

    @DeleteMapping("/api/tenants/{tenantId}/professionals/{professionalId}/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAccount(@PathVariable UUID tenantId, @PathVariable UUID professionalId) {
        invites.removeAccount(tenantId, professionalId);
    }

    /** O código vai no corpo (nunca na URL): não fica em log de acesso nem em histórico do navegador. */
    @PostMapping("/api/invites/accept")
    public AcceptedView accept(@RequestBody AcceptRequest body) {
        return invites.accept(body);
    }
}
