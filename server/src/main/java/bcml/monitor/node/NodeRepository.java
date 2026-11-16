package bcml.monitor.node;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** package-private. 다른 패키지는 NodeRegistry 로만 노드를 읽는다. */
interface NodeRepository extends JpaRepository<NodeEntity, Short> {

    Optional<NodeEntity> findByTokenSha256AndEnabledTrue(byte[] tokenSha256);
}
