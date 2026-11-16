package bcml.monitor.observation;

import java.util.UUID;

/** /proc/sys/kernel/random/boot_id. 바뀌면 재부팅이다. */
public record BootId(UUID value) {}
