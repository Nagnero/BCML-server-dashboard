package bcml.monitor.observation;

import bcml.monitor.node.NodeId;
import java.time.Instant;
import java.util.List;

/**
 * 경계를 통과한 보고 한 건(도메인 타입). 여기까지 오면 모든 항목이 검증됐다.
 * node 는 토큰에서 왔고, 모든 Sample 의 node 와 같다(ReportParser 가 보장).
 */
record AgentReport(NodeId node, Instant receivedAt, List<Sample> samples, int rejected) {}
