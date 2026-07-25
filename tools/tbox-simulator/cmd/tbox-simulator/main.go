package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/grandcat/zeroconf"
)

const (
	serviceType        = "_EasyConn._tcp"
	servicePkg         = "com.cfmoto.cfmotointernational"
	defaultServiceName = "MOTO-HUB T-Box Simulator"

	easyConnInit   = 16
	easyConnInitOK = 17

	pxcHandshake    = 65536
	pxcHandshakeOK  = 65537
	pxcCarData      = 131072
	pxcCarDataOK    = 131073
	pxcHudConfig    = 65552
	pxcPhoneConfig  = 65553
	pxcLogReport    = 0x10780
	pxcOtaFtpInfo   = 0x103a0
	pxcMediaFeature = 0x10020
	pxcSpeedConfig  = 67216
	pxcSpeedOK      = 67217
	pxcClientSet    = 66528
	pxcClientSetACK = 66529
	pxcCheckSN      = 0x201c0
	pxcCheckSNDone  = pxcCheckSN + 1
	pxcHeartbeat    = 1879048192
	pxcHeartbeatOK  = 1879048193

	mediaInit      = 16
	mediaInitACK   = 17
	mediaScreen    = 96
	mediaScreenACK = 97
	mediaPing      = 64
	mediaPong      = 65
	mediaStart     = 112
	mediaStartACK  = 113
	mediaTouch     = 32

	streamPoll              = 0x72
	appHandlebarControlPort = 42081
)

type config struct {
	ecPort        int
	controlPort   int
	profileName   string
	displayWidth  int
	displayHeight int
	safeX         int
	safeY         int
	width         int
	height        int
	heartbeat     time.Duration
	noHeartbeat   bool
	player        string
	videoDump     string
	serviceName   string
	advertisedIP  string
	profile       tboxProfile
}

type tboxProfile struct {
	Name                 string
	Description          string
	ServiceName          string
	PackageName          string
	ModelID              string
	SN                   string
	HUID                 string
	HUName               string
	CarModel             string
	Channel              string
	VersionName          string
	SDKVersion           string
	Flavor               string
	DPI                  int
	SupportFunction      int
	SupportConnect       int
	SupportScreenTouch   bool
	SupportSockAuth      bool
	SendCarDataChannel   bool
	SendCfdlNotifyBurst  bool
	RequireCarData       bool
	HeartbeatOnCarData   bool
	PairingName          string
	DefaultDisplayWidth  int
	DefaultDisplayHeight int
	DefaultSafeX         int
	DefaultSafeY         int
	DefaultSafeWidth     int
	DefaultSafeHeight    int
}

type simulator struct {
	cfg    config
	logger *log.Logger

	ecListener net.Listener
	mdns       *zeroconf.Server
	control    *http.Server
	controlLn  net.Listener

	mu       sync.RWMutex
	session  *session
	stopping bool
}

type session struct {
	phoneIP  string
	log      *log.Logger
	cfg      config
	onStop   func(*session)
	stopOnce sync.Once

	pxcMu   sync.Mutex
	pxc     net.Conn
	pxcData net.Conn
	mediaMu sync.Mutex
	media   net.Conn
	stream  net.Conn
	player  *exec.Cmd
	videoIn io.WriteCloser

	frames uint64
}

type touchRequest struct {
	Action    string `json:"action"`
	PointerID int    `json:"pointerId"`
	X         int    `json:"x"`
	Y         int    `json:"y"`
}

type handlebarRequest struct {
	Gesture string `json:"gesture"`
}

type statusResponse struct {
	Running       bool   `json:"running"`
	PhoneIP       string `json:"phoneIp,omitempty"`
	Frames        uint64 `json:"frames"`
	Profile       string `json:"profile"`
	ModelID       string `json:"modelId"`
	DisplayWidth  int    `json:"displayWidth"`
	DisplayHeight int    `json:"displayHeight"`
	SafeX         int    `json:"safeX"`
	SafeY         int    `json:"safeY"`
	Width         int    `json:"width"`
	Height        int    `json:"height"`
	Heartbeat     string `json:"heartbeat"`
	VideoPlayer   bool   `json:"videoPlayer"`
}

