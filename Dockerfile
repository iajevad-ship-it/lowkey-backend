# syntax=docker/dockerfile:1

FROM gradle:8.14-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle installDist --no-daemon --stacktrace

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
  && apt-get install -y --no-install-recommends wget \
  && rm -rf /var/lib/apt/lists/* \
  && groupadd -r lowkey && useradd -r -g lowkey lowkey
COPY --from=build /home/gradle/src/build/install/lowkey-backend /app
USER lowkey
ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/health" || exit 1
CMD ["/app/bin/lowkey-backend"]
