#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
LOG="/Users/slavafggh/RiderProjects/SpeedyNote/android/build-apk.log"
exec > >(tee -a "$LOG") 2>&1
echo "=== SpeedyNote APK build started $(date) ==="

# Wait for Docker engine
for i in $(seq 1 60); do
  if docker info >/dev/null 2>&1; then
    echo "Docker engine ready"
    break
  fi
  if [ "$i" -eq 1 ]; then
    echo "Starting Docker Desktop..."
    open -a Docker 2>/dev/null || \
      open "/Applications/Docker.app/Contents/MacOS/Docker Desktop.app" 2>/dev/null || \
      /Applications/Docker.app/Contents/MacOS/com.docker.backend >/tmp/docker-backend-user.log 2>&1 &
  fi
  echo "Waiting for Docker... ($i)"
  sleep 5
  if [ "$i" -eq 60 ]; then
    echo "ERROR: Docker did not become ready. Open Docker Desktop manually and re-run."
    exit 1
  fi
done

IMAGE=speedynote-android:latest
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "=== Building Docker image (10-20 min first time) ==="
  ./android/docker-build.sh
fi

run_in() {
  docker run --rm \
    --platform=linux/amd64 \
    -v "$(pwd):/workspace" \
    -v "${HOME}/.gradle:/root/.gradle" \
    -w /workspace \
    "$IMAGE" \
    bash -lc "$1"
}

if [ ! -f android/mupdf-build/arm64-v8a/lib/libmupdf.a ] && \
   [ ! -f android/mupdf-build/lib/libmupdf.a ]; then
  echo "=== Building MuPDF ==="
  run_in "./android/build-mupdf.sh"
fi

echo "=== Building SpeedyNote APK (arm64) ==="
run_in "./android/build-speedynote.sh --apk --arm64"

if [ -f android/SpeedyNote.apk ]; then
  ls -lh android/SpeedyNote.apk
  echo "=== BUILD SUCCESS: android/SpeedyNote.apk ==="
  echo BUILD_SUCCESS > android/build-apk.status
else
  echo "=== BUILD FAILED: APK not found ==="
  echo BUILD_FAILED > android/build-apk.status
  exit 1
fi
