// agentbridge-shim — cross-platform PATH shim for ACP agents.
//
// Mirrors the POSIX bash version (see agentbridge-shim.sh) so that a single
// static binary covers Linux, macOS, and Windows without dragging in cmd.exe
// or bash. Behaviour:
//
//  1. Determine the command name from filepath.Base(os.Args[0]) (e.g. "cat",
//     "git", "grep.exe"). Strips the .exe suffix on Windows.
//  2. Read AGENTBRIDGE_SHIM_PORT and AGENTBRIDGE_SHIM_TOKEN. If either is
//     missing → fall through immediately (no IDE running).
//  3. POST argv as application/x-www-form-urlencoded to
//     http://127.0.0.1:$port/shim-exec with header X-Shim-Token: $token.
//  4. On HTTP 200 the body is "EXIT <n>\n<stdout-bytes>". Print stdout, exit n.
//     On 204, any other status, or network error → fall through.
//  5. Fall through = re-exec the real binary of the same name found on PATH
//     after removing our own directory. Argv and stdin/stdout/stderr are
//     forwarded unchanged; the child's exit code is propagated.
//
// Implementation note: we deliberately avoid net/http and net/url to keep the
// statically-linked binary small (~1.5 MB instead of ~5 MB). The shim talks to
// 127.0.0.1 only — no TLS, no redirects, no proxies — so a hand-rolled
// HTTP/1.1 client over net.Dial is plenty.
package main

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	exitFallthroughError = 127
	dialTimeout          = 2 * time.Second
	totalTimeout         = 30 * time.Second
)

func main() {
	cmdName := commandName()
	args := os.Args[1:]

	if handled, exitCode := tryRedirect(cmdName, args); handled {
		os.Exit(exitCode)
	}
	os.Exit(execReal(cmdName, args))
}

// commandName returns the lowercased basename of os.Args[0] without the .exe
// suffix on Windows. The shim dispatches purely by this name.
func commandName() string {
	base := filepath.Base(os.Args[0])
	if runtime.GOOS == "windows" {
		base = strings.TrimSuffix(strings.ToLower(base), ".exe")
	}
	return base
}

// tryRedirect attempts the HTTP round-trip to /shim-exec. Returns
// (true, exitCode) when the IDE handled the call, (false, _) when the caller
// should fall through to the real binary.
func tryRedirect(cmdName string, args []string) (bool, int) {
	port := os.Getenv("AGENTBRIDGE_SHIM_PORT")
	token := os.Getenv("AGENTBRIDGE_SHIM_TOKEN")
	if port == "" || token == "" {
		return false, 0
	}

	body := encodeArgvForm(cmdName, args)

	conn, err := net.DialTimeout("tcp", "127.0.0.1:"+port, dialTimeout)
	if err != nil {
		return false, 0
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(totalTimeout))

	req := buildRequest(port, token, body)
	if _, err := conn.Write(req); err != nil {
		return false, 0
	}

	status, respBody, err := readResponse(conn)
	if err != nil {
		return false, 0
	}
	if status == 204 || status != 200 {
		return false, 0
	}

	exitCode, payload, ok := parseExitFrame(respBody)
	if !ok {
		return false, 0
	}
	_, _ = os.Stdout.Write(payload)
	return true, exitCode
}

// encodeArgvForm builds the application/x-www-form-urlencoded body
// "argv=<cmd>&argv=<arg1>&argv=<arg2>…". Implements RFC 3986 percent-encoding
// inline so we don't need net/url.
func encodeArgvForm(cmdName string, args []string) []byte {
	var buf bytes.Buffer
	writePair := func(v string) {
		if buf.Len() > 0 {
			buf.WriteByte('&')
		}
		buf.WriteString("argv=")
		percentEncode(&buf, v)
	}
	writePair(cmdName)
	for _, a := range args {
		writePair(a)
	}
	return buf.Bytes()
}

// percentEncode writes s to buf using application/x-www-form-urlencoded
// rules: space becomes '+', unreserved characters pass through, everything
// else becomes %HH.
func percentEncode(buf *bytes.Buffer, s string) {
	const hex = "0123456789ABCDEF"
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c == ' ':
			buf.WriteByte('+')
		case (c >= 'A' && c <= 'Z') ||
			(c >= 'a' && c <= 'z') ||
			(c >= '0' && c <= '9') ||
			c == '-' || c == '_' || c == '.' || c == '~':
			buf.WriteByte(c)
		default:
			buf.WriteByte('%')
			buf.WriteByte(hex[c>>4])
			buf.WriteByte(hex[c&0x0F])
		}
	}
}

// buildRequest serialises the HTTP/1.1 POST as bytes.
func buildRequest(port, token string, body []byte) []byte {
	var b bytes.Buffer
	b.WriteString("POST /shim-exec HTTP/1.1\r\n")
	b.WriteString("Host: 127.0.0.1:")
	b.WriteString(port)
	b.WriteString("\r\n")
	b.WriteString("X-Shim-Token: ")
	b.WriteString(token)
	b.WriteString("\r\n")
	b.WriteString("Content-Type: application/x-www-form-urlencoded\r\n")
	b.WriteString("Content-Length: ")
	b.WriteString(strconv.Itoa(len(body)))
	b.WriteString("\r\n")
	b.WriteString("Connection: close\r\n")
	b.WriteString("\r\n")
	b.Write(body)
	return b.Bytes()
}

