FROM openjdk:11

RUN apt update && apt upgrade -y && apt install maven -y

WORKDIR /build

EXPOSE 8000

CMD mvn clean spring-boot:run
