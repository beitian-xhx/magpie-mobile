package gateway

// mobileAuthenticated is private, request-local evidence that identifyCaller
// authenticated the original key before replacing credential headers. It is not
// an HTTP header and never permits a fresh request to skip authentication.
type mobileAuthenticated struct{}
