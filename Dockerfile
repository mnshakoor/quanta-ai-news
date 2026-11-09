FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src ./src
RUN javac -d out $(find src/main/java -name '*.java')

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY config ./config
COPY web ./web
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "out", "news.Main"]
