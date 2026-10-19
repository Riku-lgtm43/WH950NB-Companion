package com.xriku.edifiercontrol;

import java.util.LinkedHashMap;
import java.util.Map;

/** WH950NB commands recorded from the developer's own headphones. */
public final class Protocol {
    private Protocol() { }

    public static final String ANC = "ANC";
    public static final String AMBIENT = "AMBIENT";
    public static final String WIND = "WIND";
    public static final String OFF = "OFF";

    public static final Map<String, String> COMMANDS = new LinkedHashMap<>();
    static {
        COMMANDS.put(ANC, "AAECC100028EA48B");
        COMMANDS.put(AMBIENT, "AAECC100028EA78E");
        COMMANDS.put(WIND, "AAECC100028EA68D");
        COMMANDS.put(OFF, "AAECC100028EA188");
        COMMANDS.put("ANC 1", "AAECC100038EA4A430");
        COMMANDS.put("ANC 2", "AAECC100038EA4A733");
        COMMANDS.put("ANC 3", "AAECC100038EA4A632");
        COMMANDS.put("ANC 4", "AAECC100038EA4A12D");
        COMMANDS.put("ANC 5", "AAECC100038EA4A02C");
        COMMANDS.put("ANC 6", "AAECC100038EA4A32F");
        COMMANDS.put("ANC 7", "AAECC100038EA4A22E");
        COMMANDS.put("ANC 8", "AAECC100038EA4AD39");
        COMMANDS.put("AMBIENT -3", "AAECC100038EA7A635");
        COMMANDS.put("AMBIENT -2", "AAECC100038EA7A130");
        COMMANDS.put("AMBIENT -1", "AAECC100038EA7A02F");
        COMMANDS.put("AMBIENT 0", "AAECC100038EA7A332");
        COMMANDS.put("AMBIENT 1", "AAECC100038EA7A231");
        COMMANDS.put("AMBIENT 2", "AAECC100038EA7AD3C");
        COMMANDS.put("EQ Edifier", "AAECC40001A500");
        COMMANDS.put("EQ Jazz", "AAECC40001A4FF");
        COMMANDS.put("EQ Rock", "AAECC40001A702");
        COMMANDS.put("EQ Bass", "AAECC40001A601");
        // Custom EQ is not supported by this app.
        COMMANDS.put("Sound Music", "AAEC090001A545");
        COMMANDS.put("Sound Game", "AAEC090001A444");
        COMMANDS.put("Sound Spatial", "AAEC090001A747");
        COMMANDS.put("Timer 10m", "AAECD10002A5AFBD");
        COMMANDS.put("Timer 20m", "AAECD10002A5B1BF");
        COMMANDS.put("Timer 30m", "AAECD10002A5BBC9");
        COMMANDS.put("Timer 60m", "AAECD10002A599A7");
        COMMANDS.put("Timer 90m", "AAECD10002A5FF0D");
        COMMANDS.put("Timer Never", "AAECD2000068");
    }
    public static final String STATE_QUERY = "AAECCC000062";

    public static byte[] bytes(String key) {
        String hex = COMMANDS.get(key);
        if (hex == null) {
            throw new IllegalArgumentException("Unsupported command: " + key);
        }
        return fromHex(hex);
    }

    public static byte[] fromHex(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Odd hex length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Bad hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Reads a valid state reply, including its 8-bit checksum. */
    public static State parseState(byte[] buf, int length) {
        for (int start = 0; start + 6 <= length; start++) {
            if (u(buf[start]) != 0xBB || u(buf[start+1]) != 0xEC || u(buf[start+2]) != 0xCC) continue;
            int payloadLength = (u(buf[start+3]) << 8) | u(buf[start+4]);
            if (payloadLength < 2 || payloadLength > 32) continue;
            int frameLength = 6 + payloadLength;
            if (start + frameLength > length) continue;
            int checksum = 0;
            for (int k = start; k < start + frameLength - 1; k++) checksum = (checksum + u(buf[k])) & 0xFF;
            if (checksum != u(buf[start + frameLength - 1])) continue;
            if (u(buf[start+5]) != 0x8E) continue;

            int family = u(buf[start+6]);
            int setting = payloadLength >= 3 ? u(buf[start+7]) : -1;
            String mode = "UNMAPPED", level = "", confidence = "UNKNOWN";
            if (family == 0xA4 && setting >= 0) {
                mode = "ANC";
                int[] codes = {0xA4,0xA7,0xA6,0xA1,0xA0,0xA3,0xA2,0xAD};
                for (int i=0; i<codes.length; i++) if (codes[i]==setting) level = Integer.toString(i+1);
                confidence = (setting == 0xA6 || setting == 0xA3) ? "OBSERVED" : "INFERRED";
            } else if (family == 0xA7 && setting >= 0) {
                mode = "AMBIENT";
                int[] codes = {0xA6,0xA1,0xA0,0xA3,0xA2,0xAD};
                for (int i=0; i<codes.length; i++) if (codes[i]==setting) level = Integer.toString(i-3);
                confidence = (setting == 0xA1 || setting == 0xA3) ? "OBSERVED" : "INFERRED";
            } else if (family == 0xA6 && payloadLength==2) {
                mode="WIND"; confidence="OBSERVED";
            } else if (family == 0xA1 && payloadLength==2) {
                mode="OFF"; confidence="INFERRED";
            }
            StringBuilder raw = new StringBuilder();
            for (int k=start; k<start+frameLength; k++) raw.append(String.format("%02X", u(buf[k])));
            return new State(mode, level, confidence, raw.toString());
        }
        return null;
    }
    private static int u(byte b) {
        return b & 0xFF;
    }
    public static final class State {
        public final String mode, level, confidence, raw;
        State(String mode, String level, String confidence, String raw) {
            this.mode = mode;
            this.level = level;
            this.confidence = confidence;
            this.raw = raw;
        }
        public String label() {
            return mode + (level.isEmpty() ? "" : " " + level)
                    + " (" + confidence.toLowerCase() + ")";
        }
    }
}