var tboxProfiles = map[string]tboxProfile{
	"motohub": {
		Name:                 "MOTO-HUB Simulator",
		Description:          "Development profile optimized for MOTO-HUB.",
		ServiceName:          defaultServiceName,
		PackageName:          servicePkg,
		ModelID:              "MOTO-HUB-SIMULATOR",
		SN:                   "MOTO-HUB-SIM",
		HUID:                 "MOTO-HUB-TBOX-SIMULATOR",
		HUName:               "MOTO-HUB Simulator",
		CarModel:             "T-Box Simulator",
		Channel:              "MOTO-HUB-SIMULATOR",
		VersionName:          "MOTO-HUB.SIM.1.0",
		SDKVersion:           "1.0.2",
		Flavor:               "simulator",
		DPI:                  160,
		SupportFunction:      128,
		SupportConnect:       776,
		SupportScreenTouch:   true,
		SendCarDataChannel:   true,
		HeartbeatOnCarData:   true,
		DefaultDisplayWidth:  800,
		DefaultDisplayHeight: 480,
		DefaultSafeWidth:     800,
		DefaultSafeHeight:    384,
		PairingName:          "MOTO-HUB Simulator",
	},
	"cfdl16": {
		Name:                 "CFDL16 Legacy",
		Description:          "Legacy CFMOTO EasyConn landscape profile.",
		ServiceName:          "CFDL16-6GUV",
		PackageName:          servicePkg,
		ModelID:              "37416",
		SN:                   "peTz",
		HUID:                 "6GUVA2C00100055",
		HUName:               "CFDL16-6GUV",
		CarModel:             "CFDL16",
		Channel:              "37416",
		VersionName:          "CFDL16.6.10",
		SDKVersion:           "0.9.29.1",
		Flavor:               "CFDL16",
		DPI:                  160,
		SupportFunction:      0,
		SupportConnect:       9,
		SupportScreenTouch:   false,
		SendCarDataChannel:   true,
		RequireCarData:       true,
		DefaultDisplayWidth:  800,
		DefaultDisplayHeight: 480,
		DefaultSafeWidth:     800,
		DefaultSafeHeight:    386,
		PairingName:          "CFDL16-6GUV",
	},
	"cfdl26-portrait": {
		Name:                 "CFDL26 Portrait",
		Description:          "CFDL26 / MotoPlay portrait profile similar to 1000 MT-X.",
		ServiceName:          "CFMOTO-805120",
		PackageName:          "com.cfmoto.cfdashmotoplay",
		ModelID:              "37426",
		SN:                   "0rLs",
		HUID:                 "6WX0AT231300200",
		HUName:               "CFMOTO-805120",
		CarModel:             "1000 MT-X",
		Channel:              "37426",
		VersionName:          "CFDL26.2.3.5.0.6",
		SDKVersion:           "1.1.4",
		Flavor:               "CFDL26",
		DPI:                  240,
		SupportFunction:      128,
		SupportConnect:       776,
		SupportScreenTouch:   true,
		SupportSockAuth:      true,
		SendCarDataChannel:   true,
		SendCfdlNotifyBurst:  true,
		RequireCarData:       true,
		HeartbeatOnCarData:   true,
		DefaultDisplayWidth:  800,
		DefaultDisplayHeight: 951,
		DefaultSafeWidth:     800,
		DefaultSafeHeight:    951,
		PairingName:          "CFMOTO-805120",
	},
	"cfdl26-landscape": {
		Name:                 "CFDL26 Landscape",
		Description:          "CFDL26 / MotoPlay landscape profile similar to 800MT.",
		ServiceName:          "CFMOTO1565",
		PackageName:          "com.cfmoto.easyconnect",
		ModelID:              "37426",
		SN:                   "0rLs",
		HUID:                 "6WX0AT231300201",
		HUName:               "CFMOTO1565",
		CarModel:             "800MT",
		Channel:              "37426",
		VersionName:          "CFDL26.2.3.0.5",
		SDKVersion:           "1.1.2",
		Flavor:               "CFDL26",
		DPI:                  160,
		SupportFunction:      128,
		SupportConnect:       776,
		SupportScreenTouch:   true,
		SupportSockAuth:      true,
		SendCarDataChannel:   true,
		SendCfdlNotifyBurst:  true,
		RequireCarData:       true,
		HeartbeatOnCarData:   true,
		DefaultDisplayWidth:  800,
		DefaultDisplayHeight: 480,
		DefaultSafeWidth:     800,
		DefaultSafeHeight:    480,
		PairingName:          "CFMOTO1565",
	},
	"800nk-crcp": {
		Name:                 "800NK CRCP",
		Description:          "800NK CRCP/sdk 0.9.23.x non-touch profile.",
		ServiceName:          "CFMOTO-800NK",
		PackageName:          "linux_no_package",
		ModelID:              "66660703",
		SN:                   "800NK",
		HUID:                 "CRCP0000000000000001",
		HUName:               "CFMOTO-800NK",
		CarModel:             "800NK",
		Channel:              "66660703",
		VersionName:          "CFDL16.6.10",
		SDKVersion:           "0.9.23.4",
		Flavor:               "CRCP",
		DPI:                  160,
		SupportFunction:      0,
		SupportConnect:       776,
		SupportScreenTouch:   false,
		SendCarDataChannel:   true,
		SendCfdlNotifyBurst:  true,
		RequireCarData:       true,
		HeartbeatOnCarData:   true,
		DefaultDisplayWidth:  800,
		DefaultDisplayHeight: 480,
		DefaultSafeWidth:     800,
		DefaultSafeHeight:    400,
		PairingName:          "CFMOTO-800NK",
	},
	"800nk-touch": {
		Name:                 "800NK Touch",
		Description:          "800NK touch profile with 720 x 712 measured app area.",
		ServiceName:          "CFMOTO-800NK-TOUCH",
		PackageName:          "com.cfmoto.easyconnect",
		ModelID:              "37426",
		SN:                   "800NKT",
		HUID:                 "6KWV000000000000001",
		HUName:               "CFMOTO-800NK",
		CarModel:             "800NK",
		Channel:              "37426",
		VersionName:          "CFDL26.2.3.0.5",
		SDKVersion:           "1.1.2",
		Flavor:               "CFDL26",
		DPI:                  160,
		SupportFunction:      128,
		SupportConnect:       776,
		SupportScreenTouch:   true,
		SupportSockAuth:      true,
		SendCarDataChannel:   true,
		SendCfdlNotifyBurst:  true,
		RequireCarData:       true,
		HeartbeatOnCarData:   true,
		DefaultDisplayWidth:  720,
		DefaultDisplayHeight: 712,
		DefaultSafeWidth:     720,
		DefaultSafeHeight:    712,
		PairingName:          "CFMOTO-800NK",
	},
	"66660742": {
		Name:                 "CFDL16 66660742",
		Description:          "CFDL16-class MotoPlay landscape profile for modelId 66660742.",
		ServiceName:          "CFMOTO-66660742",
		PackageName:          "com.cfmoto.easyconnect",
		ModelID:              "66660742",
		SN:                   "60742",
		HUID:                 "6GUV666607420000001",
		HUName:               "CFMOTO-60742",
		CarModel:             "CFDL16 MotoPlay",
		Channel:              "66660742",
		VersionName:          "CFDL16.6.10",
		SDKVersion:           "0.9.23.4",
		Flavor:               "CFDL16",
		DPI:                  160,
		SupportFunction:      128,
		SupportConnect:       776,
		SupportScreenTouch:   false,
		SendCarDataChannel:   true,
		SendCfdlNotifyBurst:  true,
		RequireCarData:       true,
		HeartbeatOnCarData:   true,
		DefaultDisplayWidth:  800,
		DefaultDisplayHeight: 480,
		DefaultSafeWidth:     800,
		DefaultSafeHeight:    384,
		PairingName:          "CFMOTO-60742",
	},
}

func selectTBoxProfile(name string) (tboxProfile, error) {
	key := strings.ToLower(strings.TrimSpace(name))
	if key == "" {
		key = "motohub"
	}
	profile, ok := tboxProfiles[key]
	if !ok {
		names := make([]string, 0, len(tboxProfiles))
		for name := range tboxProfiles {
			names = append(names, name)
		}
		return tboxProfile{}, fmt.Errorf("unknown profile %q; valid profiles: %s", name, strings.Join(names, ", "))
	}
	return profile, nil
}

