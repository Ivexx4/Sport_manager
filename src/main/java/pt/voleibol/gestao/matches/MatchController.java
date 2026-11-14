package pt.voleibol.gestao.matches;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
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
class MatchController {

    record MatchRequest(@NotNull UUID teamId, String competition, @NotBlank String opponent, String location,
                        @JsonAlias({"venueId", "courtId"}) UUID courtId, @NotNull Instant matchDate, @JsonAlias({"home", "isHome"}) Boolean isHome, String status) {
    }

    record MatchResponse(UUID id, UUID teamId, String competition, String opponent, String location, UUID courtId,
                         Instant matchDate, boolean isHome, String status, Instant createdAt, Instant updatedAt) {
        static MatchResponse from(MatchService.MatchView v) {
            return new MatchResponse(v.id(), v.teamId(), v.competition(), v.opponent(), v.location(), v.courtId(),
                    v.matchDate(), v.isHome(), v.status(), v.createdAt(), v.updatedAt());
        }
    }

    record SetScoreRequest(@NotNull Integer setNumber, @NotNull Integer ourPoints, @NotNull Integer opponentPoints) {
    }

    record SetScoreResponse(int setNumber, int ourPoints, int opponentPoints) {
        static SetScoreResponse from(MatchService.SetScoreView v) {
            return new SetScoreResponse(v.setNumber(), v.ourPoints(), v.opponentPoints());
        }
    }

    record CallUpRequest(@NotNull UUID athleteId) {
    }

    record CallUpResponse(UUID athleteId, String fullName, String status, String response, Instant respondedAt) {
        static CallUpResponse from(MatchService.CallUpView v) {
            return new CallUpResponse(v.athleteId(), v.fullName(), v.status(), v.response(), v.respondedAt());
        }
    }

    record RespondRequest(String response) {
    }

    private final MatchService service;

    MatchController(MatchService service) {
        this.service = service;
    }

    // matches

    @GetMapping("/matches")
    List<MatchResponse> listMatches(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(required = false) UUID teamId) {
        return service.listMatches(actor(jwt), roles(jwt), teamId).stream().map(MatchResponse::from).toList();
    }

    @GetMapping("/matches/{id}")
    MatchResponse getMatch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return MatchResponse.from(service.getMatch(actor(jwt), roles(jwt), id));
    }

    @PostMapping("/matches")
    ResponseEntity<MatchResponse> createMatch(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody MatchRequest request) {
        MatchResponse created = MatchResponse.from(service.createMatch(
                actor(jwt), roles(jwt), request.teamId(), request.competition(), request.opponent(),
                request.location(), request.courtId(), request.matchDate(), request.isHome(), request.status()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/matches/{id}")
    MatchResponse updateMatch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @Valid @RequestBody MatchRequest request) {
        return MatchResponse.from(service.updateMatch(
                actor(jwt), roles(jwt), id, request.teamId(), request.competition(), request.opponent(),
                request.location(), request.courtId(), request.matchDate(), request.isHome(), request.status()));
    }

    @DeleteMapping("/matches/{id}")
    ResponseEntity<Void> deleteMatch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteMatch(actor(jwt), roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // set scores

    @GetMapping("/matches/{id}/set-scores")
    List<SetScoreResponse> listSetScores(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listSetScores(actor(jwt), roles(jwt), id).stream().map(SetScoreResponse::from).toList();
    }

    @PostMapping("/matches/{id}/set-scores")
    ResponseEntity<Void> setSetScore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody SetScoreRequest request) {
        service.setSetScore(actor(jwt), roles(jwt), id, request.setNumber(), request.ourPoints(),
                request.opponentPoints());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/matches/{id}/set-scores/{setNumber}")
    ResponseEntity<Void> setSetScoreByPath(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @PathVariable int setNumber,
                                          @Valid @RequestBody SetScoreRequest request) {
        int our = request == null || request.ourPoints() == null ? 0 : request.ourPoints();
        int opp = request == null || request.opponentPoints() == null ? 0 : request.opponentPoints();
        int sn = request == null || request.setNumber() == null ? setNumber : request.setNumber();
        service.setSetScore(actor(jwt), roles(jwt), id, sn, our, opp);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/matches/{id}/set-scores/{setNumber}")
    ResponseEntity<Void> deleteSetScore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @PathVariable int setNumber) {
        service.deleteSetScore(actor(jwt), roles(jwt), id, setNumber);
        return ResponseEntity.noContent().build();
    }

    // call ups

    @GetMapping("/matches/{id}/call-ups")
    List<CallUpResponse> listCallUps(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listCallUps(actor(jwt), roles(jwt), id).stream().map(CallUpResponse::from).toList();
    }

    @PostMapping("/matches/{id}/call-ups")
    ResponseEntity<Void> addCallUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody CallUpRequest request) {
        service.addCallUp(actor(jwt), roles(jwt), id, request.athleteId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/matches/{id}/call-ups/{athleteId}")
    ResponseEntity<Void> removeCallUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @PathVariable UUID athleteId) {
        service.removeCallUp(actor(jwt), roles(jwt), id, athleteId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/matches/{id}/call-ups/{athleteId}")
    ResponseEntity<Void> respondCallUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                      @PathVariable UUID athleteId, @RequestBody(required = false) RespondRequest request) {
        String response = request == null ? null : request.response();
        service.respondCallUp(actor(jwt), roles(jwt), id, athleteId, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/matches/{id}/call-ups/{athleteId}/respond")
    ResponseEntity<Void> respondCallUpPost(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @PathVariable UUID athleteId, @RequestBody(required = false) RespondRequest request) {
        String response = request == null ? null : request.response();
        service.respondCallUp(actor(jwt), roles(jwt), id, athleteId, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/matches/{id}/call-ups/{athleteId}/response")
    ResponseEntity<Void> respondCallUpPost2(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @PathVariable UUID athleteId, @RequestBody(required = false) RespondRequest request) {
        String response = request == null ? null : request.response();
        service.respondCallUp(actor(jwt), roles(jwt), id, athleteId, response);
        return ResponseEntity.noContent().build();
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static Set<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Set.of() : Set.copyOf(roles);
    }
}