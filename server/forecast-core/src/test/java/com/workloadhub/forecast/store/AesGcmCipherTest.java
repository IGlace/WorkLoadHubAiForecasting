package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void ciphertextIsVersionedAndRandomised() {
        AesGcmCipher c = AesGcmCipher.fromBase64Key(KEY);
        String a = c.encrypt("gho_abc");
        String b = c.encrypt("gho_abc");
        assertTrue(a.startsWith("v1:"));
        assertNotEquals(a, b, "fresh nonce per value");
        assertEquals("gho_abc", c.decrypt(a));
        assertEquals("gho_abc", c.decrypt(b));
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> new AesGcmCipher(new byte[16]));
    }

    @Test
    void tamperedValueFails() {
        AesGcmCipher c = AesGcmCipher.fromBase64Key(KEY);
        String enc = c.encrypt("gho_abc");
        String tampered = enc.substring(0, enc.length() - 2) + "AA";
        assertThrows(IllegalStateException.class, () -> c.decrypt(tampered));
    }

    @Property
    boolean roundTripsAnyString(@ForAll String s) {
        AesGcmCipher c = AesGcmCipher.fromBase64Key(KEY);
        return s.equals(c.decrypt(c.encrypt(s)));
    }
}
