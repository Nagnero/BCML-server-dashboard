package bcml.monitor.observation;

import static org.assertj.core.api.Assertions.assertThat;

import bcml.monitor.node.NodeId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** 전송 형식 v1 의 필드 이름을 contract 예제 파일에 묶는다. Spring 이 실제로 쓰는 ObjectMapper 설정으로 읽는다. */
@JsonTest
class ReportContractTest {

    private static final NodeId NODE = new NodeId(4);
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-15T02:11:00Z");

    @Autowired
    private ObjectMapper json;

    @Test
    void minimal_heartbeat_becomes_one_sample_with_every_field_read() throws Exception {
        ReportJson body = json.readValue(Contract.read("minimal-heartbeat.json"), ReportJson.class);

        AgentReport report = ReportParser.parse(NODE, body, RECEIVED_AT);

        assertThat(report.rejected()).isZero();
        assertThat(report.samples()).containsExactly(new Sample(
                NODE,
                Instant.parse("2026-11-15T02:10:02.031Z"),
                RECEIVED_AT,
                new BootId(UUID.fromString("0b6a3f9e-5c1d-4f7a-9a52-3e8d2c7b1f40")),
                Instant.parse("2026-11-01T03:54:18Z"),
                Duration.ofMillis(1_203_344_180L),
                "6.8.0-48-generic",
                null,
                41.7f));
    }

    @Test
    void fields_this_server_does_not_read_yet_are_ignored() throws Exception {
        ReportJson body = json.readValue(Contract.read("after-reboot-batch.json"), ReportJson.class);

        AgentReport report = ReportParser.parse(NODE, body, RECEIVED_AT);

        assertThat(report.rejected()).isZero();
        assertThat(report.samples()).extracting(Sample::driver).containsExactly("580.126.16", "580.126.16");
    }

    @Test
    void receipt_has_exactly_the_contract_fields() throws Exception {
        String written = json.writeValueAsString(new ReportJson.ReceiptJson(1, 0, 0));

        assertThat(json.readTree(written)).isEqualTo(json.readTree(Contract.read("receipt.json")));
    }
}
