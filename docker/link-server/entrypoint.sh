#!/bin/bash
cd /home/container || exit 1

# 컨테이너 내부 IP (일부 플러그인/설정에서 사용)
INTERNAL_IP=$(ip route get 1 2>/dev/null | awk '{print $(NF-2);exit}')
export INTERNAL_IP
export TZ=${TZ:-UTC}

# 패널은 빈 변수도 넘긴다. 빈 LINK_* 값이 link.yml 설정을 덮어쓰지 않도록 지운다
for name in LINK_NODE_ID LINK_JDBC_URL LINK_DB_USER LINK_DB_PASSWORD LINK_REDIS_URI LINK_S3_ACCESS_KEY LINK_S3_SECRET_KEY; do
    if [ -z "${!name}" ]; then unset "$name"; fi
done

# EULA=true 이면 eula.txt 자동 작성 (Minecraft EULA 동의)
if [ "${EULA,,}" = "true" ]; then
    echo "eula=true" > eula.txt
fi

# 이미지에 포함된 서버 jar 를 서버 폴더로 동기화 (이미지 업데이트 = 서버 업데이트)
BUNDLED_JAR=${BUNDLED_JAR:-/opt/link/server.jar}
SERVER_JARFILE=${SERVER_JARFILE:-server.jar}
# jar 가 없으면 항상 복사, 있으면 AUTO_UPDATE_JAR=false 가 아닐 때만 갱신
if [ -f "$BUNDLED_JAR" ]; then
    if [ ! -f "$SERVER_JARFILE" ]; then
        echo "서버 jar 를 ${SERVER_JARFILE} 로 복사합니다"
        cp "$BUNDLED_JAR" "$SERVER_JARFILE" || echo "jar 복사 실패: $(id)"
    elif [ "${AUTO_UPDATE_JAR,,}" != "false" ] && ! cmp -s "$BUNDLED_JAR" "$SERVER_JARFILE"; then
        echo "서버 jar 를 최신 이미지 버전으로 갱신합니다"
        cp "$BUNDLED_JAR" "$SERVER_JARFILE" || echo "jar 복사 실패: $(id)"
    fi
else
    echo "이미지에 서버 jar 가 없습니다: $BUNDLED_JAR"
fi

java -version

# Pterodactyl STARTUP 변수의 {{VAR}} 를 환경변수 값으로 치환
PARSED=$(echo "${STARTUP}" | sed -e 's/{{/${/g' -e 's/}}/}/g' | eval echo "$(cat -)")

printf "\033[1m\033[33mcontainer@pterodactyl~ \033[0m%s\n" "$PARSED"

# exec 로 java 를 tini 의 직속 자식으로 만들어 시그널이 제대로 전달되게 한다
eval "exec ${PARSED}"
