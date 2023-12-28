FROM adoptopenjdk/maven-openjdk11:latest AS builder
WORKDIR /build
COPY . /build
RUN mvn package -DskipTest=true

FROM  openjdk:11-jre
COPY --from=builder /build/redis-sync*.jar .
CMD ["java", "-jar", "redis-sync*.jar"]