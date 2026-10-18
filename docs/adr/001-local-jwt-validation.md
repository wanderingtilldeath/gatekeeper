# ADR 001: Validate JWTs at the gateway with the public key, not by calling `auth`

**Status:** accepted

## Context
The first version of the gateway called `auth /auth/validate?token=...` over HTTP on every request. That adds a network hop (plus a Eureka lookup) to every call, makes `auth` a hard dependency of all traffic, and puts tokens into URLs and access logs. Tokens were HS256 signed with a secret committed to the repo, so anyone could forge one.

## Decision
- `auth` signs 15-minute access tokens with an **RS256 private key** and publishes the public key as a JWKS at `/auth/.well-known/jwks.json` (each key has a `kid`).
- The gateway is a Spring Security resource server. It fetches the JWKS, caches it (5 minutes), and verifies signature, expiry and issuer **locally**. A token with an unknown `kid` triggers a re-fetch, which is how key rotation works without a restart, but at most once per 30 seconds (`ThrottledJwkSource`). We measured the unthrottled default: 50 tokens with random `kid`s caused 50 calls to `auth`, so anyone could turn junk requests into load on `auth`. Fetches have a 3-second timeout, and if one fails the cached keys keep being used.
- `/auth/validate` is removed.
- Refresh tokens (7 days, random, stored hashed, rotated on every use) cover re-login. Replaying a used refresh token deletes the whole token family.

## Consequences
- Good: no per-request call to `auth`; if `auth` is briefly down, already-issued tokens keep working until they expire (the gateway only needs the cached keys). Only `auth` can mint tokens.
- Rotation rule: publish the new key in the JWKS first, start signing with it later (longer than the 5-minute cache). A token signed with a brand-new key can be rejected for up to 30 seconds if the gateway refreshed just before it was published.
- Bad: an access token can't be revoked before it expires (max 15 minutes). That is why the lifetime is short. A deny-list in Redis keyed by `jti` is the next step if instant revocation becomes necessary.
- The latency benefit is measured, not assumed: see `bench/README.md` (scenario 2).
