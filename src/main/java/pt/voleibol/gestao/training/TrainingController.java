package pt.voleibol.gestao.training;

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
class TrainingController {

    record VenueRequest(@NotBlank String name, String address) {
    }

    record SessionRequest(@NotNull UUID teamId, @JsonAlias({"venueId", "courtId"}) UUID courtId,
                          @NotNull @JsonAlias({"start", "startTime", "startDateTime"}) Instant startTime,
                          @NotNull @JsonAlias({"end", "endTime", "endDateTime"}) Instant endTime,
                          String recurrence, String notes, String status) {
    }

    record AttendanceRequest(@NotNull UUID athleteId, String status, String justification) {
    }

    private final TrainingService service;

    TrainingController(TrainingService service) {
        this.service = service;
    }

    // venues

    @GetMapping("/venues")
    List<TrainingService.VenueView> listVenues(@AuthenticationPrincipal Jwt jwt) {
        return service.listVenues(Auth.actor(jwt), Auth.roles(jwt));
    }

    @GetMapping("/venues/{id}")
    TrainingService.VenueView getVenue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.getVenue(Auth.actor(jwt), Auth.roles(jwt), id);
    }

    @PostMapping("/venues")
    ResponseEntity<TrainingService.VenueView> createVenue(@AuthenticationPrincipal Jwt jwt,
                                                          @Valid @RequestBody VenueRequest request) {
        TrainingService.VenueView created = service.createVenue(Auth.actor(jwt), Auth.roles(jwt),
                request.name(), request.address());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/venues/{id}")
    TrainingService.VenueView updateVenue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @Valid @RequestBody VenueRequest request) {
        return service.updateVenue(Auth.actor(jwt), Auth.roles(jwt), id, request.name(), request.address());
    }

    @DeleteMapping("/venues/{id}")
    ResponseEntity<Void> deleteVenue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteVenue(Auth.actor(jwt), Auth.roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // training sessions

    @GetMapping("/training-sessions")
    List<TrainingService.SessionView> listSessions(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam(required = false) UUID teamId) {
        return service.listSessions(Auth.actor(jwt), Auth.roles(jwt), teamId);
    }

    @GetMapping("/training-sessions/{id}")
    TrainingService.SessionView getSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.getSession(Auth.actor(jwt), Auth.roles(jwt), id);
    }

    @PostMapping("/training-sessions")
    ResponseEntity<TrainingService.SessionView> createSession(@AuthenticationPrincipal Jwt jwt,
                                                              @Valid @RequestBody SessionRequest request) {
        TrainingService.SessionView created = service.createSession(Auth.actor(jwt), Auth.roles(jwt),
                request.teamId(), request.courtId(), request.startTime(), request.endTime(),
                request.recurrence(), request.notes(), request.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/training-sessions/{id}")
    TrainingService.SessionView updateSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                              @Valid @RequestBody SessionRequest request) {
        return service.updateSession(Auth.actor(jwt), Auth.roles(jwt), id, request.teamId(), request.courtId(),
                request.startTime(), request.endTime(), request.recurrence(), request.notes(), request.status());
    }

    @DeleteMapping("/training-sessions/{id}")
    ResponseEntity<Void> deleteSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteSession(Auth.actor(jwt), Auth.roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // attendance

    @GetMapping("/training-sessions/{id}/attendance")
    List<TrainingService.AttendanceView> listAttendance(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable UUID id) {
        return service.listAttendance(Auth.actor(jwt), Auth.roles(jwt), id);
    }

    @PostMapping("/training-sessions/{id}/attendance")
    ResponseEntity<Void> setAttendance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody AttendanceRequest request) {
        service.setAttendance(Auth.actor(jwt), Auth.roles(jwt), id, request.athleteId(), request.status(),
                request.justification());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/training-sessions/{id}/attendance/{athleteId}")
    ResponseEntity<Void> setAttendanceByPath(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                             @PathVariable UUID athleteId,
                                             @RequestBody(required = false) AttendanceRequest request) {
        service.setAttendance(Auth.actor(jwt), Auth.roles(jwt), id, athleteId,
                request == null ? null : request.status(),
                request == null ? null : request.justification());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/training-sessions/{id}/attendance/{athleteId}")
    ResponseEntity<Void> removeAttendance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @PathVariable UUID athleteId) {
        service.removeAttendance(Auth.actor(jwt), Auth.roles(jwt), id, athleteId);
        return ResponseEntity.noContent().build();
    }
}
