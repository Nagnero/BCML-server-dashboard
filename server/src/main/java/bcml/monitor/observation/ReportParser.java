package bcml.monitor.observation;

import bcml.monitor.node.NodeId;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 경계 검증의 유일한 장소(순수 함수). wire DTO → 도메인. 안쪽 코드는 다시 검사하지 않는다.
 */
final class ReportParser {

    static final int SCHEMA = 1;
    static final int MAX_SAMPLES = 500;          // 에이전트 배치 상한(240)보다 넉넉히
    static final int MAX_KERNEL_CHARS = 128;
    static final int MAX_DRIVER_CHARS = 64;
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private ReportParser() {}

    /** 배치 구조가 틀림(스키마 버전, 상한 초과). 400. 에이전트는 이 배치를 버리고 로컬 로그에 남긴다. */
    static final class BadReport extends RuntimeException {
        BadReport(String message) {
            super(message);
        }
    }

    static AgentReport parse(NodeId node, ReportJson body, Instant receivedAt) {
        if (body.schema() != SCHEMA) {
            throw new BadReport("unsupported schema: " + body.schema());
        }
        if (body.samples() == null) {
            throw new BadReport("samples is missing");
        }
        if (body.samples().size() > MAX_SAMPLES) {
            throw new BadReport("too many samples: " + body.samples().size());
        }
        List<Sample> samples = new ArrayList<>();
        int rejected = 0;
        for (ReportJson.SampleJson json : body.samples()) {
            Optional<Sample> sample = sample(node, json, receivedAt);
            if (sample.isPresent()) {
                samples.add(sample.get());
            } else {
                rejected++;
            }
        }
        return new AgentReport(node, receivedAt, List.copyOf(samples), rejected);
    }

    private static Optional<Sample> sample(NodeId node, ReportJson.SampleJson json, Instant receivedAt) {
        if (json == null || json.observedAt() == null || json.bootId() == null) {
            return Optional.empty();
        }
        Instant observedAt;
        UUID bootId;
        try {
            // PostgreSQL timestamptz 는 마이크로초까지다. 더 잘게 받으면 같은 표본이 다른 키로 다시 들어온다.
            observedAt = OffsetDateTime.parse(json.observedAt()).toInstant().truncatedTo(ChronoUnit.MICROS);
            bootId = UUID.fromString(json.bootId());
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean valid = !observedAt.isAfter(receivedAt.plus(MAX_FUTURE_SKEW))
                && within(json.uptimeS(), 0, Double.MAX_VALUE)
                && within(json.cpuUtilPct(), 0, 100)
                && json.kernel() != null && !json.kernel().isBlank() && json.kernel().length() <= MAX_KERNEL_CHARS
                && (json.driver() == null || json.driver().length() <= MAX_DRIVER_CHARS);
        if (!valid) {
            return Optional.empty();
        }
        Duration uptime = Duration.ofNanos(Math.round(json.uptimeS() * 1e9));
        // 초 단위로 맞춰야 같은 부팅의 표본끼리 bootedAt 이 1초 이내로 모인다.
        Instant bootedAt = observedAt.minus(uptime).plusMillis(500).truncatedTo(ChronoUnit.SECONDS);
        return Optional.of(new Sample(node, observedAt, receivedAt, new BootId(bootId), bootedAt, uptime,
                json.kernel(), json.driver(), json.cpuUtilPct().floatValue()));
    }

    /** NaN 은 어떤 비교도 통과하지 못하므로 여기서 함께 걸러진다. */
    private static boolean within(Double value, double min, double max) {
        return value != null && value >= min && value <= max;
    }
}
