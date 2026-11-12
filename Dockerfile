FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY intentguard-app/frontend/package.json intentguard-app/frontend/package-lock.json ./
RUN npm ci
COPY intentguard-app/frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /app/backend
COPY intentguard-app/backend/ ./
RUN bash ./build.sh jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend-build /app/backend/intentguard.jar ./intentguard.jar
COPY --from=frontend-build /app/frontend/dist ./frontend

ENV PORT=8080 \
    FRONTEND_DIR=/app/frontend

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/intentguard.jar"]
