package pt.voleibol.gestao.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class BillingController {

    record FeePlanRequest(@NotBlank String name, UUID seasonId, UUID ageGroupId, @NotNull Long amountCents, String periodicity) {
    }
    record FeePlanResponse(UUID id, String name, UUID seasonId, UUID ageGroupId, long amountCents, String periodicity) {
        static FeePlanResponse from(BillingService.FeePlanView v) {
            return new FeePlanResponse(v.id(), v.name(), v.seasonId(), v.ageGroupId(), v.amountCents(), v.periodicity());
        }
    }

    record ChargeRequest(@NotNull UUID athleteId, UUID guardianId, UUID feePlanId, @NotNull Long amountCents, @NotNull LocalDate dueDate, String status) {
    }
    record ChargeResponse(UUID id, UUID athleteId, UUID guardianId, UUID feePlanId, long amountCents, LocalDate dueDate, String status) {
        static ChargeResponse from(BillingService.ChargeView v) {
            return new ChargeResponse(v.id(), v.athleteId(), v.guardianId(), v.feePlanId(), v.amountCents(), v.dueDate(), v.status());
        }
    }

    record PaymentRequest(@NotNull UUID chargeId, String method, @NotNull Long amountCents, String reference) {
    }
    record PaymentResponse(UUID id, UUID chargeId, String method, long amountCents, String reference, java.time.Instant paidAt) {
        static PaymentResponse from(BillingService.PaymentView v) {
            return new PaymentResponse(v.id(), v.chargeId(), v.method(), v.amountCents(), v.reference(), v.paidAt());
        }
    }

    record LedgerRequest(@NotBlank String type, @NotBlank String category, @NotNull Long amountCents, @NotNull LocalDate entryDate, String description) {
    }
    record LedgerResponse(UUID id, String type, String category, long amountCents, LocalDate entryDate, String description) {
        static LedgerResponse from(BillingService.LedgerView v) {
            return new LedgerResponse(v.id(), v.type(), v.category(), v.amountCents(), v.entryDate(), v.description());
        }
    }

    record StatusRequest(String status) {
    }

    private final BillingService service;

    BillingController(BillingService service) { this.service = service; }

    // fee plans
    @GetMapping("/fee-plans")
    List<FeePlanResponse> listFeePlans(@AuthenticationPrincipal Jwt jwt) {
        return service.listFeePlans(roles(jwt)).stream().map(FeePlanResponse::from).toList();
    }
    @PostMapping("/fee-plans")
    ResponseEntity<FeePlanResponse> createFeePlan(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FeePlanRequest r) {
        FeePlanResponse c = FeePlanResponse.from(service.createFeePlan(actor(jwt), roles(jwt), r.name(), r.seasonId(), r.ageGroupId(), r.amountCents(), r.periodicity()));
        return ResponseEntity.status(HttpStatus.CREATED).body(c);
    }
    @DeleteMapping("/fee-plans/{id}")
    ResponseEntity<Void> deleteFeePlan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteFeePlan(actor(jwt), roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // charges
    @GetMapping("/charges")
    List<ChargeResponse> listCharges(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID athleteId, @RequestParam(required = false) String status) {
        return service.listCharges(actor(jwt), roles(jwt), athleteId, status).stream().map(ChargeResponse::from).toList();
    }
    @PostMapping("/charges")
    ResponseEntity<ChargeResponse> createCharge(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChargeRequest r) {
        ChargeResponse c = ChargeResponse.from(service.createCharge(actor(jwt), roles(jwt), r.athleteId(), r.guardianId(), r.feePlanId(), r.amountCents(), r.dueDate(), r.status()));
        return ResponseEntity.status(HttpStatus.CREATED).body(c);
    }
    @PatchMapping("/charges/{id}/status")
    ResponseEntity<Void> updateChargeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody(required = false) StatusRequest r) {
        service.updateChargeStatus(actor(jwt), roles(jwt), id, r == null ? null : r.status());
        return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/charges/{id}")
    ResponseEntity<Void> deleteCharge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteCharge(actor(jwt), roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // payments
    @GetMapping("/charges/{id}/payments")
    List<PaymentResponse> listPayments(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listPayments(roles(jwt), id).stream().map(PaymentResponse::from).toList();
    }
    @PostMapping("/payments")
    ResponseEntity<PaymentResponse> createPayment(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaymentRequest r) {
        PaymentResponse c = PaymentResponse.from(service.createPayment(actor(jwt), roles(jwt), r.chargeId(), r.method(), r.amountCents(), r.reference()));
        return ResponseEntity.status(HttpStatus.CREATED).body(c);
    }

    // ledger
    @GetMapping("/ledger-entries")
    List<LedgerResponse> listLedger(@AuthenticationPrincipal Jwt jwt) {
        return service.listLedger(roles(jwt)).stream().map(LedgerResponse::from).toList();
    }
    @PostMapping("/ledger-entries")
    ResponseEntity<LedgerResponse> createLedger(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LedgerRequest r) {
        LedgerResponse c = LedgerResponse.from(service.createLedger(actor(jwt), roles(jwt), r.type(), r.category(), r.amountCents(), r.entryDate(), r.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(c);
    }
    @DeleteMapping("/ledger-entries/{id}")
    ResponseEntity<Void> deleteLedger(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteLedger(actor(jwt), roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static Set<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Set.of() : Set.copyOf(roles);
    }
}