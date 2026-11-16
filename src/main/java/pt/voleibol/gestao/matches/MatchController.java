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

import pt.voleibol.gestao.shared.CurrentUser;

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
    List<MatchService.MatchView> listMatches(CurrentUser user,
                                             @RequestParam(required = false) UUID teamId) {
        return service.listMatches(user, teamId);
    }

    @GetMapping("/matches/{id}")
    MatchService.MatchView getMatch(CurrentUser user, @PathVariable UUID id) {
        return service.getMatch(user, id);
    }

    @PostMapping("/matches")
    ResponseEntity<MatchService.MatchView> createMatch(CurrentUser user,
                                                       @Valid @RequestBody MatchRequest request) {
        MatchService.MatchView created = service.createMatch(user, request.teamId(),
                request.competition(), request.opponent(), request.location(), request.courtId(),
                request.matchDate(), request.isHome(), request.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/matches/{id}")
    MatchService.MatchView updateMatch(CurrentUser user, @PathVariable UUID id,
                                       @Valid @RequestBody MatchRequest request) {
        return service.updateMatch(user, id, request.teamId(), request.competition(),
                request.opponent(), request.location(), request.courtId(), request.matchDate(),
                request.isHome(), request.status());
    }

    @DeleteMapping("/matches/{id}")
    ResponseEntity<Void> deleteMatch(CurrentUser user, @PathVariable UUID id) {
        service.deleteMatch(user, id);
        return ResponseEntity.noContent().build();
    }

    // set scores

    @GetMapping("/matches/{id}/set-scores")
    List<MatchService.SetScoreView> listSetScores(CurrentUser user, @PathVariable UUID id) {
        return service.listSetScores(user, id);
    }

    @PostMapping("/matches/{id}/set-scores")
    ResponseEntity<Void> setSetScore(CurrentUser user, @PathVariable UUID id,
                                     @Valid @RequestBody SetScoreRequest request) {
        service.setSetScore(user, id, request.setNumber(), request.ourPoints(), request.opponentPoints());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/matches/{id}/set-scores/{setNumber}")
    ResponseEntity<Void> setSetScoreByPath(CurrentUser user, @PathVariable UUID id,
                                           @PathVariable int setNumber,
                                           @Valid @RequestBody SetScoreRequest request) {
        service.setSetScore(user, id,
                request == null || request.setNumber() == null ? setNumber : request.setNumber(),
                request == null || request.ourPoints() == null ? 0 : request.ourPoints(),
                request == null || request.opponentPoints() == null ? 0 : request.opponentPoints());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/matches/{id}/set-scores/{setNumber}")
    ResponseEntity<Void> deleteSetScore(CurrentUser user, @PathVariable UUID id,
                                        @PathVariable int setNumber) {
        service.deleteSetScore(user, id, setNumber);
        return ResponseEntity.noContent().build();
    }

    // call ups

    @GetMapping("/matches/{id}/call-ups")
    List<MatchService.CallUpView> listCallUps(CurrentUser user, @PathVariable UUID id) {
        return service.listCallUps(user, id);
    }

    @PostMapping("/matches/{id}/call-ups")
    ResponseEntity<Void> addCallUp(CurrentUser user, @PathVariable UUID id,
                                   @Valid @RequestBody CallUpRequest request) {
        service.addCallUp(user, id, request.athleteId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/matches/{id}/call-ups/{athleteId}")
    ResponseEntity<Void> removeCallUp(CurrentUser user, @PathVariable UUID id,
                                      @PathVariable UUID athleteId) {
        service.removeCallUp(user, id, athleteId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/matches/{id}/call-ups/{athleteId}")
    ResponseEntity<Void> respondCallUp(CurrentUser user, @PathVariable UUID id,
                                       @PathVariable UUID athleteId,
                                       @RequestBody(required = false) RespondRequest request) {
        return respond(user, id, athleteId, request);
    }

    @PostMapping("/matches/{id}/call-ups/{athleteId}/respond")
    ResponseEntity<Void> respondCallUpPost(CurrentUser user, @PathVariable UUID id,
                                           @PathVariable UUID athleteId,
                                           @RequestBody(required = false) RespondRequest request) {
        return respond(user, id, athleteId, request);
    }

    @PostMapping("/matches/{id}/call-ups/{athleteId}/response")
    ResponseEntity<Void> respondCallUpPost2(CurrentUser user, @PathVariable UUID id,
                                            @PathVariable UUID athleteId,
                                            @RequestBody(required = false) RespondRequest request) {
        return respond(user, id, athleteId, request);
    }

    private ResponseEntity<Void> respond(CurrentUser user, UUID matchId, UUID athleteId, RespondRequest request) {
        service.respondCallUp(user, matchId, athleteId, request == null ? null : request.response());
        return ResponseEntity.noContent().build();
    }
}
