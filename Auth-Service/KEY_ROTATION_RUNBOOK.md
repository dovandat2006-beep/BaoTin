# Key persistence and rotation

Signing and encryption keys are separate read-only file mounts. Outside the test profile,
startup requires explicit files; there is no generated production key fallback.

## RS256 signing keys
1. Generate a new RSA key externally (3072 bits preferred), new distinct `JWT_KEY_ID` and PKCS#8 private-key file.
2. Add old **public-only** RSA JWK(s) to `JWT_PREVIOUS_PUBLIC_JWKS_PATH`; keep private history out of JWKS.
3. Restart Auth with the new private key plus previous public keys. New tokens use the new kid; existing unexpired tokens verify against the overlap set.
4. Gateway reads public JWKS and refreshes on unknown kid. Keep overlap at least access TTL (600s) plus clock skew/cache propagation.
5. After overlap, remove retired public keys and restart Auth. Auth rejects retired kids immediately; Gateway cache may take its JWKS cache lifetime to refresh, but downstream Auth also validates.

JWT sessions and refresh hashes remain in PostgreSQL; same-key restart and signing-key rotation do not erase them.
Do not reuse kid for a different key. Unknown kid, wrong issuer/audience/type/algorithm or future/expired timestamps fail validation.

## AES-GCM encryption keys
`AUTH_ENCRYPTION_KEY_ID` names the active 32-byte raw key file. `AUTH_PREVIOUS_ENCRYPTION_KEYS`
maps previous IDs to files (`id=/run/secrets/file.bin,...`); `AUTH_LEGACY_ENCRYPTION_KEY_ID`
identifies legacy nonce+ciphertext rows written before the key-id envelope.

1. Deploy new key ID/file while retaining old decryption keys and the legacy ID mapping.
2. Restart Auth; new ciphertext uses an authenticated key-id envelope. Old ciphertext remains readable.
3. Run the fixed-allowlist migrator CLI; it updates MFA, pending enrollment, mail and OAuth encrypted columns in transactions of 100 rows. It authenticates ciphertext before skipping already-current rows, uses keyset traversal and can resume after a failed batch.
4. Verify changed-count/integration tests and take an encrypted DB backup before retiring old keys. Allow in-flight older app instances to finish or stop them before rewrap.
5. Remove old keys only after rewrap and verify no old payload remains. Rollback before retirement must retain the full key ring; legacy software without envelope support must not read the new envelope format.

```powershell
# DB_URL + DB_MIGRATION_USER=baotin_auth_migrator + DB_MIGRATION_PASSWORD from isolated job credentials;
# active/previous encryption key settings are supplied from the external environment.
.\gradlew.bat rewrapSecrets
```

The CLI requires actual `session_user=baotin_auth_migrator`, accepts no table/SQL arguments,
prints only counts and never plaintext. Keys/private credentials are not put in Git, ZIP artifacts or console output.
