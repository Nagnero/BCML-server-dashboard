package bcml.monitor.observation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** contract/agent-report/v1 의 예제 파일. 에이전트 테스트(agent/tests)가 같은 파일을 읽는다. */
final class Contract {

    private static final Path DIR = Path.of("..", "contract", "agent-report", "v1");

    private Contract() {}

    static String read(String fileName) {
        try {
            return Files.readString(DIR.resolve(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
