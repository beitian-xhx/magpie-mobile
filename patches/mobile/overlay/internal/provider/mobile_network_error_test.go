//go:build mobile

package provider

import (
    "context"
    "crypto/tls"
    "crypto/x509"
    "errors"
    "fmt"
    "net"
    "strings"
    "testing"
)

func TestMobileModelsNetworkErrorsAreSpecificAndSafe(t *testing.T) {
    cases := []struct{ err error; code string }{
        {&net.DNSError{Err: "synthetic-secret", Name: "private-host.invalid"}, "provider_models_dns"},
        {x509.UnknownAuthorityError{}, "provider_models_tls_certificate"},
        {&tls.CertificateVerificationError{Err: x509.UnknownAuthorityError{}}, "provider_models_tls_certificate"},
        {errors.New("connection failed synthetic-secret"), "provider_models_unavailable"},
        {context.DeadlineExceeded, "provider_models_timeout"},
    }
    for _, c := range cases {
        err := mobileModelsNetworkError(context.Background(), fmt.Errorf("wrapped: %w", c.err))
        var public *MobileProviderModelsError
        if !errors.As(err, &public) || public.Code != c.code { t.Fatalf("unexpected fixed code") }
        if strings.Contains(public.Message, "synthetic-secret") || strings.Contains(public.Message, "private-host") { t.Fatal("private error leaked") }
    }
}
