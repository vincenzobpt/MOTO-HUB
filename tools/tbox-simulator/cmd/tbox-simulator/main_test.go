package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"
)

func TestDisplayGeometrySeparatesPhysicalTFTFromProjectionArea(t *testing.T) {
	cfg := config{
		displayWidth:  800,
		displayHeight: 480,
		width:         800,
		height:        384,
	}
	if err := validateDisplayGeometry(cfg); err != nil {
		t.Fatalf("validateDisplayGeometry() error = %v", err)
	}

	filter := previewVideoFilter(cfg)
	for _, expected := range []string{
		"scale=800:384:force_original_aspect_ratio=decrease",
		"pad=800:384:(ow-iw)/2:(oh-ih)/2",
		"pad=800:480:0:0",
		"drawbox=x=0:y=384:w=800:h=96",
		"drawbox=x=0:y=0:w=800:h=384",
	} {
		if !strings.Contains(filter, expected) {
			t.Fatalf("previewVideoFilter() = %q; missing %q", filter, expected)
		}
	}
}

func TestSelectTBoxProfileProvidesThirdPartyMetadata(t *testing.T) {
	profile, err := selectTBoxProfile("cfdl26-portrait")
	if err != nil {
		t.Fatalf("selectTBoxProfile() error = %v", err)
	}
	if profile.ModelID != "37426" {
		t.Fatalf("ModelID = %q, want 37426", profile.ModelID)
	}
	if profile.PackageName != "com.cfmoto.cfdashmotoplay" {
		t.Fatalf("PackageName = %q", profile.PackageName)
	}
	if !profile.SendCarDataChannel || !profile.SendCfdlNotifyBurst || !profile.RequireCarData {
		t.Fatalf("CFDL26 profile did not enable required compatibility behavior: %+v", profile)
	}
}

func TestHudConfigUsesSelectedCompatibilityProfile(t *testing.T) {
	profile, err := selectTBoxProfile("800nk-crcp")
	if err != nil {
		t.Fatalf("selectTBoxProfile() error = %v", err)
	}
	session := &session{cfg: config{profile: profile}}
	payload, err := json.Marshal(session.hudConfig())
	if err != nil {
		t.Fatalf("Marshal(hudConfig) error = %v", err)
	}
	text := string(payload)
	for _, expected := range []string{
		`"HUID":"CRCP0000000000000001"`,
		`"channel":"66660703"`,
		`"supportScreenTouch":false`,
		`"package_name":"linux_no_package"`,
	} {
		if !strings.Contains(text, expected) {
			t.Fatalf("hudConfig = %s; missing %s", text, expected)
		}
	}
}

func TestDisplayGeometryRejectsProjectionOutsidePhysicalTFT(t *testing.T) {
	err := validateDisplayGeometry(config{
		displayWidth:  800,
		displayHeight: 480,
		safeX:         100,
		width:         800,
		height:        384,
	})
	if err == nil {
		t.Fatal("validateDisplayGeometry() accepted an out-of-bounds projection area")
	}
}

func TestFullCanvasPreviewAlwaysNormalizesFrameSize(t *testing.T) {
	filter := previewVideoFilter(config{
		displayWidth:  1280,
		displayHeight: 720,
		width:         1280,
		height:        720,
	})
	for _, expected := range []string{
		"scale=1280:720:force_original_aspect_ratio=decrease",
		"pad=1280:720:(ow-iw)/2:(oh-ih)/2",
		"setsar=1",
	} {
		if !strings.Contains(filter, expected) {
			t.Fatalf("previewVideoFilter() = %q; missing %q", filter, expected)
		}
	}
}

func TestEasyConnFrameRoundTrip(t *testing.T) {
	want := []byte("{\"status\":true}\n")
	encoded := encodeEasyConnFrame(easyConnInitOK, 0x70, want)
	got, err := readEasyConnFrame(bytes.NewReader(encoded))
	if err != nil {
		t.Fatalf("readEasyConnFrame() error = %v", err)
	}
	if got.code != easyConnInitOK || got.separator != 0x70 || !bytes.Equal(got.body, want) {
		t.Fatalf("decoded frame = %+v, body=%q", got, got.body)
	}
}

func TestTouchActionAndPayloadWireValues(t *testing.T) {
	for _, test := range []struct {
		name  string
		input string
		want  int
	}{
		{name: "down", input: "down", want: 2},
		{name: "up", input: "up", want: 1},
		{name: "move", input: "move", want: 3},
	} {
		t.Run(test.name, func(t *testing.T) {
			got, err := touchAction(test.input)
			if err != nil || got != test.want {
				t.Fatalf("touchAction(%q) = %d, %v; want %d", test.input, got, err, test.want)
			}
		})
	}
}

func TestHandlebarGestureValidation(t *testing.T) {
	for _, gesture := range []string{
		"volumeUp",
		"volumeUpDouble",
		"volumeDown",
		"volumeDownDouble",
		"enter",
		"enterLong",
		"enterDouble",
		"trackBack",
		"trackBackDouble",
		"trackForward",
		"trackForwardDouble",
	} {
		got, err := normaliseHandlebarGesture(gesture)
		if err != nil || got != gesture {
			t.Fatalf("normaliseHandlebarGesture(%q) = %q, %v", gesture, got, err)
		}
	}
	if _, err := normaliseHandlebarGesture("unknown"); err == nil {
		t.Fatal("normaliseHandlebarGesture accepted an unknown gesture")
	}
}

func TestMediaTouchPayloadPreservesPointerAndCoordinates(t *testing.T) {
	payload := make([]byte, 8)
	binary.LittleEndian.PutUint16(payload[0:2], 2)
	binary.LittleEndian.PutUint16(payload[2:4], 799)
	binary.LittleEndian.PutUint16(payload[4:6], 383)
	binary.LittleEndian.PutUint16(payload[6:8], 1)

	if action := binary.LittleEndian.Uint16(payload[0:2]); action != 2 {
		t.Fatalf("action = %d, want DOWN=2", action)
	}
	if pointerID := binary.LittleEndian.Uint16(payload[6:8]); pointerID != 1 {
		t.Fatalf("pointer id = %d, want 1", pointerID)
	}
	if x := binary.LittleEndian.Uint16(payload[2:4]); x != 799 {
		t.Fatalf("x = %d, want 799", x)
	}
	if y := binary.LittleEndian.Uint16(payload[4:6]); y != 383 {
		t.Fatalf("y = %d, want 383", y)
	}
}

func TestClampKeepsTouchInsideConfiguredCanvas(t *testing.T) {
	if got := clamp(-1, 0, 799); got != 0 {
		t.Fatalf("clamp(-1) = %d, want 0", got)
	}
	if got := clamp(800, 0, 799); got != 799 {
		t.Fatalf("clamp(800) = %d, want 799", got)
	}
}

func TestSessionStopDoesNotWaitForProtocolReadLock(t *testing.T) {
	peer, socket := net.Pipe()
	defer socket.Close()
	session := &session{pxc: peer}

	session.pxcMu.Lock()
	defer session.pxcMu.Unlock()
	finished := make(chan struct{})
	go func() {
		session.stop()
		close(finished)
	}()

	select {
	case <-finished:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("session.stop() waited for the protocol mutex")
	}
}
