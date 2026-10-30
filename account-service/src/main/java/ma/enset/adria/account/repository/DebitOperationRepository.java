package ma.enset.adria.account.repository;

import ma.enset.adria.account.domain.DebitOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DebitOperationRepository extends JpaRepository<DebitOperation, UUID> { }
