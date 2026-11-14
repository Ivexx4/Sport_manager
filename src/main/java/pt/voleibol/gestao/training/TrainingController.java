package pt.voleibol.gestao.training;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class TrainingController {

    record VenueRequest(@NotBlank String name, String address) {
    }

    record VenueResponse(UUID id, String name, String address, Instant createdAt, Instant updatedAt) {
        static VenueResponse from(TrainingService.VenueView v) {
            return new VenueResponse(v.id(), v.name(), v.address(), v.createdAt(), v.updatedAt());
        }
    }

    record SessionRequest(@NotNull UUID teamId, @JsonAlias({"venueId", "courtId"}) UUID courtId,
                          @NotNull @JsonAlias({"start", "startTime", "startDateTime"}) Instant startTime,
                          @NotNull @JsonAlias({"end", "endTime", "endDateTime"}) Instant endTime,
                          String recurrence, String notes, String status) {
    }

    record SessionResponse(UUID id, UUID teamId, UUID courtId, Instant startTime, Instant endTime,
                           String recurrence, String notes, String status, Instant createdAt, Instant updatedAt) {
        static SessionResponse from(TrainingService.SessionView v) {
            return new SessionResponse(v.id(), v.teamId(), v.courtId(), v.startTime(), v.endTime(),
                    v.recurrence(), v.notes(), v.status(), v.createdAt(), v.updatedAt());
        }
    }

    record AttendanceRequest(@NotNull UUID athleteId, String status, String justification) {
    }

    record AttendanceResponse(UUID athleteId, String fullName, String status, String justification) {
        static AttendanceResponse from(TrainingService.AttendanceView v) {
            return new AttendanceResponse(v.athleteId(), v.fullName(), v.status(), v.justification());
        }
    }

    private final TrainingService service;

    TrainingController(TrainingService service) {
        this.service = service;
    }

    // venues

    @GetMapping("/venues")
    List<VenueResponse> listVenues(@AuthenticationPrincipal Jwt jwt) {
        return service.listVenues(actor(jwt), roles(jwt)).stream().map(VenueResponse::from).toList();
    }

    @GetMapping("/venues/{id}")
    VenueResponse getVenue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return VenueResponse.from(service.getVenue(actor(jwt), roles(jwt), id));
    }

    @PostMapping("/venues")
    ResponseEntity<VenueResponse> createVenue(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody VenueRequest request) {
        VenueResponse created = VenueResponse.from(
                service.createVenue(actor(jwt), roles(jwt), request.name(), request.address()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/venues/{id}")
    VenueResponse updateVenue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @Valid @RequestBody VenueRequest request) {
        return VenueResponse.from(service.updateVenue(actor(jwt), roles(jwt), id, request.name(), request.address()));
    }

    @DeleteMapping("/venues/{id}")
    ResponseEntity<Void> deleteVenue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteVenue(actor(jwt), roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // training sessions

    @GetMapping("/training-sessions")
    List<SessionResponse> listSessions(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) UUID teamId) {
        return service.listSessions(actor(jwt), roles(jwt), teamId).stream().map(SessionResponse::from).toList();
    }

    @GetMapping("/training-sessions/{id}")
    SessionResponse getSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return SessionResponse.from(service.getSession(actor(jwt), roles(jwt), id));
    }

    @PostMapping("/training-sessions")
    ResponseEntity<SessionResponse> createSession(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody SessionRequest request) {
        SessionResponse created = SessionResponse.from(service.createSession(
                actor(jwt), roles(jwt), request.teamId(), request.courtId(), request.startTime(),
                request.endTime(), request.recurrence(), request.notes(), request.status()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/training-sessions/{id}")
    SessionResponse updateSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody SessionRequest request) {
        return SessionResponse.from(service.updateSession(
                actor(jwt), roles(jwt), id, request.teamId(), request.courtId(), request.startTime(),
                request.endTime(), request.recurrence(), request.notes(), request.status()));
    }

    @DeleteMapping("/training-sessions/{id}")
    ResponseEntity<Void> deleteSession(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.deleteSession(actor(jwt), roles(jwt), id);
        return ResponseEntity.noContent().build();
    }

    // attendance

    @GetMapping("/training-sessions/{id}/attendance")
    List<AttendanceResponse> listAttendance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listAttendance(actor(jwt), roles(jwt), id).stream().map(AttendanceResponse::from).toList();
    }

    @PostMapping("/training-sessions/{id}/attendance")
    ResponseEntity<Void> setAttendance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody AttendanceRequest request) {
        service.setAttendance(actor(jwt), roles(jwt), id, request.athleteId(), request.status(),
                request.justification());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/training-sessions/{id}/attendance/{athleteId}")
    ResponseEntity<Void> setAttendanceByPath(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @PathVariable UUID athleteId,
                                            @RequestBody(required = false) AttendanceRequest request) {
        String status = request == null ? null : request.status();
        String justification = request == null ? null : request.justification();
        service.setAttendance(actor(jwt), roles(jwt), id, athleteId, status, justification);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/training-sessions/{id}/attendance/{athleteId}")
    ResponseEntity<Void> removeAttendance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @PathVariable UUID athleteId) {
        service.removeAttendance(actor(jwt), roles(jwt), id, athleteId);
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