package com.gathin.tvhome;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Numeric IPv4/IPv6 literals only; validation never resolves a hostname. */
final class NumericIpAddress {
    private NumericIpAddress() {}

    static boolean isValid(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) return false;
        if (value.indexOf(':') >= 0) {
            // A colon and this alphabet select numeric IPv6 parsing, never DNS.
            // Provider addresses must not include brackets, ports or interface scope IDs.
            if (!value.matches("[0-9a-fA-F:.]+")) return false;
            try {
                InetAddress.getByName(value);
                return true;
            } catch (UnknownHostException invalid) {
                return false;
            }
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}") || (octet.length() > 1 && octet.charAt(0) == '0')
                    || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }
}
