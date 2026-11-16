package bcml.monitor.security;

import bcml.monitor.node.NodeInfo;

/**
 * /api/** 요청의 주체. 에이전트의 노드 정체성은 토큰에서만 나온다(본문의 hostname 은 믿지 않는다):
 * ws3 토큰으로 ws4 의 표본을 넣을 방법이 타입상 없다.
 */
public sealed interface ApiPrincipal permits ApiPrincipal.Agent {

    /** 노드 에이전트. ROLE_AGENT. */
    record Agent(NodeInfo node) implements ApiPrincipal {}
}
