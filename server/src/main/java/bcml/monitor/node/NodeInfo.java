package bcml.monitor.node;

/** 등록부의 한 노드. 토큰 해시는 여기 없다: 인증은 NodeRegistry.authenticate 안에서 끝난다. */
public record NodeInfo(NodeId id, NodeName name, int expectedGpus) {}
