package pt.clubevolei.athlete;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AthleteRepository extends JpaRepository<Athlete, UUID> {
}
