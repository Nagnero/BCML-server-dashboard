package bcml.monitor.observation;

import bcml.monitor.security.ApiPrincipal;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트 수신. 하는 일은 "파싱 → 사실 추가" 뿐이다. 노드 상태, 장애, 알림을 건드리지 않는다.
 * 그래서 노드들의 요청이 같은 행을 놓고 다투지 않는다(각 노드는 자기 node_id 행만 추가).
 */
@RestController
class AgentReportController {

    private final ObservationLog log;
    private final Clock clock;

    AgentReportController(ObservationLog log, Clock clock) {
        this.log = log;
        this.clock = clock;
    }

    @PostMapping(path = "/api/v1/agent/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReportJson.ReceiptJson> report(@AuthenticationPrincipal ApiPrincipal.Agent agent,
                                                  @RequestBody ReportJson body) {
        AgentReport report = ReportParser.parse(agent.node().id(), body, clock.instant());
        return ResponseEntity.accepted().body(log.append(report));
    }

    @ExceptionHandler(ReportParser.BadReport.class)
    ResponseEntity<Map<String, String>> badReport(ReportParser.BadReport e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
