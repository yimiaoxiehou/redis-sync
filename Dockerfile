FROM adoptopenjdk/maven-openjdk11:latest AS builder
WORKDIR /build
COPY . /build
RUN mvn package -DskipTest=true

FROM  openjdk:11-jre
COPY --from=builder /build/target/redis-sync*.jar .
CMD  /bin/bash -c 'JAR_FILE=$(ls *.jar) && java -jar $JAR_FILE'