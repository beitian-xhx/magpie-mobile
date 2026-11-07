//go:build mobile

package cli

import (
	"io"
	"os"
	"strings"
	"testing"

	"github.com/yetone/magpie/internal/gui"
)

func mobileCapturePrintWeb(t *testing.T, web *gui.Web) string {
	t.Helper()
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer read.Close()
	defer write.Close()
	old := os.Stdout
	os.Stdout = write
	defer func() { os.Stdout = old }()
	// The output is bounded to two lines, below the pipe buffer. If printWeb
	// panics, Go fails this test and deferred cleanup still restores stdout.
	printWeb(web)
	if err := write.Close(); err != nil {
		t.Fatal(err)
	}
	os.Stdout = old
	data, err := io.ReadAll(read)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestMobilePrintWebNoKeyDoesNotPanic(t *testing.T) {
	for _, link := range []string{"", "http://127.0.0.1:3430/", "http://127.0.0.1:3430/?view=gateway"} {
		output := mobileCapturePrintWeb(t, &gui.Web{Addr: "127.0.0.1:3430", Link: link})
		if !strings.Contains(output, "127.0.0.1:3430") || !strings.Contains(output, "App-managed credentials") {
			t.Fatal("mobile output lacks private address or authentication guidance")
		}
		if strings.Contains(output, "the link carries") || strings.Contains(output, "through your proxy") || strings.Contains(output, "/?k=") {
			t.Fatal("mobile output kept desktop key-bearing link guidance")
		}
	}
}

func TestMobilePrintWebDoesNotOutputCredentials(t *testing.T) {
	const secret = "mobile-print-test-private-secret-0123456789"
	t.Setenv("MAGPIE_WEB_KEY", secret)
	t.Setenv("MAGPIE_WEB_URL", "https://proxy.example.invalid")
	output := mobileCapturePrintWeb(t, &gui.Web{Addr: "127.0.0.1:3430", Link: "http://127.0.0.1:3430/?k=" + secret})
	if strings.Contains(output, secret) || strings.Contains(output, "/?k=") || strings.Contains(output, "proxy.example.invalid") {
		t.Fatal("mobile print exposed a credential or proxy link")
	}
}
