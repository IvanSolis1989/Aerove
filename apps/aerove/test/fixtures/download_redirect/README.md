# Loopback-only TLS fixture

This self-signed certificate and its deliberately public private key are synthetic test data. They identify only localhost / loopback addresses and must never be used outside tests. Tests trust this certificate through an isolated SecurityContext, not by accepting invalid certificates. No application trust store or production certificate policy is changed.
