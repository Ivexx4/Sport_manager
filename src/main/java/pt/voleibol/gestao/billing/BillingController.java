package pt.voleibol.gestao.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.shared.CurrentUser;

@RestController
@RequestMapping("/api/v1")
class BillingController {

    record FeePlanRequest(@NotBlank String name, UUID seasonId, UUID ageGroupId, @NotNull Long amountCents,
                          String periodicity) {
    }

    record ChargeRequest(@NotNull UUID athleteId, UUID guardianId, UUID feePlanId, @NotNull Long amountCents,
                         @NotNull LocalDate dueDate, String status) {
    }

    record GenerateChargesRequest(String period, UUID feePlanId, Boolean dryRun) {
    }

    record PromotionRequest(@NotNull UUID athleteId, UUID feePlanId, @NotNull Long amountCents,
                            @NotNull LocalDate validFrom, LocalDate validTo, String reason,
                            String applicationScope, String billingPeriod) {
    }

    record PaymentRequest(@NotNull UUID chargeId, String method, @NotNull Long amountCents, String reference) {
    }

    record LedgerRequest(@NotBlank String type, @NotBlank String category, @NotNull Long amountCents,
                         @NotNull LocalDate entryDate, String description) {
    }

    record StatusRequest(String status) {
    }

    private final BillingService service;

    BillingController(BillingService service) {
        this.service = service;
    }

    // fee plans
    @GetMapping("/fee-plans")
    List<BillingService.FeePlanView> listFeePlans(@AuthenticationPrincipal Jwt jwt) {
        return service.listFeePlans(CurrentUser.of(jwt));
    }

    @PostMapping("/fee-plans")
    ResponseEntity<BillingService.FeePlanView> createFeePlan(@AuthenticationPrincipal Jwt jwt,
                                                             @Valid @RequestBody FeePlanRequest r) {
        BillingService.FeePlanView created = service.createFeePlan(CurrentUser.of(jwt), r.name(),
                r.seasonId(), r.ageGroupId(), r.amountCents(), r.periodicity());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/fee-plans/{id}")
    ResponseEntity<Void> deleteFeePlan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteFeePlan(CurrentUser.of(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/athletes/{athleteId}/promotions")
    List<BillingService.PromotionView> listPromotions(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable UUID athleteId) {
        return service.listPromotions(CurrentUser.of(jwt), athleteId);
    }

    @PostMapping("/athlete-promotions")
    ResponseEntity<BillingService.PromotionView> createPromotion(@AuthenticationPrincipal Jwt jwt,
                                                                 @Valid @RequestBody PromotionRequest r) {
        BillingService.PromotionView created = service.createPromotion(CurrentUser.of(jwt),
                r.athleteId(), r.feePlanId(), r.amountCents(), r.validFrom(), r.validTo(), r.reason(),
                r.applicationScope(), r.billingPeriod());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/athlete-promotions/{id}/active")
    ResponseEntity<Void> setPromotionActive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @RequestBody StatusRequest r) {
        service.setPromotionActive(CurrentUser.of(jwt), id,
                r != null && "ACTIVE".equalsIgnoreCase(r.status()));
        return ResponseEntity.noContent().build();
    }

    // charges
    @GetMapping("/charges")
    List<BillingService.ChargeView> listCharges(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(required = false) UUID athleteId,
                                                @RequestParam(required = false) String status) {
        return service.listCharges(CurrentUser.of(jwt), athleteId, status);
    }

    @PostMapping("/charges")
    ResponseEntity<BillingService.ChargeView> createCharge(@AuthenticationPrincipal Jwt jwt,
                                                           @Valid @RequestBody ChargeRequest r) {
        BillingService.ChargeView created = service.createCharge(CurrentUser.of(jwt), r.athleteId(),
                r.guardianId(), r.feePlanId(), r.amountCents(), r.dueDate(), r.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/charges/generate")
    BillingService.GenerateView generateCharges(@AuthenticationPrincipal Jwt jwt,
                                                @RequestBody(required = false) GenerateChargesRequest r) {
        return service.generateCharges(CurrentUser.of(jwt), r == null ? null : r.period(),
                r == null ? null : r.feePlanId(), r != null && Boolean.TRUE.equals(r.dryRun()));
    }

    @PatchMapping("/charges/{id}/status")
    ResponseEntity<Void> updateChargeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @RequestBody(required = false) StatusRequest r) {
        service.updateChargeStatus(CurrentUser.of(jwt), id, r == null ? null : r.status());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/charges/{id}")
    ResponseEntity<Void> deleteCharge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteCharge(CurrentUser.of(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // payments
    @GetMapping("/charges/{id}/payments")
    List<BillingService.PaymentView> listPayments(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listPayments(CurrentUser.of(jwt), id);
    }

    @PostMapping("/payments")
    ResponseEntity<BillingService.PaymentView> createPayment(@AuthenticationPrincipal Jwt jwt,
                                                             @Valid @RequestBody PaymentRequest r) {
        BillingService.PaymentView created = service.createPayment(CurrentUser.of(jwt),
                r.chargeId(), r.method(), r.amountCents(), r.reference());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ledger
    @GetMapping("/ledger-entries")
    List<BillingService.LedgerView> listLedger(@AuthenticationPrincipal Jwt jwt) {
        return service.listLedger(CurrentUser.of(jwt));
    }

    @PostMapping("/ledger-entries")
    ResponseEntity<BillingService.LedgerView> createLedger(@AuthenticationPrincipal Jwt jwt,
                                                           @Valid @RequestBody LedgerRequest r) {
        BillingService.LedgerView created = service.createLedger(CurrentUser.of(jwt), r.type(),
                r.category(), r.amountCents(), r.entryDate(), r.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/ledger-entries/{id}")
    ResponseEntity<Void> deleteLedger(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteLedger(CurrentUser.of(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
