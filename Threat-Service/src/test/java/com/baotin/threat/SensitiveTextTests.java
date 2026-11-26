package com.baotin.threat;

import com.baotin.threat.domain.SensitiveText;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SensitiveTextTests {
    @Test void keepsWarningWordingAndMasksContactsCodesAccountsAndUrlSecrets() {
        String raw="Cảnh báo khóa tài khoản! Nhập OTP 123456 tại https://example.com/login?token=SECRET. Liên hệ 0901234567 hoặc alice@example.com; mật khẩu: Pa55w0rd!";
        String text=SensitiveText.redact(raw);
        assertTrue(text.contains("Cảnh báo khóa tài khoản"));assertTrue(text.contains("tại https://example.com/***"));
        for(String value:java.util.List.of("123456","0901234567","alice@example.com","Pa55w0rd","SECRET","?token"))assertFalse(text.contains(value),value);
        assertTrue(text.contains("***"));assertFalse(text.contains("đã ẩn"));assertEquals(text,SensitiveText.redact(text));
    }
    @Test void masksMultiwordIdentityAndAddressAndKeepsDistinctWarningSentence() {
        String text=SensitiveText.redact("Họ tên: Nguyễn Văn A; địa chỉ: 12 đường Test, Quà miễn phí!\nNhập OTP 123456.");
        assertFalse(text.contains("Nguyễn"));assertFalse(text.contains("Văn A"));assertFalse(text.contains("đường Test"));
        assertTrue(text.contains("Quà miễn phí!"));assertFalse(text.contains("\n"));
        assertEquals(text,SensitiveText.redact(text));
        assertEquals("***",SensitiveText.redact("a".repeat(10000)));
    }
    @Test void normalMessageIsNotBlanketHiddenAndUnicodeRemainsReadable() {
        assertEquals("Nhận quà miễn phí, chuyển khoản ngay!",SensitiveText.redact("Nhận quà miễn phí, chuyển khoản ngay!"));
        assertThrows(IllegalArgumentException.class,()->SensitiveText.redact("x".repeat(10001)));
        assertDoesNotThrow(()->SensitiveText.redact("a".repeat(10000)));
    }
}
