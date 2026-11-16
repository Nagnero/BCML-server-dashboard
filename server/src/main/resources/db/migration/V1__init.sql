-- V1: M2a 걷는 뼈대가 쓰는 표(node, sample)만. 정의는 설계 schema.sql 그대로이고, 나머지 표는 뒤 마이그레이션이 더한다.
-- 후보 1: 관측 기록 중심.
--
-- 테이블은 세 무리로 나뉜다. 무리마다 쓰는 모듈이 정확히 하나다.
--   1) 사실(facts)      : 에이전트/가져오기 도구가 보낸 것. 추가만 한다(append-only). 자연 키로 중복 제거.
--                          쓰는 곳: observation 패키지의 ObservationLog / SlurmImport 만.
--   2) 파생 상태(derived): 탐지기가 사실에서 만든 믿음과 장애 기록.
--                          쓰는 곳: incident 패키지의 FleetStateStore 만 (DetectorJob 의 한 트랜잭션 안).
--   3) 알림 outbox       : notify 패키지만 쓴다.
-- 수집(ingest)은 파생 상태를 절대 건드리지 않는다. 그래서 에이전트 5대의 요청과 탐지기가 같은 행을 놓고 다투지 않는다.

------------------------------------------------------------------------------------------
-- 0. 등록부 (JPA)
------------------------------------------------------------------------------------------

CREATE TABLE node (
    id             smallint    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           text        NOT NULL UNIQUE,          -- 'ws4'. Slurm NodeName 과 같은 철자를 쓴다.
    token_sha256   bytea       NOT NULL UNIQUE,          -- 에이전트 Bearer 토큰의 SHA-256. 평문은 어디에도 저장하지 않는다.
    expected_gpus  smallint    NOT NULL CHECK (expected_gpus >= 0),  -- ws2=3 이면 1장만 보일 때 ERROR 로 보인다.
    enabled        boolean     NOT NULL DEFAULT true,    -- false 면 탐지 대상(roster)에서 빠진다.
    created_at     timestamptz NOT NULL DEFAULT now()
);

------------------------------------------------------------------------------------------
-- 1. 사실 (append-only, 자연 키로 멱등)
------------------------------------------------------------------------------------------

-- 15초마다 노드당 1행. 에이전트가 같은 배치를 다시 보내도 PK 로 무시된다(ON CONFLICT DO NOTHING).
-- 중복 재전송은 received_at 을 바꾸지 않는다 → "새 표본이 와야만 신호로 친다"가 저장 구조로 보장된다.
CREATE TABLE sample (
    node_id          smallint         NOT NULL REFERENCES node (id),
    observed_at      timestamptz      NOT NULL,   -- 에이전트 시계(NTP 동기화). 타임라인/지표 구간에 쓴다.
    received_at      timestamptz      NOT NULL,   -- 서버 시계. 무신호 판정은 이 값만 쓴다.
    boot_id          uuid             NOT NULL,
    booted_at        timestamptz      NOT NULL,   -- observed_at - uptime. 수집 경계에서 한 번 계산. 여러 노드 동시 재부팅(전원) 비교용.
    uptime_s         double precision NOT NULL CHECK (uptime_s >= 0),
    kernel           text             NOT NULL,   -- uname -r
    driver           text,                        -- /proc/driver/nvidia/version 의 커널 모듈 버전. 모듈이 없으면 NULL.
    cpu_util_pct     real             NOT NULL CHECK (cpu_util_pct BETWEEN 0 AND 100),
    gpu_probe_ok     boolean          NOT NULL,   -- nvidia-smi 성공 여부. 실패도 하나의 증상(사실)이다.
    gpu_probe_error  text,                        -- 실패 시 stderr 앞부분(잘라서 저장)
    gpu_count        smallint         NOT NULL,   -- 성공 시 보인 GPU 수, 실패 시 0
    PRIMARY KEY (node_id, observed_at),
    CHECK (gpu_probe_ok = (gpu_probe_error IS NULL)),
    CHECK (gpu_probe_ok OR gpu_count = 0)
);
-- 접근 패턴
--  (a) 노드별 최신 표본 (탐지 틱마다 + 현재 상태 화면):
--      node 마다 LATERAL (... WHERE node_id = $1 ORDER BY observed_at DESC LIMIT 1) → PK 역방향 스캔 1회씩. 노드 5대.
--  (b) 장애 직전 지표 구간: WHERE node_id = $1 AND observed_at BETWEEN $2 AND $3 → PK 범위 스캔.
--  (c) 보관 삭제: WHERE observed_at < $cutoff → 시간 순으로 쌓이므로 BRIN 이 작고 충분하다.
CREATE INDEX sample_observed_at_brin ON sample USING brin (observed_at);
CREATE INDEX sample_latest_received ON sample (node_id, received_at DESC, observed_at DESC);  -- 틱마다 노드별 최신 표본
