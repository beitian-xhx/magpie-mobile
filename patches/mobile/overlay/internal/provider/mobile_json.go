package provider

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"

	"github.com/tidwall/jsonc"
	"github.com/yetone/magpie/internal/mobilepolicy"
)

// Desktop parsing is unchanged. Mobile keeps JSONC readable after a
// format-preserving provider edit, and attaches a private read generation.
func mobileProviderJSON(b []byte) []byte {
	if !mobilepolicy.Enabled {
		return b
	}
	return jsonc.ToJSON(bytes.Clone(b))
}

func mobileFileRevision(b []byte) string {
	if !mobilepolicy.Enabled || b == nil {
		return ""
	}
	sum := sha256.Sum256(bytes.TrimPrefix(b, utf8BOM))
	return hex.EncodeToString(sum[:])
}
