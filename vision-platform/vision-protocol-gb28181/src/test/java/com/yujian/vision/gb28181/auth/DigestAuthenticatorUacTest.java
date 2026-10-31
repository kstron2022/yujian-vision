package com.yujian.vision.gb28181.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 级联 UAC 侧 Digest Authorization 构造（docs/08 §2.1）：与 verify 校验式对称, 往返自洽。
 */
class DigestAuthenticatorUacTest {

    private static final String URI = "sip:34020000002000000001@3402000000";

    @Test
    void challengeThenAuthorizeThenVerifyRoundTrip() {
        String challenge = DigestAuthenticator.createChallenge("3402000000", "nonce-abc123");
        String auth = DigestAuthenticator.buildAuthorization(challenge, "REGISTER", URI,
                "34020000001320000001", "secret-pw");
        assertTrue(auth.startsWith("Digest username=\"34020000001320000001\""), auth);
        assertTrue(auth.contains("realm=\"3402000000\""), auth);
        assertTrue(auth.contains("nonce=\"nonce-abc123\""), auth);
        assertTrue(auth.contains("uri=\"" + URI + "\""), auth);
        assertTrue(DigestAuthenticator.verify(auth, "REGISTER", "secret-pw"));
    }

    @Test
    void qopChallengeRoundTrip() {
        String challenge = "Digest realm=\"gov\", nonce=\"7cef\", qop=\"auth,auth-int\", opaque=\"00ff\"";
        String auth = DigestAuthenticator.buildAuthorization(challenge, "REGISTER", URI,
                "34020000001320000001", "secret-pw");
        assertTrue(auth.contains("qop=auth"), auth);
        assertTrue(auth.contains("opaque=\"00ff\""), auth);
        assertTrue(DigestAuthenticator.verify(auth, "REGISTER", "secret-pw"));
    }

    @Test
    void wrongPasswordFailsVerify() {
        String challenge = DigestAuthenticator.createChallenge("3402000000", "nonce-x");
        String auth = DigestAuthenticator.buildAuthorization(challenge, "REGISTER", URI, "u", "right");
        assertTrue(DigestAuthenticator.verify(auth, "REGISTER", "right"));
        assertEquals(false, DigestAuthenticator.verify(auth, "REGISTER", "wrong"));
    }

    @Test
    void missingRealmOrNonceRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DigestAuthenticator.buildAuthorization("Digest nonce=\"n\"", "REGISTER", URI, "u", "p"));
        assertThrows(IllegalArgumentException.class,
                () -> DigestAuthenticator.buildAuthorization("Digest realm=\"r\"", "REGISTER", URI, "u", "p"));
    }
}