func main() {
	cfg := config{}
	flag.IntVar(&cfg.ecPort, "ec-port", 0, "EasyConn discovery/init TCP port; 0 chooses a free port")
	flag.IntVar(&cfg.controlPort, "control-port", 8765, "local HTTP control port; 0 chooses a free port")
	flag.StringVar(&cfg.profileName, "profile", "motohub", "T-Box compatibility profile: motohub, cfdl16, cfdl26-portrait, cfdl26-landscape, 800nk-crcp, 800nk-touch, 66660742")
	flag.IntVar(&cfg.displayWidth, "display-width", 0, "physical TFT width; 0 uses the projection width")
	flag.IntVar(&cfg.displayHeight, "display-height", 0, "physical TFT height; 0 uses the projection height")
	flag.IntVar(&cfg.safeX, "safe-x", 0, "projection-area X offset inside the physical TFT preview")
	flag.IntVar(&cfg.safeY, "safe-y", 0, "projection-area Y offset inside the physical TFT preview")
	flag.IntVar(&cfg.width, "width", 800, "projection-area width reported to MOTO-HUB")
	flag.IntVar(&cfg.height, "height", 384, "projection-area height reported to MOTO-HUB")
	flag.DurationVar(&cfg.heartbeat, "heartbeat", time.Second, "PXC heartbeat interval")
	flag.BoolVar(&cfg.noHeartbeat, "no-heartbeat", false, "do not send PXC heartbeats")
	flag.StringVar(&cfg.player, "player", "ffplay", "H.264 player executable; empty disables preview")
	flag.StringVar(&cfg.videoDump, "video-dump", "", "optional path for received Annex-B video")
	flag.StringVar(&cfg.serviceName, "service-name", defaultServiceName, "Bonjour service name")
	flag.StringVar(&cfg.advertisedIP, "ip", "", "IPv4 address advertised in Bonjour TXT")
	flag.Parse()

	profile, err := selectTBoxProfile(cfg.profileName)
	if err != nil {
		log.Fatal(err)
	}
	cfg.profile = profile
	if cfg.serviceName == defaultServiceName && profile.ServiceName != "" {
		cfg.serviceName = profile.ServiceName
	}
	if cfg.width == 800 && cfg.height == 384 && profile.DefaultSafeWidth > 0 && profile.DefaultSafeHeight > 0 {
		cfg.width = profile.DefaultSafeWidth
		cfg.height = profile.DefaultSafeHeight
	}
	if cfg.displayWidth == 0 {
		cfg.displayWidth = firstPositive(profile.DefaultDisplayWidth, cfg.width)
	}
	if cfg.displayHeight == 0 {
		cfg.displayHeight = firstPositive(profile.DefaultDisplayHeight, cfg.height)
	}
	if cfg.safeX == 0 && profile.DefaultSafeX > 0 {
		cfg.safeX = profile.DefaultSafeX
	}
	if cfg.safeY == 0 && profile.DefaultSafeY > 0 {
		cfg.safeY = profile.DefaultSafeY
	}
	if err := validateDisplayGeometry(cfg); err != nil {
		log.Fatal(err)
	}

	sim := &simulator{cfg: cfg, logger: log.New(os.Stdout, "[tbox-sim] ", log.LstdFlags|log.Lmicroseconds)}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := sim.start(); err != nil {
		sim.logger.Fatal(err)
	}
	<-ctx.Done()
	sim.stop()
}

func (s *simulator) start() error {
	if err := s.validatePorts(); err != nil {
		return err
	}
	if err := validatePreviewPlayer(s.cfg.player); err != nil {
		return err
	}
	listener, err := net.Listen("tcp", fmt.Sprintf(":%d", s.cfg.ecPort))
	if err != nil {
		return fmt.Errorf("listen EasyConn on :%d: %w", s.cfg.ecPort, err)
	}
	s.ecListener = listener
	s.cfg.ecPort = listener.Addr().(*net.TCPAddr).Port

	ip := s.cfg.advertisedIP
	if ip == "" {
		ip = localIPv4()
	}
	serviceName := s.cfg.serviceName
	if serviceName == defaultServiceName {
		serviceName = fmt.Sprintf("%s %d", defaultServiceName, s.cfg.ecPort)
	}
	txt := []string{
		"packagename=" + s.cfg.profile.PackageName,
		"ip=" + ip,
		"modelid=" + s.cfg.profile.ModelID,
		"sn=" + s.cfg.profile.SN,
		"action=9",
	}
	s.mdns, err = zeroconf.Register(serviceName, serviceType, "local.", s.cfg.ecPort, txt, nil)
	if err != nil {
		_ = listener.Close()
		return fmt.Errorf("register Bonjour service: %w", err)
	}

	controlListener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", s.cfg.controlPort))
	if err != nil {
		s.mdns.Shutdown()
		_ = listener.Close()
		return fmt.Errorf("listen local control on :%d: %w", s.cfg.controlPort, err)
	}
	s.controlLn = controlListener
	s.cfg.controlPort = controlListener.Addr().(*net.TCPAddr).Port
	s.control = &http.Server{Handler: s.controlHandler()}
	go func() {
		if err := s.control.Serve(controlListener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			s.logger.Printf("control server: %v", err)
		}
	}()
	go s.acceptEasyConn()
	s.logger.Printf("ready: Bonjour %s (%s) at %s:%d, control http://127.0.0.1:%d", serviceType, serviceName, ip, s.cfg.ecPort, s.cfg.controlPort)
	s.logger.Printf(
		"compatibility profile: %s; modelId=%s; package=%s; HUID=%s; HUName=%s",
		s.cfg.profile.Name,
		s.cfg.profile.ModelID,
		s.cfg.profile.PackageName,
		s.cfg.profile.HUID,
		s.cfg.profile.HUName,
	)
	s.logger.Printf(
		"TFT physical geometry: %dx%d; projection area: %dx%d @(%d,%d); heartbeat=%s",
		s.cfg.displayWidth,
		s.cfg.displayHeight,
		s.cfg.width,
		s.cfg.height,
		s.cfg.safeX,
		s.cfg.safeY,
		heartbeatLabel(s.cfg),
	)
	if s.cfg.player == "" {
		s.logger.Printf("video preview disabled; received H.264 frames will not open ffplay")
	} else {
		s.logger.Printf("video preview player configured: %s", s.cfg.player)
	}
	return nil
}

func validatePreviewPlayer(player string) error {
	if strings.TrimSpace(player) == "" {
		return nil
	}
	resolved, err := exec.LookPath(player)
	if err != nil {
		return fmt.Errorf("video preview player %q is not executable: %w", player, err)
	}
	if resolved == "" {
		return fmt.Errorf("video preview player %q resolved to an empty path", player)
	}
	return nil
}

