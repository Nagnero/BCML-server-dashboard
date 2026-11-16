package bcml.monitor;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 하나의 Spring Boot 앱. 지금은 에이전트 수신 API 와 health 뿐이다.
 *
 * <p>화면 로그인(app_user)이 아직 없으므로 Spring Boot 가 임시 사용자와 비밀번호를 만들어 로그에 찍지 않게 뺀다.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }

    /** 시각은 이 Bean 으로만 얻는다. 순수 로직은 시각을 인자로 받는다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
