package bcml.monitor.observation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사실을 쓰는 유일한 곳. package-private: 같은 패키지의 수신 컨트롤러만 부를 수 있다.
 * 모든 쓰기는 자연 키 기준 멱등이다. 응답이 유실돼 에이전트가 같은 배치를 다시 보내도 결과가 같다.
 */
@Repository
class ObservationLog {

    // gpu_probe 는 아직 읽지 않는다. "성공, GPU 0장"으로 적으면 거짓 사실이 되므로 "확인 못 함"으로 적는다.
    private static final String GPU_PROBE_NOT_READ = "gpu_probe not read by this server version";

    private static final String INSERT_SAMPLE = """
            INSERT INTO sample (node_id, observed_at, received_at, boot_id, booted_at, uptime_s, kernel, driver,
                                cpu_util_pct, gpu_probe_ok, gpu_probe_error, gpu_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, 0)
            ON CONFLICT (node_id, observed_at) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    ObservationLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    ReportJson.ReceiptJson append(AgentReport report) {
        List<Object[]> rows = report.samples().stream()
                .map(s -> new Object[] {
                    s.node().value(), utc(s.observedAt()), utc(s.receivedAt()), s.boot().value(), utc(s.bootedAt()),
                    s.uptime().toNanos() / 1e9, s.kernel(), s.driver(), s.cpuUtilPct(), GPU_PROBE_NOT_READ
                })
                .toList();
        int accepted = 0;
        for (int inserted : jdbc.batchUpdate(INSERT_SAMPLE, rows)) {
            accepted += inserted;
        }
        return new ReportJson.ReceiptJson(accepted, rows.size() - accepted, report.rejected());
    }

    /** PostgreSQL JDBC 드라이버는 Instant 를 직접 받지 않는다. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
