# Pterodactyl Link (Paper 계열 Bukkit) 서버 이미지
# 빌드 예: CI(.github/workflows/link-package.yml)가 build/dist/Link-26.3-dev.jar 를 docker/link-server/server.jar 로 복사한 뒤 빌드한다
ARG JAVA_VERSION=25
FROM eclipse-temurin:${JAVA_VERSION}-jdk-noble

LABEL org.opencontainers.image.description="Pterodactyl Link Minecraft server (26.3)"
LABEL org.opencontainers.image.licenses=MIT

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      curl ca-certificates openssl git tar unzip jq sqlite3 \
      fontconfig libfreetype6 tzdata iproute2 tini \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -m -d /home/container -s /bin/bash container \
 && mkdir -p /opt/link && chmod 755 /opt/link

COPY --chmod=755 entrypoint.sh /entrypoint.sh
# 서버 jar 는 이미지에 포함되고, 시작 시 /home/container 로 복사된다
COPY --chmod=644 server.jar /opt/link/server.jar

USER container
ENV USER=container HOME=/home/container
WORKDIR /home/container

# Pterodactyl은 SIGINT/stop 명령으로 서버를 종료한다
STOPSIGNAL SIGINT

ENTRYPOINT ["/usr/bin/tini", "-g", "--"]
CMD ["/entrypoint.sh"]