func validateDisplayGeometry(cfg config) error {
	if cfg.displayWidth < 16 || cfg.displayHeight < 16 {
		return errors.New("physical TFT width and height must be at least 16 pixels")
	}
	if cfg.width < 16 || cfg.height < 16 {
		return errors.New("projection width and height must be at least 16 pixels")
	}
	if cfg.safeX < 0 || cfg.safeY < 0 {
		return errors.New("projection offsets cannot be negative")
	}
	if cfg.safeX+cfg.width > cfg.displayWidth || cfg.safeY+cfg.height > cfg.displayHeight {
		return fmt.Errorf(
			"projection area %dx%d @(%d,%d) does not fit physical TFT %dx%d",
			cfg.width,
			cfg.height,
			cfg.safeX,
			cfg.safeY,
			cfg.displayWidth,
			cfg.displayHeight,
		)
	}
	return nil
}

func (s *simulator) validatePorts() error {
	for _, port := range []int{s.cfg.ecPort, s.cfg.controlPort} {
		if port < 0 || port > 65535 {
			return fmt.Errorf("invalid port %d", port)
		}
	}
	return nil
}

func (s *simulator) stop() {
	s.mu.Lock()
	if s.stopping {
		s.mu.Unlock()
		return
	}
	s.stopping = true
	active := s.session
	s.session = nil
	s.mu.Unlock()
	if active != nil {
		active.stop()
	}
	if s.mdns != nil {
		s.mdns.Shutdown()
	}
	if s.ecListener != nil {
		_ = s.ecListener.Close()
	}
	if s.control != nil {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_ = s.control.Shutdown(ctx)
		cancel()
	}
	if s.controlLn != nil {
		_ = s.controlLn.Close()
	}
	s.logger.Printf("stopped")
}

func (s *simulator) acceptEasyConn() {
	for {
		conn, err := s.ecListener.Accept()
		if err != nil {
			if !s.isStopping() {
				s.logger.Printf("EasyConn accept: %v", err)
			}
			return
		}
		go s.handleEasyConn(conn)
	}
}

func (s *simulator) handleEasyConn(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	frame, err := readEasyConnFrame(conn)
	if err != nil {
		s.logger.Printf("EasyConn read from %s: %v", conn.RemoteAddr(), err)
		return
	}
	if frame.code != easyConnInit {
		s.logger.Printf("EasyConn unexpected command %d", frame.code)
		return
	}
	response := encodeEasyConnFrame(easyConnInitOK, frame.separator, []byte("{\"status\":true}\n"))
	if _, err := conn.Write(response); err != nil {
		s.logger.Printf("EasyConn response: %v", err)
		return
	}
	remoteIP, _, err := net.SplitHostPort(conn.RemoteAddr().String())
	if err != nil {
		s.logger.Printf("cannot identify phone address: %v", err)
		return
	}
	s.logger.Printf("EasyConn accepted from phone %s", remoteIP)
	active := &session{
		phoneIP: remoteIP,
		cfg:     s.cfg,
		log:     s.logger,
		onStop:  s.sessionStopped,
	}
	s.mu.Lock()
	previous := s.session
	s.session = active
	s.mu.Unlock()
	if previous != nil {
		previous.stop()
	}
	if err := active.start(); err != nil {
		s.logger.Printf("session failed: %v", err)
		active.stop()
	}
}

func (s *simulator) isStopping() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.stopping
}

func (s *session) start() error {
	if err := s.connectPXC(); err != nil {
		return err
	}
	if err := s.connectMedia(); err != nil {
		return err
	}
	if err := s.connectStream(); err != nil {
		return err
	}
	s.log.Printf("T-Box session active for phone %s", s.phoneIP)
	return nil
}

func (s *session) stop() {
	s.stopOnce.Do(func() {
		// Close sockets without taking the protocol mutexes. A read may be blocked
		// while holding one of those mutexes; waiting for it before closing its
		// socket would deadlock session shutdown.
		pxc := s.pxc
		pxcData := s.pxcData
		media := s.media
		stream := s.stream
		videoIn := s.videoIn
		player := s.player

		for _, conn := range []net.Conn{pxc, pxcData, media, stream} {
			if conn != nil {
				_ = conn.Close()
			}
		}
		if videoIn != nil {
			_ = videoIn.Close()
		}
		if player != nil && player.Process != nil {
			_ = player.Process.Kill()
			done := make(chan struct{})
			go func() {
				_ = player.Wait()
				close(done)
			}()
			select {
			case <-done:
			case <-time.After(time.Second):
				s.log.Printf("ffplay did not exit within 1s after SIGKILL")
			}
		}
		if s.onStop != nil {
			s.onStop(s)
		}
	})
}

func (s *simulator) sessionStopped(stopped *session) {
	s.mu.Lock()
	if s.session == stopped {
		s.session = nil
	}
	s.mu.Unlock()
	s.logger.Printf("phone session ended; simulator is ready for a new connection")
}

