package bcml.monitor.node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 노드 등록부의 유일한 공개 입구. JPA(NodeEntity, NodeRepository)는 package-private 라 밖에서 못 쓴다.
 *
 * <p>노드 추가/토큰 교체는 화면 없이 운영 절차로 한다: tools/new_node_token.py 가 토큰을 만들고 SHA-256 을 담은
 * INSERT 문을 출력한다. 토큰 평문은 노드의 토큰 파일에만 둔다.
 */
@Service
public class NodeRegistry {

    private final NodeRepository repository;

    NodeRegistry(NodeRepository repository) {
        this.repository = repository;
    }

    /** Bearer 토큰 → 노드. 보안 필터만 부른다. 등록되지 않았거나 enabled=false 면 empty. */
    public Optional<NodeInfo> authenticate(String bearerToken) {
        return repository.findByTokenSha256AndEnabledTrue(sha256(bearerToken)).map(NodeEntity::toInfo);
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
