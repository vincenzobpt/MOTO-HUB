#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/MOTO-HUB-TBox-Simulator.app"
CORE="$ROOT/.build/tbox-simulator-core"
ICON="$ROOT/MOTO-HUB-TBox-Simulator.icns"

cd "$ROOT"
go build -o "$CORE" ./cmd/tbox-simulator
swift build --package-path macos-app -c release

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$CORE" "$APP/Contents/MacOS/tbox-simulator-core"
cp macos-app/.build/release/MOTO-HUB-TBox-Simulator "$APP/Contents/MacOS/MOTO-HUB-TBox-Simulator"
if [ -f "$ICON" ]; then
    cp "$ICON" "$APP/Contents/Resources/MOTO-HUB-TBox-Simulator.icns"
fi
chmod +x "$APP/Contents/MacOS/"*

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleExecutable</key>
	<string>MOTO-HUB-TBox-Simulator</string>
	<key>CFBundleIconFile</key>
	<string>MOTO-HUB-TBox-Simulator</string>
	<key>CFBundleIdentifier</key>
	<string>io.motohub.tbox-simulator</string>
	<key>CFBundleName</key>
	<string>MOTO-HUB T-Box Simulator</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleVersion</key>
	<string>0.1.0</string>
	<key>CFBundleShortVersionString</key>
	<string>0.1.0</string>
	<key>LSMinimumSystemVersion</key>
	<string>13.0</string>
	<key>NSLocalNetworkUsageDescription</key>
	<string>Il simulatore emula il T-Box sulla rete locale per testare MOTO-HUB.</string>
</dict>
</plist>
PLIST

echo "Built $APP"