func (s *session) connectPXC() error {
	conn, err := dialPhone(s.phoneIP, 10922)
	if err != nil {
		return fmt.Errorf("connect PXC: %w", err)
	}
	s.pxc = conn
	if err := s.pxcExchange(pxcHandshake, nil, pxcHandshakeOK); err != nil {
		return fmt.Errorf("PXC handshake: %w", err)
	}
	hudConfig := s.hudConfig()
	body, _ := json.Marshal(hudConfig)
	if err := s.pxcExchange(pxcHudConfig, body, pxcPhoneConfig); err != nil {
		return fmt.Errorf("PXC HU config: %w", err)
	}
	clientSet, _ := json.Marshal(map[string]any{"client_set": "easy_conn", "sn": s.cfg.profile.SN})
	// CHECK_SN produces two responses on the Android-side PXC server: an
	// immediate ACK followed by the JSON result. Consume both before sending
	// the next command or the result would be mistaken for the speed ACK.
	s.pxcMu.Lock()
	if _, err := s.writePXC(pxcClientSet, clientSet); err != nil {
		s.pxcMu.Unlock()
		return fmt.Errorf("PXC client set write: %w", err)
	}
	ack, err := s.readPXC()
	if err != nil {
		s.pxcMu.Unlock()
		return fmt.Errorf("PXC client set ACK: %w", err)
	}
	if ack.command != pxcClientSetACK {
		s.pxcMu.Unlock()
		return fmt.Errorf("PXC client set ACK command=0x%x, expected=0x%x", ack.command, pxcClientSetACK)
	}
	result, err := s.readPXC()
	if err != nil {
		s.pxcMu.Unlock()
		return fmt.Errorf("PXC client set result: %w", err)
	}
	if result.command != pxcCheckSN {
		s.pxcMu.Unlock()
		return fmt.Errorf("PXC client set result command=0x%x, expected=0x%x", result.command, pxcCheckSN)
	}
	_, _ = s.writePXC(pxcCheckSNDone, nil)
	s.pxcMu.Unlock()
	if err := s.pxcExchange(pxcSpeedConfig, []byte(`{"speed":0}`), pxcSpeedOK); err != nil {
		return fmt.Errorf("PXC speed config: %w", err)
	}
	if err := s.connectPXCData(); err != nil {
		if s.cfg.profile.RequireCarData {
			return err
		}
		s.log.Printf("PXC CAR_DATA compatibility channel skipped: %v", err)
	}
	if _, err := s.writePXC(pxcHeartbeat, nil); err != nil {
		return fmt.Errorf("PXC heartbeat: %w", err)
	}
	if _, err := s.readPXC(); err != nil {
		return fmt.Errorf("PXC heartbeat response: %w", err)
	}
	if s.cfg.noHeartbeat {
		s.log.Printf("PXC heartbeat disabled by configuration")
	} else {
		go s.heartbeatLoop()
	}
	s.log.Printf("PXC handshake complete")
	return nil
}

func (s *session) connectPXCData() error {
	if !s.cfg.profile.SendCarDataChannel {
		return nil
	}
	conn, err := dialPhone(s.phoneIP, 10922)
	if err != nil {
		return fmt.Errorf("connect PXC CAR_DATA: %w", err)
	}
	s.pxcData = conn
	if err := s.pxcDataExchange(pxcCarData, nil, pxcCarDataOK); err != nil {
		return fmt.Errorf("PXC CAR_DATA select: %w", err)
	}
	if s.cfg.profile.SendCfdlNotifyBurst {
		if err := s.sendCfdlNotifyBurst(); err != nil {
			return err
		}
	}
	s.log.Printf("PXC CAR_DATA channel ready")
	return nil
}

func (s *session) hudConfig() map[string]any {
	profile := s.cfg.profile
	config := map[string]any{
		"HUID":                      profile.HUID,
		"HUName":                    profile.HUName,
		"carBrand":                  "CFMOTO",
		"carModel":                  profile.CarModel,
		"channel":                   profile.Channel,
		"flavor":                    profile.Flavor,
		"pxcVersion":                "1.0.2",
		"package_name":              profile.PackageName,
		"version_name":              profile.VersionName,
		"sdkVersion":                profile.SDKVersion,
		"socketTimeoutPeriodWifi":   9000,
		"supportScreenMirroring":    true,
		"supportScreenTouch":        profile.SupportScreenTouch,
		"supportMirrorOverlayTouch": profile.SupportScreenTouch,
		"supportMirrorReconnect":    true,
		"supportHID":                profile.SupportScreenTouch,
		"supportH264IFrame":         true,
		"supportFunction":           profile.SupportFunction,
		"supportConnect":            profile.SupportConnect,
		"enableDPI":                 true,
		"enableSockServerAuth":      profile.SupportSockAuth,
		"screenType":                0,
		"dpi":                       profile.DPI,
	}
	return config
}

func (s *session) sendCfdlNotifyBurst() error {
	notifications := []struct {
		command uint32
		body    []byte
	}{
		{pxcLogReport, []byte(`{"log":"mdns success"}`)},
		{pxcOtaFtpInfo, []byte(`{"port":11021,"userName":"easyconn","pwd":"easyconn"}`)},
		{pxcMediaFeature, []byte(`{"music":true,"talkie":false,"tts":true,"vr":true,"autoChangeToBT":false}`)},
		{0x10040, []byte(`{"maxNaviIcon":161,"supportFunction":0}`)},
	}
	for _, notification := range notifications {
		if err := s.pxcDataExchange(notification.command, notification.body, notification.command+1); err != nil {
			return fmt.Errorf("PXC notify 0x%x: %w", notification.command, err)
		}
	}
	return nil
}

func (s *session) heartbeatLoop() {
	ticker := time.NewTicker(s.cfg.heartbeat)
	defer ticker.Stop()
	for range ticker.C {
		s.pxcMu.Lock()
		if s.pxc == nil {
			s.pxcMu.Unlock()
			return
		}
		_, writeErr := s.writePXC(pxcHeartbeat, nil)
		if writeErr == nil {
			_ = s.pxc.SetReadDeadline(time.Now().Add(800 * time.Millisecond))
			_, _ = s.readPXC()
		}
		s.pxcMu.Unlock()
		if writeErr != nil {
			s.log.Printf("PXC heartbeat stopped: %v", writeErr)
			s.stop()
			return
		}
		if s.cfg.profile.HeartbeatOnCarData && s.pxcData != nil {
			if err := s.sendPXCDataHeartbeat(); err != nil {
				s.log.Printf("PXC CAR_DATA heartbeat stopped: %v", err)
				s.stop()
				return
			}
		}
	}
}

func (s *session) sendPXCDataHeartbeat() error {
	if s.pxcData == nil {
		return nil
	}
	if _, err := s.writePXCData(pxcHeartbeat, nil); err != nil {
		return err
	}
	_ = s.pxcData.SetReadDeadline(time.Now().Add(800 * time.Millisecond))
	_, _ = s.readPXCData()
	_ = s.pxcData.SetReadDeadline(time.Time{})
	return nil
}

func (s *session) pxcExchange(command uint32, body []byte, expected uint32) error {
	s.pxcMu.Lock()
	defer s.pxcMu.Unlock()
	if _, err := s.writePXC(command, body); err != nil {
		return err
	}
	response, err := s.readPXC()
	if err != nil {
		return err
	}
	if response.command != expected {
		return fmt.Errorf("response command=0x%x, expected=0x%x", response.command, expected)
	}
	return nil
}

