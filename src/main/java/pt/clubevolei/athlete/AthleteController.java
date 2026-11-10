package pt.clubevolei.athlete;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API mínima só para provar o isolamento ponta a ponta. A fase 1 substitui isto por um módulo completo. */
@RestController
@RequestMapping("/api/athletes")
public class AthleteController {

    public record AthleteRequest(@NotBlank String fullName, LocalDate birthDate) {
    }

    public record AthleteResponse(UUID id, String fullName, LocalDate birthDate) {
        static AthleteResponse from(Athlete a) {
            return new AthleteResponse(a.getId(), a.getFullName(), a.getBirthDate());
        }
    }

    private final AthleteRepository repository;

    public AthleteController(AthleteRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AthleteResponse> list() {
        return repository.findAll().stream().map(AthleteResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AthleteResponse create(@Valid @RequestBody AthleteRequest request) {
        return AthleteResponse.from(repository.save(new Athlete(request.fullName(), request.birthDate())));
    }
}
