package bcml.monitor.node;

/** node.id. 노드 이름(NodeName)과 섞이지 않게 따로 둔 타입. */
public record NodeId(int value) implements Comparable<NodeId> {

    @Override
    public int compareTo(NodeId other) {
        return Integer.compare(value, other.value);
    }
}