func (s *session) pxcDataExchange(command uint32, body []byte, expected uint32) error {
	s.pxcMu.Lock()
	defer s.pxcMu.Unlock()
	if _, err := s.writePXCData(command, body); err != nil {
		return err
	}
	response, err := s.readPXCData()
	if err != nil {
		return err
	}
	if response.command != expected {
		return fmt.Errorf("response command=0x%x, expected=0x%x", response.command, expected)
	}
	return nil
}

func (s *session) writePXC(command uint32, body []byte) (int, error) {
	if s.pxc == nil {
		return 0, errors.New("PXC socket is closed")
	}
	return writePXCFrame(s.pxc, command, body)
}

func (s *session) writePXCData(command uint32, body []byte) (int, error) {
	if s.pxcData == nil {
		return 0, errors.New("PXC CAR_DATA socket is closed")
	}
	return writePXCFrame(s.pxcData, command, body)
}

func writePXCFrame(conn net.Conn, command uint32, body []byte) (int, error) {
	size := uint32(16 + len(body))
	header := make([]byte, 16)
	binary.LittleEndian.PutUint32(header[0:4], command)
	binary.LittleEndian.PutUint32(header[4:8], size)
	binary.LittleEndian.PutUint32(header[8:12], size^command)
	if _, err := conn.Write(append(header, body...)); err != nil {
		return 0, err
	}
	return len(body), nil
}

type pxcFrame struct {
	command uint32
	body    []byte
}

func (s *session) readPXC() (pxcFrame, error) {
	if s.pxc == nil {
		return pxcFrame{}, errors.New("PXC socket is closed")
	}
	return readPXCFrame(s.pxc)
}

func (s *session) readPXCData() (pxcFrame, error) {
	if s.pxcData == nil {
		return pxcFrame{}, errors.New("PXC CAR_DATA socket is closed")
	}
	return readPXCFrame(s.pxcData)
}

func readPXCFrame(conn net.Conn) (pxcFrame, error) {
	var header [16]byte
	if _, err := io.ReadFull(conn, header[:]); err != nil {
		return pxcFrame{}, err
	}
	size := binary.LittleEndian.Uint32(header[4:8])
	command := binary.LittleEndian.Uint32(header[0:4])
	if size < 16 || size > 2<<20 || binary.LittleEndian.Uint32(header[8:12]) != size^command {
		return pxcFrame{}, fmt.Errorf("invalid PXC header command=0x%x size=%d", command, size)
	}
	body := make([]byte, int(size)-16)
	if _, err := io.ReadFull(conn, body); err != nil {
		return pxcFrame{}, err
	}
	return pxcFrame{command: command, body: body}, nil
}

func (s *session) connectMedia() error {
	conn, err := dialPhone(s.phoneIP, 10921)
	if err != nil {
		return fmt.Errorf("connect media control: %w", err)
	}
	s.media = conn
	payload := make([]byte, 30)
	binary.LittleEndian.PutUint16(payload[0:2], uint16(s.cfg.width))
	binary.LittleEndian.PutUint16(payload[2:4], uint16(s.cfg.height))
	binary.LittleEndian.PutUint32(payload[8:12], 2)
	payload[29] = 1
	if err := s.mediaExchange(mediaInit, payload, mediaInitACK); err != nil {
		return fmt.Errorf("media init: %w", err)
	}
	safeArea, err := json.Marshal(map[string]any{
		"viewAreaConfig": map[string]any{
			"viewAreas": []any{
				map[string]any{
					"safeArea": map[string]int{
						"width":  s.cfg.width,
						"height": s.cfg.height,
					},
				},
			},
		},
	})
	if err != nil {
		return fmt.Errorf("encode media screen config: %w", err)
	}
	if err := s.mediaExchange(mediaScreen, safeArea, mediaScreenACK); err != nil {
		return fmt.Errorf("media screen config: %w", err)
	}
	if err := s.mediaExchange(mediaStart, nil, mediaStartACK); err != nil {
		return fmt.Errorf("media stream start: %w", err)
	}
	s.log.Printf(
		"media control negotiated projection area %dx%d inside physical TFT %dx%d",
		s.cfg.width,
		s.cfg.height,
		s.cfg.displayWidth,
		s.cfg.displayHeight,
	)
	return nil
}

func (s *session) mediaExchange(command uint16, payload []byte, expected uint16) error {
	s.mediaMu.Lock()
	defer s.mediaMu.Unlock()
	if s.media == nil {
		return errors.New("media control socket is closed")
	}
	if err := writeMedia(s.media, command, payload); err != nil {
		return err
	}
	response, err := readMedia(s.media)
	if err != nil {
		return err
	}
	if response.command != expected {
		return fmt.Errorf("response command=%d, expected=%d", response.command, expected)
	}
	return nil
}

type mediaFrame struct {
	command uint16
	payload []byte
}

func writeMedia(w io.Writer, command uint16, payload []byte) error {
	header := make([]byte, 8)
	binary.LittleEndian.PutUint16(header[0:2], command)
	binary.LittleEndian.PutUint16(header[2:4], uint16(len(payload)))
	if _, err := w.Write(header); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

func readMedia(r io.Reader) (mediaFrame, error) {
	var header [8]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		return mediaFrame{}, err
	}
	length := int(binary.LittleEndian.Uint16(header[2:4]))
	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return mediaFrame{}, err
	}
	return mediaFrame{command: binary.LittleEndian.Uint16(header[0:2]), payload: payload}, nil
}

func (s *session) connectStream() error {
	conn, err := dialPhone(s.phoneIP, 10920)
	if err != nil {
		return fmt.Errorf("connect media stream: %w", err)
	}
	s.stream = conn
	s.log.Printf("video stream socket connected to phone %s:10920", s.phoneIP)
	if s.cfg.player != "" {
		args := []string{
			"-loglevel", "warning",
			"-fflags", "nobuffer",
			"-flags", "low_delay",
			"-f", "h264",
			"-i", "-",
		}
		if filter := previewVideoFilter(s.cfg); filter != "" {
			args = append(args, "-vf", filter)
		}
		args = append(args, "-window_title", "MOTO-HUB T-Box Simulator")
		cmd := exec.Command(s.cfg.player, args...)
		stdin, err := cmd.StdinPipe()
		if err != nil {
			return fmt.Errorf("open video player stdin: %w", err)
		}
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Start(); err != nil {
			return fmt.Errorf("start %s: %w", s.cfg.player, err)
		}
		s.player = cmd
		s.videoIn = stdin
		s.log.Printf("ffplay preview started: executable=%s pid=%d", s.cfg.player, cmd.Process.Pid)
	}
	go s.readStream()
	return nil
}

