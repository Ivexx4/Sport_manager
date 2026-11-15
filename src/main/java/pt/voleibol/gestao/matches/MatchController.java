package pt.voleibol.gestao.matches;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pt.voleibol.gestao.shared.Auth;

@RestController
@RequestMapping("/api/v1")
class MatchController {

    record MatchRequest(@NotNull UUID teamId, String competition, @NotBlank String opponent, String location,
                        @JsonAlias({"venueId", "courtId"}) UUID courtId, @NotNull Instant matchDate,
                        @JsonAlias({"home", "isHome"}) Boolean isHome, String status) {
    }

    record SetScoreRequest(@NotNull Integer setNumber, @NotNull Integer ourPoints, @NotNull Integer opponentPoints) {
    }

    record CallUpRequest(@NotNull UUID athleteId) {
    }

    record RespondRequest(String response) {
    }

    private final MatchService service;

    MatchController(MatchService service) {
        this.service = service;
    }

    // matches

    @GetMapping("/matches")
    List<MatchService.MatchView> listMatches(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(required = false) UUID teamId) {
        return service.listMatches(Auth.actor(jwt), Auth.roles(jwt), teamId);
    }

    @GetMapping("/matches/{id}")
    MatchService.MatchView getMatch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.getMatch(Auth.actor(jwt), Auth.roles(jwt), id);
    }

    @PostMapping("/matches")
    ResponseEntity<MatchService.MatchView> createMatch(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody MatchRequest request) {
        MatchService.MatchView created = service.createMatch(Auth.actor(jwt), Auth.roles(jwt), request.teamId(),
                request.competition(), request.opponent(), request.location(), request.courtId(),
                request.matchDate(), request.isHome(), request.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/matches/{id}")
    MatchService.MatchView updateMatch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody MatchRequest request) {
        return service.updateMatch(Auth.actor(jwt), Auth.roles(jwt), id, request.teamId(), request.competition(),
                request.opponent(), request.location(), request.courtId(), request.matchDate(),
                request.isHome(), request.status());
    }

    @DeleteMapping("/matches/{id}")
    ResponseEntity<Void> deleteMatch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteMatch(Auth.actor(jwt), Auth.roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // set scores

    @GetMapping("/matches/{id}/set-scores")
    List<MatchService.SetScoreView> listSetScores(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listSetScores(Auth.actor(jwt), Auth.roles(jwt), id);
    }

    @PostMapping("/matches/{id}/set-scores")
    ResponseEntity<Void> setSetScore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @Valid @RequestBody SetScoreRequest request) {
        service.setSetScore(Auth.actor(jwt), Auth.roles(jwt), id, request.setNumber(), request.ourPoints(),
                request.opponentPoints());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/matches/{id}/set-scores/{setNumber}")
    ResponseEntity<Void> setSetScoreByPath(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @PathVariable int setNumber,
                                           @Valid @RequestBody SetScoreRequest request) {
        service.setSetScore(Auth.actor(jwt), Auth.roles(jwt), id,
                request == null || request.setNumber() == null ? setNumber : request.setNumber(),
                request == null || request.ourPoints() == null ? 0 : request.ourPoints(),
                request == null || request.opponentPoints() == null ? 0 : request.opponentPoints());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/matches/{id}/set-scores/{setNumber}")
    ResponseEntity<Void> deleteSetScore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                        @PathVariable int setNumber) {
        service.deleteSetScore(Auth.actor(jwt), Auth.roles(jwt), id, setNumber);
        return ResponseEntity.noContent().build();
    }

    // call ups

    @GetMapping("/matches/{id}/call-ups")
    List<MatchService.CallUpView> listCallUps(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listCallUps(Auth.actor(jwt), Auth.roles(jwt), id);
    }

    @PostMapping("/matches/{id}/call-ups")
    ResponseEntity<Void> addCallUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @Valid @RequestBody CallUpRequest request) {
        service.addCallUp(Auth.actor(jwt), Auth.roles(jwt), id, request.athleteId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/matches/{id}/call-ups/{athleteId}")
    ResponseEntity<Void> removeCallUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                      @PathVariable UUID athleteId) {
        service.removeCallUp(Auth.actor(jwt), Auth.roles(jwt), id, athleteId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/matches/{id}/call-ups/{athleteId}")
    ResponseEntity<Void> respondCallUp(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @PathVariable UUID athleteId,
                                       @RequestBody(required = false) RespondRequest request) {
        return respond(jwt, id, athleteId, request);
    }

    @PostMapping("/matches/{id}/call-ups/{athleteId}/respond")
    ResponseEntity<Void> respondCallUpPost(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @PathVariable UUID athleteId,
                                           @RequestBody(required = false) RespondRequest request) {
        return respond(jwt, id, athleteId, request);
    }

    @PostMapping("/matches/{id}/call-ups/{athleteId}/response")
    ResponseEntity<Void> respondCallUpPost2(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @PathVariable UUID athleteId,
                                            @RequestBody(required = false) RespondRequest request) {
        return respond(jwt, id, athleteId, request);
    }

    private ResponseEntity<Void> respond(Jwt jwt, UUID matchId, UUID athleteId, RespondRequest request) {
        service.respondCallUp(Auth.actor(jwt), Auth.roles(jwt), matchId, athleteId,
                request == null ? null : request.response());
        return ResponseEntity.noContent().build();
    }
}
