# syntax=docker/dockerfile:1

FROM gradle:8.14.3-jdk21-alpine AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget \
  && addgroup -S lowkey && adduser -S lowkey -G lowkey
COPY --from=build /home/gradle/src/build/install/lowkey-backend /app
USER lowkey
ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/health" || exit 1
CMD ["/app/bin/lowkey-backend"]