// readResponse reads and parses an HTTP/1.1 response. Supports both
// Content-Length and chunked transfer encoding (Java's HttpServer always
// uses one or the other). Returns (status, body, error).
func readResponse(r io.Reader) (int, []byte, error) {
	br := bufio.NewReader(r)

	// Status line: "HTTP/1.1 200 OK\r\n"
	statusLine, err := br.ReadString('\n')
	if err != nil {
		return 0, nil, err
	}
	parts := strings.SplitN(strings.TrimRight(statusLine, "\r\n"), " ", 3)
	if len(parts) < 2 {
		return 0, nil, fmt.Errorf("malformed status line")
	}
	status, err := strconv.Atoi(parts[1])
	if err != nil {
		return 0, nil, err
	}

	// Headers
	contentLength := -1
	chunked := false
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return 0, nil, err
		}
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			break
		}
		colon := strings.IndexByte(line, ':')
		if colon < 0 {
			continue
		}
		name := strings.ToLower(strings.TrimSpace(line[:colon]))
		value := strings.TrimSpace(line[colon+1:])
		switch name {
		case "content-length":
			if n, err := strconv.Atoi(value); err == nil {
				contentLength = n
			}
		case "transfer-encoding":
			if strings.EqualFold(value, "chunked") {
				chunked = true
			}
		}
	}

	if status == 204 {
		return status, nil, nil
	}

	if chunked {
		body, err := readChunkedBody(br)
		return status, body, err
	}
	if contentLength >= 0 {
		body := make([]byte, contentLength)
		if _, err := io.ReadFull(br, body); err != nil {
			return 0, nil, err
		}
		return status, body, nil
	}
	// No length given — read until EOF (Connection: close).
	body, err := io.ReadAll(br)
	return status, body, err
}

func readChunkedBody(br *bufio.Reader) ([]byte, error) {
	var out bytes.Buffer
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return nil, err
		}
		sizeStr := strings.TrimRight(line, "\r\n")
		if i := strings.IndexByte(sizeStr, ';'); i >= 0 {
			sizeStr = sizeStr[:i]
		}
		size, err := strconv.ParseInt(strings.TrimSpace(sizeStr), 16, 64)
		if err != nil {
			return nil, err
		}
		if size == 0 {
			// Trailer / CRLF
			_, _ = br.ReadString('\n')
			return out.Bytes(), nil
		}
		chunk := make([]byte, size)
		if _, err := io.ReadFull(br, chunk); err != nil {
			return nil, err
		}
		out.Write(chunk)
		if _, err := br.ReadString('\n'); err != nil {
			return nil, err
		}
	}
}

// parseExitFrame validates and splits the wire format "EXIT <n>\n<bytes>".
func parseExitFrame(body []byte) (int, []byte, bool) {
	const prefix = "EXIT "
	if !bytes.HasPrefix(body, []byte(prefix)) {
		return 0, nil, false
	}
	rest := body[len(prefix):]
	nl := bytes.IndexByte(rest, '\n')
	if nl < 0 {
		return 0, nil, false
	}
	n, err := strconv.Atoi(string(rest[:nl]))
	if err != nil {
		return 0, nil, false
	}
	return n, rest[nl+1:], true
}

// execReal locates the real cmdName on PATH (with our directory removed) and
// runs it forwarding stdio. Returns the child's exit code, or
// exitFallthroughError when no real binary is found.
func execReal(cmdName string, args []string) int {
	stripped := pathWithoutSelf()

	realPath, err := lookPathWith(cmdName, stripped)
	if err != nil {
		fmt.Fprintf(os.Stderr, "agentbridge-shim: %s: command not found on PATH (after removing shim dir)\n", cmdName)
		return exitFallthroughError
	}

	cmd := exec.Command(realPath, args...) // #nosec G204 -- shim is a thin wrapper; argv comes from caller
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Env = append(os.Environ(), "PATH="+stripped)

	if err := cmd.Run(); err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return exitErr.ExitCode()
		}
		fmt.Fprintf(os.Stderr, "agentbridge-shim: failed to exec %s: %v\n", realPath, err)
		return exitFallthroughError
	}
	return cmd.ProcessState.ExitCode()
}

// pathWithoutSelf returns $PATH with the directory containing os.Args[0]
// removed (case-insensitive on Windows).
func pathWithoutSelf() string {
	selfDir, err := selfDir()
	if err != nil {
		return os.Getenv("PATH")
	}
	sep := string(os.PathListSeparator)
	parts := strings.Split(os.Getenv("PATH"), sep)
	out := parts[:0]
	for _, p := range parts {
		if pathsEqual(p, selfDir) {
			continue
		}
		out = append(out, p)
	}
	return strings.Join(out, sep)
}

// selfDir returns the absolute directory of the running shim binary.
func selfDir() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return filepath.Dir(os.Args[0]), nil
	}
	resolved, err := filepath.EvalSymlinks(exe)
	if err != nil {
		resolved = exe
	}
	return filepath.Dir(resolved), nil
}

func pathsEqual(a, b string) bool {
	if runtime.GOOS == "windows" {
		return strings.EqualFold(filepath.Clean(a), filepath.Clean(b))
	}
	return filepath.Clean(a) == filepath.Clean(b)
}

// lookPathWith mimics exec.LookPath but uses the supplied PATH instead of
// the process environment.
func lookPathWith(name, path string) (string, error) {
	oldPath := os.Getenv("PATH")
	if err := os.Setenv("PATH", path); err != nil {
		return "", err
	}
	defer func() { _ = os.Setenv("PATH", oldPath) }()
	return exec.LookPath(name)
}
