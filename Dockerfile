FROM maven:3.9.6-amazoncorretto-17-al2023 AS builder
WORKDIR /build
COPY . /build
RUN mvn package -DskipTest=true

FROM eclipse-temurin:17.0.9_9-jre-jammy
COPY --from=builder /build/target/redis-sync*.jar .
CMD  /bin/bash -c 'JAR_FILE=$(ls *.jar) && java -jar $JAR_FILE'