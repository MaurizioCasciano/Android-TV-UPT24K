package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class NumericIpAddressTest {
    @Test public void acceptsDottedIpv4() {
        for (String ip : new String[]{"192.0.2.1", "0.0.0.0", "255.255.255.255"})
            assertTrue(ip, NumericIpAddress.isValid(ip));
    }

    @Test public void acceptsCompressedExpandedAndLoopbackIpv6() {
        for (String ip : new String[]{"2001:db8::1", "2001:DB8:1234:5678:9abc:def0:1234:5678", "::", "::1"})
            assertTrue(ip, NumericIpAddress.isValid(ip));
    }

    @Test public void acceptsEmbeddedIpv4() {
        assertTrue(NumericIpAddress.isValid("::ffff:192.0.2.128"));
        assertTrue(NumericIpAddress.isValid("2001:db8::192.0.2.1"));
    }

    @Test public void rejectsMalformedAndLegacyIpv4() {
        for (String ip : new String[]{"1.2.3", "1.2.3.256", "1.2.3.-1", "1.2.3.4.5", "01.2.3.4", "1..2.3", "2130706433", "0x7f000001"})
            assertFalse(ip, NumericIpAddress.isValid(ip));
    }

    @Test public void rejectsMalformedIpv6AndDecoratedAddresses() {
        for (String ip : new String[]{":::1", "1::2::3", "1:2:3:4:5:6:7:8:9", "2001:db8::xyz", "[2001:db8::1]", "fe80::1%wlan0", "192.0.2.1:443", "::ffff:192.0.2.999"})
            assertFalse(ip, NumericIpAddress.isValid(ip));
    }

    @Test public void rejectsHostnamesUrlsWhitespaceAndMissingValuesWithoutDns() {
        for (String ip : new String[]{"localhost", "example.com", "deadbeef", "https://192.0.2.1", " 192.0.2.1", "2001:db8::1\n", "", null})
            assertFalse(ip, NumericIpAddress.isValid(ip));
    }
}
