package pt.voleibol.gestao.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
class PlatformController {

    record ProvisionTenantRequest(@NotBlank @Size(max = 120) String name,
                                  @NotBlank @Size(max = 63) String slug,
                                  @NotBlank @Email @Size(max = 254) String adminEmail,
                                  @NotBlank @Size(max = 120) String adminName,
                                  @NotBlank @Size(max = 200) String adminPassword) {
    }

    record ProvisionTenantResponse(UUID tenantId, String slug, String name, UUID adminUserId, String adminEmail) {
        static ProvisionTenantResponse from(ProvisioningService.ProvisionResult r) {
            return new ProvisionTenantResponse(r.tenantId(), r.slug(), r.name(), r.adminUserId(), r.adminEmail());
        }
    }

    private final ProvisioningService provisioningService;

    PlatformController(ProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping("/tenants")
    ResponseEntity<ProvisionTenantResponse> provisionTenant(
            @RequestHeader(value = "X-Bootstrap-Key", required = false) String bootstrapKey,
            @Valid @RequestBody ProvisionTenantRequest request) {
        ProvisioningService.ProvisionResult result = provisioningService.provisionTenant(
                bootstrapKey, request.name(), request.slug(),
                request.adminEmail(), request.adminName(), request.adminPassword());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProvisionTenantResponse.from(result));
    }
}
