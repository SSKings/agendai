package com.sskings.agendai.catalog;

import com.sskings.agendai.common.PlainText;
import com.sskings.agendai.common.Texts;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {}

    public record ServiceRequest(
            @NotBlank @Size(max = 100) @PlainText String name,
            @NotNull @Min(5) @Max(CatalogService.MAX_DURATION) Integer durationMinutes,
            @NotNull @Min(0) @Max(CatalogService.MAX_BUFFER) Integer bufferMinutes,
            @NotNull @Min(0) @Max(10_000_000) Integer priceCents,
            @NotNull Boolean active) {
        public ServiceRequest {
            name = Texts.clean(name);
        }
    }

    public record ServiceView(UUID id, String name, int durationMinutes, int bufferMinutes, int priceCents, boolean active) {
        static ServiceView of(Service s) {
            return new ServiceView(s.getId(), s.getName(), s.getDurationMinutes(), s.getBufferMinutes(), s.getPriceCents(), s.isActive());
        }
    }

    public record ProfessionalRequest(
            @NotBlank @Size(max = 100) @PlainText String name,
            @NotNull Boolean active,
            @NotNull @Size(max = 100) List<@NotNull UUID> serviceIds) {
        public ProfessionalRequest {
            name = Texts.clean(name);
        }
    }

    public record ProfessionalView(UUID id, String name, boolean active, List<UUID> serviceIds) {}
}
