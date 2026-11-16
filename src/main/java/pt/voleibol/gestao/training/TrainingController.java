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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    List<TrainingService.VenueView> listVenues(CurrentUser user) {
        return service.listVenues(user);
    }

    @GetMapping("/venues/{id}")
    TrainingService.VenueView getVenue(CurrentUser user, @PathVariable UUID id) {
        return service.getVenue(user, id);
    }

    @PostMapping("/venues")
    ResponseEntity<TrainingService.VenueView> createVenue(CurrentUser user,
                                                          @Valid @RequestBody VenueRequest request) {
        TrainingService.VenueView created = service.createVenue(user, request.name(), request.address());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/venues/{id}")
    TrainingService.VenueView updateVenue(CurrentUser user, @PathVariable UUID id,
                                          @Valid @RequestBody VenueRequest request) {
        return service.updateVenue(user, id, request.name(), request.address());
    }

    @DeleteMapping("/venues/{id}")
    ResponseEntity<Void> deleteVenue(CurrentUser user, @PathVariable UUID id) {
        service.deleteVenue(user, id);
        return ResponseEntity.noContent().build();
    }

    // training sessions

    @GetMapping("/training-sessions")
    List<TrainingService.SessionView> listSessions(CurrentUser user,
                                                   @RequestParam(required = false) UUID teamId) {
        return service.listSessions(user, teamId);
    }

    @GetMapping("/training-sessions/{id}")
    TrainingService.SessionView getSession(CurrentUser user, @PathVariable UUID id) {
        return service.getSession(user, id);
    }

    @PostMapping("/training-sessions")
    ResponseEntity<TrainingService.SessionView> createSession(CurrentUser user,
                                                              @Valid @RequestBody SessionRequest request) {
        TrainingService.SessionView created = service.createSession(user,
                request.teamId(), request.courtId(), request.startTime(), request.endTime(),
                request.recurrence(), request.notes(), request.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/training-sessions/{id}")
    TrainingService.SessionView updateSession(CurrentUser user, @PathVariable UUID id,
                                              @Valid @RequestBody SessionRequest request) {
        return service.updateSession(user, id, request.teamId(), request.courtId(),
                request.startTime(), request.endTime(), request.recurrence(), request.notes(), request.status());
    }

    @DeleteMapping("/training-sessions/{id}")
    ResponseEntity<Void> deleteSession(CurrentUser user, @PathVariable UUID id) {
        service.deleteSession(user, id);
        return ResponseEntity.noContent().build();
    }

    // attendance

    @GetMapping("/training-sessions/{id}/attendance")
    List<TrainingService.AttendanceView> listAttendance(CurrentUser user, @PathVariable UUID id) {
        return service.listAttendance(user, id);
    }

    @PostMapping("/training-sessions/{id}/attendance")
    ResponseEntity<Void> setAttendance(CurrentUser user, @PathVariable UUID id,
                                       @Valid @RequestBody AttendanceRequest request) {
        service.setAttendance(user, id, request.athleteId(), request.status(), request.justification());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/training-sessions/{id}/attendance/{athleteId}")
    ResponseEntity<Void> setAttendanceByPath(CurrentUser user, @PathVariable UUID id,
                                             @PathVariable UUID athleteId,
                                             @RequestBody(required = false) AttendanceRequest request) {
        service.setAttendance(user, id, athleteId,
                request == null ? null : request.status(),
                request == null ? null : request.justification());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/training-sessions/{id}/attendance/{athleteId}")
    ResponseEntity<Void> removeAttendance(CurrentUser user, @PathVariable UUID id,
                                          @PathVariable UUID athleteId) {
        service.removeAttendance(user, id, athleteId);
        return ResponseEntity.noContent().build();
    }
}
