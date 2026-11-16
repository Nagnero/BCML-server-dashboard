package bcml.monitor.observation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * 에이전트 → 서버 전송 형식 v1 (wire DTO). package-private: 이 타입은 ReportParser 밖으로 나가지 않는다.
 *
 * <p>필드 이름의 기준은 contract/agent-report/v1/*.json 예제 파일이다. 에이전트 테스트(wire.py)와 서버 테스트
 * (ReportContractTest)가 같은 파일을 읽어서, 한쪽만 바꾸면 빌드가 깨진다.
 *
 * <p>시각·UUID 를 String 으로 받는 이유: 형식이 틀린 항목 하나가 배치 전체를 400 으로 만들면, 에이전트 버퍼에
 * 영원히 남는 독약(poison) 배치가 된다. 항목 단위로 거르고 rejected 로 센다.
 *
 * <p>여기 없는 필드(gpu_probe, kernel_lines …)는 읽지 않고 넘긴다. 필드가 더 많은 에이전트가 먼저 배포돼도 받는다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record ReportJson(int schema, String agentVersion, String hostname, List<SampleJson> samples) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SampleJson(String observedAt, String bootId, Double uptimeS, String kernel, String driver,
                      Double cpuUtilPct) {}

    /** 응답. 2xx 를 받으면 에이전트는 보낸 항목을 버퍼에서 지운다(accepted, duplicate, rejected 모두). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ReceiptJson(int acceptedSamples, int duplicateSamples, int rejected) {}
}
