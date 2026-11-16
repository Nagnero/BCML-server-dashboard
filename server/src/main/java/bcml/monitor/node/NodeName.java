package bcml.monitor.node;

import java.util.regex.Pattern;

/** 'ws4', 'bcml-central'. 만들 때 한 번 검사하고 안에서는 믿는다. */
public record NodeName(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    public NodeName {
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid node name: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