func previewVideoFilter(cfg config) string {
	// Always normalize the decoded stream before handing it to ffplay. Android Auto
	// can occasionally renegotiate a portrait frame (1080x1920) even while the
	// simulator is configured for an 800x480 TFT. Returning an empty filter for a
	// full-canvas profile allowed ffplay to size its window from that unexpected
	// frame. Scale/pad first into the configured projection area, then place it in
	// the physical TFT canvas so the preview window is deterministic.
	filters := []string{
		fmt.Sprintf(
			"scale=%d:%d:force_original_aspect_ratio=decrease",
			cfg.width,
			cfg.height,
		),
		fmt.Sprintf(
			"pad=%d:%d:(ow-iw)/2:(oh-ih)/2:color=0x050908",
			cfg.width,
			cfg.height,
		),
	}
	if cfg.displayWidth != cfg.width || cfg.displayHeight != cfg.height || cfg.safeX != 0 || cfg.safeY != 0 {
		filters = append(filters, fmt.Sprintf(
			"pad=%d:%d:%d:%d:color=0x050908",
			cfg.displayWidth,
			cfg.displayHeight,
			cfg.safeX,
			cfg.safeY,
		))
	}
	addReservedBox := func(x, y, width, height int) {
		if width <= 0 || height <= 0 {
			return
		}
		filters = append(
			filters,
			fmt.Sprintf("drawbox=x=%d:y=%d:w=%d:h=%d:color=0x18201d:t=fill", x, y, width, height),
		)
	}
	addReservedBox(0, 0, cfg.displayWidth, cfg.safeY)
	addReservedBox(0, cfg.safeY+cfg.height, cfg.displayWidth, cfg.displayHeight-cfg.safeY-cfg.height)
	addReservedBox(0, cfg.safeY, cfg.safeX, cfg.height)
	addReservedBox(cfg.safeX+cfg.width, cfg.safeY, cfg.displayWidth-cfg.safeX-cfg.width, cfg.height)
	filters = append(
		filters,
		fmt.Sprintf(
			"drawbox=x=%d:y=%d:w=%d:h=%d:color=0x2dd881@0.65:t=2",
			cfg.safeX,
			cfg.safeY,
			cfg.width,
			cfg.height,
		),
		"setsar=1",
	)
	return strings.Join(filters, ",")
}

func (s *session) readStream() {
	var dump io.WriteCloser
	if s.cfg.videoDump != "" {
		file, err := os.OpenFile(filepath.Clean(s.cfg.videoDump), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
		if err != nil {
			s.log.Printf("open video dump: %v", err)
		} else {
			dump = file
			defer dump.Close()
		}
	}
	for {
		if s.stream == nil {
			return
		}
		// 30s idle timeout detects phone disconnect without TCP RST.
		_ = s.stream.SetDeadline(time.Now().Add(30 * time.Second))
		poll := make([]byte, 8)
		binary.LittleEndian.PutUint16(poll[0:2], streamPoll)
		if _, err := s.stream.Write(poll); err != nil {
			s.log.Printf("stream poll: %v", err)
			s.stop()
			return
		}
		var lengthBytes [4]byte
		if _, err := io.ReadFull(s.stream, lengthBytes[:]); err != nil {
			s.log.Printf("stream length: %v", err)
			s.stop()
			return
		}
		total := binary.LittleEndian.Uint32(lengthBytes[:])
		if total == 0 {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		if total < 4 || total > 8<<20 {
			s.log.Printf("invalid stream frame length %d", total)
			return
		}
		var index [4]byte
		if _, err := io.ReadFull(s.stream, index[:]); err != nil {
			s.log.Printf("stream index: %v", err)
			s.stop()
			return
		}
		body := make([]byte, int(total)-4)
		if _, err := io.ReadFull(s.stream, body); err != nil {
			s.log.Printf("stream body: %v", err)
			s.stop()
			return
		}
		if dump != nil {
			_, _ = dump.Write(body)
		}
		if s.videoIn != nil {
			if _, err := s.videoIn.Write(body); err != nil {
				s.log.Printf("video player input: %v", err)
				s.stop()
				return
			}
		}
		s.frames++
		if s.frames == 1 || s.frames%120 == 0 {
			s.log.Printf("received video frame #%d (%d bytes)", s.frames, len(body))
		}
	}
}

func (s *session) sendTouch(request touchRequest) error {
	action, err := touchAction(request.Action)
	if err != nil {
		return err
	}
	payload := make([]byte, 8)
	binary.LittleEndian.PutUint16(payload[0:2], uint16(action))
	binary.LittleEndian.PutUint16(payload[2:4], uint16(clamp(request.X, 0, s.cfg.width-1)))
	binary.LittleEndian.PutUint16(payload[4:6], uint16(clamp(request.Y, 0, s.cfg.height-1)))
	binary.LittleEndian.PutUint16(payload[6:8], uint16(max(request.PointerID, 0)))
	s.mediaMu.Lock()
	defer s.mediaMu.Unlock()
	if s.media == nil {
		return errors.New("no active media control session")
	}
	if err := writeMedia(s.media, mediaTouch, payload); err != nil {
		return err
	}
	_ = s.media.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, _ = readMedia(s.media)
	_ = s.media.SetReadDeadline(time.Time{})
	s.log.Printf("touch %s p%d (%d,%d)", request.Action, request.PointerID, request.X, request.Y)
	return nil
}

func (s *session) sendHandlebar(request handlebarRequest) error {
	gesture, err := normaliseHandlebarGesture(request.Gesture)
	if err != nil {
		return err
	}
	body, err := json.Marshal(handlebarRequest{Gesture: gesture})
	if err != nil {
		return fmt.Errorf("encode handlebar request: %w", err)
	}
	url := fmt.Sprintf("http://%s:%d/handlebar", s.phoneIP, appHandlebarControlPort)
	client := http.Client{Timeout: 2 * time.Second}
	response, err := client.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("send handlebar gesture to MOTO-HUB: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		payload, _ := io.ReadAll(io.LimitReader(response.Body, 512))
		return fmt.Errorf("MOTO-HUB rejected handlebar gesture: status=%d body=%q", response.StatusCode, strings.TrimSpace(string(payload)))
	}
	s.log.Printf("handlebar gesture %s forwarded to phone %s", gesture, s.phoneIP)
	return nil
}

func (s *simulator) controlHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/status", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		s.mu.RLock()
		active := s.session
		s.mu.RUnlock()
		response := statusResponse{
			Profile:       s.cfg.profile.Name,
			ModelID:       s.cfg.profile.ModelID,
			DisplayWidth:  s.cfg.displayWidth,
			DisplayHeight: s.cfg.displayHeight,
			SafeX:         s.cfg.safeX,
			SafeY:         s.cfg.safeY,
			Width:         s.cfg.width,
			Height:        s.cfg.height,
			Heartbeat:     heartbeatLabel(s.cfg),
		}
		if active != nil {
			response.Running = true
			response.PhoneIP = active.phoneIP
			response.Frames = active.frames
			response.VideoPlayer = active.videoIn != nil
		}
		_ = json.NewEncoder(w).Encode(response)
	})
	mux.HandleFunc("/touch", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		var request touchRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		s.mu.RLock()
		active := s.session
		s.mu.RUnlock()
		if active == nil {
			http.Error(w, "no active phone session", http.StatusConflict)
			return
		}
		if err := active.sendTouch(request); err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/handlebar", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		var request handlebarRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		s.mu.RLock()
		active := s.session
		s.mu.RUnlock()
		if active == nil {
			http.Error(w, "no active phone session", http.StatusConflict)
			return
		}
		if err := active.sendHandlebar(request); err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/gesture/pinch", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if err := s.sendTwoFingerGesture("pinch"); err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/gesture/rotate", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if err := s.sendTwoFingerGesture("rotate"); err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	return mux
}

