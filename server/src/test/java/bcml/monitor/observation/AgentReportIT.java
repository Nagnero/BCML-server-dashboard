package bcml.monitor.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 HTTP 와 실제 PostgreSQL 로 수신 경로 전체를 본다: 토큰 → 노드, 본문 → 표본 행, 재전송 → 변화 없음. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "BCML_DB_PASSWORD=replaced-by-the-test-container")
@Testcontainers
class AgentReportIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HEARTBEAT = Contract.read("minimal-heartbeat.json");
    private static final String HEARTBEAT_OBSERVED_AT = "2026-11-15T02:10:02.031Z";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private String ws4Token;

    @BeforeEach
    void registerWs4() {
        jdbc.update("DELETE FROM sample");
        jdbc.update("DELETE FROM node");
        ws4Token = registerNode("ws4", true);
    }

    @Test
    void health_is_open_without_a_token() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(url("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("UP");
    }

    @Test
    void report_without_a_token_is_rejected_and_stores_nothing() throws Exception {
        assertThat(post(null, HEARTBEAT).statusCode()).isEqualTo(401);
        assertThat(sampleCount()).isZero();
    }

    @Test
    void report_with_an_unknown_token_is_rejected_and_stores_nothing() throws Exception {
        assertThat(post("not-a-registered-token", HEARTBEAT).statusCode()).isEqualTo(401);
        assertThat(sampleCount()).isZero();
    }

    @Test
    void token_of_a_disabled_node_is_rejected() throws Exception {
        String ws5Token = registerNode("ws5", false);

        assertThat(post(ws5Token, HEARTBEAT).statusCode()).isEqualTo(401);
        assertThat(sampleCount()).isZero();
    }

    @Test
    void other_api_paths_also_need_a_token() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(url("/api/v1/anything")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void sample_is_stored_under_the_node_of_the_token_not_the_hostname_in_the_body() throws Exception {
        assertThat(json.readTree(HEARTBEAT).path("hostname").asText()).isEqualTo("ws2");

        HttpResponse<String> response = post(ws4Token, HEARTBEAT);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree(Contract.read("receipt.json")));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT node.name, sample.boot_id::text AS boot_id, sample.uptime_s, sample.kernel,
                       to_char(sample.observed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') AS observed_at,
                       to_char(sample.booted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS booted_at,
                       sample.received_at > now() - interval '1 minute' AS received_just_now
                  FROM sample JOIN node ON node.id = sample.node_id
                """);
        assertThat(row).containsEntry("name", "ws4")
                .containsEntry("boot_id", "0b6a3f9e-5c1d-4f7a-9a52-3e8d2c7b1f40")
                .containsEntry("uptime_s", 1203344.18)
                .containsEntry("kernel", "6.8.0-48-generic")
                .containsEntry("observed_at", HEARTBEAT_OBSERVED_AT)
                .containsEntry("booted_at", "2026-11-01T03:54:18Z")
                .containsEntry("received_just_now", true);
    }

    @Test
    void same_report_twice_leaves_row_count_and_received_at_unchanged() throws Exception {
        post(ws4Token, HEARTBEAT);
        String firstReceivedAt = jdbc.queryForObject("SELECT received_at::text FROM sample", String.class);

        HttpResponse<String> second = post(ws4Token, HEARTBEAT);

        assertThat(second.statusCode()).isEqualTo(202);
        JsonNode receipt = json.readTree(second.body());
        assertThat(receipt.path("accepted_samples").asInt()).isZero();
        assertThat(receipt.path("duplicate_samples").asInt()).isEqualTo(1);
        assertThat(sampleCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT received_at::text FROM sample", String.class))
                .isEqualTo(firstReceivedAt);
    }

    @Test
    void two_nodes_reporting_the_same_instant_each_get_their_own_row() throws Exception {
        String ws5Token = registerNode("ws5", true);

        post(ws4Token, HEARTBEAT);
        post(ws5Token, HEARTBEAT);

        assertThat(jdbc.queryForList(
                "SELECT node.name FROM sample JOIN node ON node.id = sample.node_id ORDER BY node.name", String.class))
                .containsExactly("ws4", "ws5");
    }

    @Test
    void report_from_a_fuller_agent_is_accepted_with_unknown_fields_ignored() throws Exception {
        HttpResponse<String> response = post(ws4Token, Contract.read("after-reboot-batch.json"));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json.readTree(response.body()).path("accepted_samples").asInt()).isEqualTo(2);
        assertThat(sampleCount()).isEqualTo(2);
    }

    @Test
    void malformed_item_is_counted_as_rejected_and_the_rest_of_the_batch_is_stored() throws Exception {
        String body = """
                {"schema": 1, "agent_version": "0.1.0", "hostname": "ws4", "samples": [
                  {"observed_at": "yesterday", "boot_id": "%1$s", "uptime_s": 10.0, "kernel": "k", "cpu_util_pct": 1.0},
                  {"observed_at": "2026-11-15T02:10:02Z", "boot_id": "not-a-uuid", "uptime_s": 10.0, "kernel": "k", "cpu_util_pct": 1.0},
                  {"observed_at": "2026-11-15T02:10:03Z", "boot_id": "%1$s", "uptime_s": -1.0, "kernel": "k", "cpu_util_pct": 1.0},
                  {"observed_at": "2026-11-15T02:10:04Z", "boot_id": "%1$s", "uptime_s": 10.0, "kernel": "k", "cpu_util_pct": 101.0},
                  {"observed_at": "2026-11-15T02:10:05Z", "boot_id": "%1$s", "uptime_s": 10.0, "cpu_util_pct": 1.0},
                  {"observed_at": "2999-01-01T00:00:00Z", "boot_id": "%1$s", "uptime_s": 10.0, "kernel": "k", "cpu_util_pct": 1.0},
                  null,
                  {"observed_at": "2026-11-15T02:10:06Z", "boot_id": "%1$s", "uptime_s": 10.0, "kernel": "k", "cpu_util_pct": 1.0}
                ]}
                """.formatted(UUID.randomUUID());

        HttpResponse<String> response = post(ws4Token, body);

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode receipt = json.readTree(response.body());
        assertThat(receipt.path("rejected").asInt()).isEqualTo(7);
        assertThat(receipt.path("accepted_samples").asInt()).isEqualTo(1);
        assertThat(sampleCount()).isEqualTo(1);
    }

    @Test
    void unsupported_schema_version_is_a_400_for_the_whole_batch() throws Exception {
        HttpResponse<String> response = post(ws4Token, HEARTBEAT.replace("\"schema\": 1", "\"schema\": 2"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(sampleCount()).isZero();
    }

    @Test
    void body_that_is_not_json_is_a_400() throws Exception {
        assertThat(post(ws4Token, "{not json").statusCode()).isEqualTo(400);
    }

    private String registerNode(String name, boolean enabled) {
        String token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO node (name, token_sha256, expected_gpus, enabled) VALUES (?, ?, 0, ?)",
                name, sha256(token), enabled);
        return token;
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url("/api/v1/agent/report"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private int sampleCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sample", Integer.class);
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
