# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# TLS 를 가로채는 프록시 뒤에서 빌드할 때만: 그 프록시의 CA(PEM, 여러 장 가능)를 빌드 단계의 JDK 에만 넣는다. 실행 이미지에는 안 들어간다.
RUN --mount=type=secret,id=build_extra_ca \
    if [ -s /run/secrets/build_extra_ca ]; then \
      mkdir /tmp/extra-ca && cd /tmp/extra-ca && \
      awk '/-----BEGIN CERTIFICATE-----/ { n++ } n { print > ("ca-" n ".pem") }' /run/secrets/build_extra_ca && \
      for pem in ca-*.pem; do \
        keytool -importcert -noprompt -cacerts -storepass changeit -alias "build-extra-$pem" -file "$pem" || exit 1; \
      done && \
      cd / && rm -r /tmp/extra-ca; \
    fi
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin bcml
WORKDIR /app
COPY --from=build /src/build/libs/monitor.jar monitor.jar
USER bcml
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "monitor.jar"]
