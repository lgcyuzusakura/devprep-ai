FROM node:22-alpine AS frontend
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build/backend-java
COPY backend-java/pom.xml ./
COPY backend-java/src ./src
COPY data/seed /build/data/seed
COPY --from=frontend /build/dist /build/dist
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app && mkdir -p /data && chown app:app /data
WORKDIR /app
COPY --from=backend /build/backend-java/target/devprep-backend-0.2.0-SNAPSHOT.jar ./app.jar
USER 10001:10001
ENV DEVPREP_HOST=0.0.0.0 DEVPREP_PORT=8765 DEVPREP_DATA_DIR=/data DEVPREP_ROOT=/app
EXPOSE 8765
HEALTHCHECK --interval=15s --timeout=4s --start-period=40s CMD wget -q -O /dev/null http://127.0.0.1:8765/api/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
