package ca.int13.librenmsmcp.tools.support;

import java.net.Inet6Address;
import java.util.Optional;

/**
 * Python {@code ipaddress.ip_network(text, strict=False)} for the forms LibreNMS ARP search can
 * receive: an IPv4 address, IPv4 with a prefix length, dotted netmask or hostmask, or any IPv6
 * address/network. Anything Python rejects is rejected here too.
 */
public final class IpNetworks {
    private IpNetworks() {}

    /** A parsed network: version 4 or 6, the network address (IPv4 only) and prefix length. */
    public record Network(int version, String networkAddress, int prefixLength) {}

    /** The network, or empty where Python raises ValueError. */
    public static Optional<Network> parse(String text) {
        String[] parts = text.split("/", -1);
        if (parts.length > 2) {
            return Optional.empty();
        }
        String address = parts[0];
        String prefix = parts.length == 2 ? parts[1] : null;
        Long v4 = ipv4(address);
        if (v4 != null) {
            Integer length = prefix == null ? Integer.valueOf(32) : ipv4Prefix(prefix);
            if (length == null) {
                return Optional.empty();
            }
            long mask = length == 0 ? 0 : (0xFFFFFFFFL << (32 - length)) & 0xFFFFFFFFL;
            return Optional.of(new Network(4, dotted(v4 & mask), length));
        }
        if (address.contains(":") && !address.contains("%") && !address.startsWith("[") && ipv6(address)) {
            Integer length = prefix == null ? Integer.valueOf(128) : decimal(prefix, 128);
            return length == null ? Optional.empty() : Optional.of(new Network(6, null, length));
        }
        return Optional.empty();
    }

    /** Strict dotted quad: four ASCII decimal octets, no leading zeros. */
    private static Long ipv4(String s) {
        String[] octets = s.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        long value = 0;
        for (String o : octets) {
            if (o.isEmpty()
                    || o.length() > 3
                    || !o.chars().allMatch(c -> c >= '0' && c <= '9')
                    || (o.length() > 1 && o.charAt(0) == '0')) {
                return null;
            }
            int n = Integer.parseInt(o);
            if (n > 255) {
                return null;
            }
            value = value << 8 | n;
        }
        return value;
    }

    /** A prefix length (ASCII digits, 0–32) or a dotted netmask / hostmask. */
    private static Integer ipv4Prefix(String s) {
        Integer n = decimal(s, 32);
        if (n != null) {
            return n;
        }
        Long mask = ipv4(s);
        if (mask == null) {
            return null;
        }
        Integer length = contiguous(mask);
        return length != null ? length : contiguous(~mask & 0xFFFFFFFFL);
    }

    /** Prefix length of a netmask with contiguous leading ones, or null. */
    private static Integer contiguous(long mask) {
        int ones = Long.numberOfLeadingZeros(~mask & 0xFFFFFFFFL) - 32;
        long expected = ones == 0 ? 0 : (0xFFFFFFFFL << (32 - ones)) & 0xFFFFFFFFL;
        return mask == expected ? ones : null;
    }

    private static Integer decimal(String s, int max) {
        if (s.isEmpty() || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        try {
            int n = Integer.parseInt(s);
            return n <= max ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean ipv6(String s) {
        try {
            Inet6Address.ofLiteral(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String dotted(long v) {
        return (v >> 24 & 255) + "." + (v >> 16 & 255) + "." + (v >> 8 & 255) + "." + (v & 255);
    }
}