func normaliseHandlebarGesture(value string) (string, error) {
	gesture := strings.TrimSpace(value)
	switch gesture {
	case "volumeUp",
		"volumeUpDouble",
		"volumeDown",
		"volumeDownDouble",
		"enter",
		"enterLong",
		"enterDouble",
		"trackBack",
		"trackBackDouble",
		"trackForward",
		"trackForwardDouble":
		return gesture, nil
	default:
		return "", fmt.Errorf("unknown handlebar gesture %q", value)
	}
}

func (s *simulator) sendTwoFingerGesture(kind string) error {
	s.mu.RLock()
	active := s.session
	s.mu.RUnlock()
	if active == nil {
		return errors.New("no active phone session")
	}
	left, right := 240, 560
	centerY := s.cfg.height / 2
	if kind == "rotate" {
		left, right = s.cfg.width/2-80, s.cfg.width/2+80
	}
	sequence := []touchRequest{
		{Action: "down", PointerID: 0, X: left, Y: centerY},
		{Action: "down", PointerID: 1, X: right, Y: centerY},
	}
	if kind == "pinch" {
		sequence = append(sequence,
			touchRequest{Action: "move", PointerID: 0, X: left + 55, Y: centerY},
			touchRequest{Action: "move", PointerID: 1, X: right - 55, Y: centerY},
		)
	} else {
		sequence = append(sequence,
			touchRequest{Action: "move", PointerID: 0, X: left + 20, Y: centerY - 25},
			touchRequest{Action: "move", PointerID: 1, X: right - 20, Y: centerY + 25},
		)
	}
	sequence = append(sequence,
		touchRequest{Action: "up", PointerID: 1, X: right, Y: centerY},
		touchRequest{Action: "up", PointerID: 0, X: left, Y: centerY},
	)
	for _, event := range sequence {
		if err := active.sendTouch(event); err != nil {
			return err
		}
		time.Sleep(25 * time.Millisecond)
	}
	return nil
}

func touchAction(value string) (int, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "down", "0":
		return 2, nil
	case "up", "1":
		return 1, nil
	case "move", "2":
		return 3, nil
	default:
		return 0, fmt.Errorf("unknown touch action %q", value)
	}
}

func dialPhone(ip string, port int) (net.Conn, error) {
	var last error
	for attempt := 1; attempt <= 5; attempt++ {
		conn, err := net.DialTimeout("tcp", net.JoinHostPort(ip, strconv.Itoa(port)), 3*time.Second)
		if err == nil {
			if tcp, ok := conn.(*net.TCPConn); ok {
				_ = tcp.SetNoDelay(true)
				_ = tcp.SetKeepAlive(true)
			}
			return conn, nil
		}
		last = err
		time.Sleep(time.Duration(attempt) * 250 * time.Millisecond)
	}
	return nil, last
}

type easyConnFrame struct {
	code      int
	separator uint16
	body      []byte
}

func readEasyConnFrame(r io.Reader) (easyConnFrame, error) {
	var header [16]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		return easyConnFrame{}, err
	}
	total := int(binary.LittleEndian.Uint16(header[4:6]))
	if total < 16 || total > 64*1024 {
		return easyConnFrame{}, fmt.Errorf("invalid EasyConn length %d", total)
	}
	body := make([]byte, total-16)
	if _, err := io.ReadFull(r, body); err != nil {
		return easyConnFrame{}, err
	}
	return easyConnFrame{code: int(binary.LittleEndian.Uint16(header[0:2])), separator: binary.LittleEndian.Uint16(header[11:13]), body: body}, nil
}

func encodeEasyConnFrame(code int, separator uint16, body []byte) []byte {
	total := len(body) + 16
	header := make([]byte, 16)
	binary.LittleEndian.PutUint16(header[0:2], uint16(code))
	binary.LittleEndian.PutUint16(header[2:4], separator)
	binary.LittleEndian.PutUint16(header[4:6], uint16(total))
	binary.LittleEndian.PutUint16(header[8:10], uint16(total^16))
	binary.LittleEndian.PutUint16(header[11:13], separator)
	return append(header, body...)
}

func localIPv4() string {
	addrs, _ := net.InterfaceAddrs()
	for _, address := range addrs {
		ipNet, ok := address.(*net.IPNet)
		if ok && ipNet.IP.To4() != nil && !ipNet.IP.IsLoopback() {
			return ipNet.IP.To4().String()
		}
	}
	return "127.0.0.1"
}

func heartbeatLabel(cfg config) string {
	if cfg.noHeartbeat {
		return "disabled"
	}
	return cfg.heartbeat.String()
}

func clamp(value, low, high int) int {
	if value < low {
		return low
	}
	if value > high {
		return high
	}
	return value
}

func firstPositive(values ...int) int {
	for _, value := range values {
		if value > 0 {
			return value
		}
	}
	return 0
}
