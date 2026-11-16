package bcml.monitor.node;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA 매핑. package-private: 밖으로는 NodeInfo 만 나간다. */
@Entity
@Table(name = "node")
class NodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "token_sha256", nullable = false, unique = true)
    private byte[] tokenSha256;

    @Column(name = "expected_gpus", nullable = false)
    private short expectedGpus;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NodeEntity() {}

    NodeInfo toInfo() {
        return new NodeInfo(new NodeId(id), new NodeName(name), expectedGpus);
    }
}
