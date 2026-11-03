#!/usr/bin/env bash
set -euo pipefail
cd /mnt/data/projects/incoming/mod/run/wt-MT
export JAVA_HOME=/home/artem/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
export INCOMING_SANDBOX=1 INCOMING_SCENARIO=lod-buffer INCOMING_SIZE=1280x720 INCOMING_VOXY=1 INCOMING_LSS=1 INCOMING_SHADERS=1 INCOMING_GPU_OWNER=MT-LOD
probe_run="run/mt-lod-buffer/client-$(date +%Y%m%d-%H%M%S)"
printf '%s\n' "$probe_run" > mod/run/mt-lod-buffer/client-dir.txt
mkdir -p "mod/$probe_run/config" "mod/$probe_run/shaderpacks" "mod/$probe_run/logs"
probe_mc=$(python3 tools/paths.py MC)
probe_pack=$(sed -n 's/^shaderPack=//p' "$probe_mc/config/iris.properties")
cp -a "$probe_mc/shaderpacks/$probe_pack" "mod/$probe_run/shaderpacks/"
printf 'CLOUD_STYLE_DEFINE=0\n' > "mod/$probe_run/shaderpacks/$probe_pack.txt"
cp "$probe_mc/config/voxy-config.json" "mod/$probe_run/config/voxy-config.json"
printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$probe_pack" > "mod/$probe_run/config/iris.properties"
cat > "mod/$probe_run/options.txt" <<'OPT'
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:3
simulationDistance:5
guiScale:2
soundCategory_master:0.0
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
enableVsync:false
maxFps:120
fov:0.5
OPT
cat > "mod/$probe_run/alsoft.conf" <<AUDIO
[general]
drivers = wave
[wave]
file = /mnt/data/projects/incoming/mod/run/wt-MT/mod/$probe_run/audio.wav
AUDIO
sed '/^generation:/,/^[^ ]/{s/^  enabled: true/  enabled: false/;}' mod/run/gametest/config/lss-server-config.yaml > "mod/$probe_run/config/lss-server-config.yaml"
powerprofilesctl get > "mod/$probe_run/power-profile.txt"
cd mod
./gradlew rigLaunch -PrigRun=runClientScenario -PrigOut=mt-lod-probe -PscenarioDir="$probe_run" > run/mt-lod-buffer/prepare-client.log 2>&1
