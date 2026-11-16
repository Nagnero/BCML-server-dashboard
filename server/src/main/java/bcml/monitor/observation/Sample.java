package bcml.monitor.observation;

import bcml.monitor.node.NodeId;
import java.time.Duration;
import java.time.Instant;

/**
 * 15초 표본 하나(사실). 쓰기는 ObservationLog 만 한다.
 *
 * @param observedAt 에이전트 시계(NTP). 지표 구간과 타임라인.
 * @param receivedAt 서버 시계. 무신호 판정은 이 값만 쓴다. 중복 재전송은 이 값을 바꾸지 않는다(PK 충돌 시 무시).
 * @param bootedAt   observedAt - uptime. ReportParser 에서 한 번만 계산한다.
 * @param driver     /proc/driver/nvidia/version 의 커널 모듈 버전. 모듈 없으면 null.
 */
public record Sample(
        NodeId node,
        Instant observedAt,
        Instant receivedAt,
        BootId boot,
        Instant bootedAt,
        Duration uptime,
        String kernel,
        String driver,
        float cpuUtilPct) {}
