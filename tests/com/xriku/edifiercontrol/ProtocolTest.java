package com.xriku.edifiercontrol;

import java.util.Arrays;

/**
 * Standalone protocol checks. No Android SDK, headphones, or test framework needed.
 * The sample reply frames are built from the known frame format, not recordings
 * of an additional headphone/firmware revision.
 */
public final class ProtocolTest {
    private static int checks = 0;

    private static void check(boolean result, String description) {
        checks++;
        if (!result) throw new AssertionError(description);
    }

    private static void fails(Runnable action, String description) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, description);
    }

    /** BB EC CC + length + 8E + family [+ level] + additive checksum. */
    private static byte[] reply(int family, int... settings) {
        int payloadLength = 2 + settings.length;
        byte[] frame = new byte[6 + payloadLength];
        frame[0] = (byte) 0xBB;
        frame[1] = (byte) 0xEC;
        frame[2] = (byte) 0xCC;
        frame[3] = 0;
        frame[4] = (byte) payloadLength;
        frame[5] = (byte) 0x8E;
        frame[6] = (byte) family;
        for (int i = 0; i < settings.length; i++) frame[7 + i] = (byte) settings[i];
        int checksum = 0;
        for (int i = 0; i < frame.length - 1; i++) checksum = (checksum + (frame[i] & 0xFF)) & 0xFF;
        frame[frame.length - 1] = (byte) checksum;
        return frame;
    }

    private static void commandChecks() {
        check(Protocol.COMMANDS.size() == 31, "expected command inventory");
        check(Arrays.equals(Protocol.bytes(Protocol.ANC), Protocol.fromHex("AAECC100028EA48B")), "ANC bytes");
        check(Arrays.equals(Protocol.bytes(Protocol.WIND), Protocol.fromHex("AAECC100028EA68D")), "Wind bytes");
        check(Arrays.equals(Protocol.bytes("ANC 8"), Protocol.fromHex("AAECC100038EA4AD39")), "ANC 8 bytes");
        check(Arrays.equals(Protocol.fromHex(Protocol.STATE_QUERY), Protocol.fromHex("AAECCC000062")), "state query");
        for (String key : Protocol.COMMANDS.keySet()) {
            check(Protocol.bytes(key).length > 0, "valid hex for " + key);
        }
        fails(() -> Protocol.fromHex("123"), "odd-length hex rejected");
        fails(() -> Protocol.fromHex("XZ"), "invalid hex rejected");
        fails(() -> Protocol.bytes("NOT_SUPPORTED"), "unknown command rejected");
    }

    private static void replyChecks() {
        Protocol.State anc = Protocol.parseState(reply(0xA4, 0xA6), 9);
        check(anc != null, "ANC frame accepted");
        check("ANC".equals(anc.mode) && "3".equals(anc.level), "ANC level decoded");
        check("OBSERVED".equals(anc.confidence), "observed ANC code labeled correctly");
        check(anc.raw.startsWith("BBECCC"), "raw frame preserved");

        byte[] ambientFrame = reply(0xA7, 0xA1);
        Protocol.State ambient = Protocol.parseState(ambientFrame, ambientFrame.length);
        check(ambient != null && "AMBIENT".equals(ambient.mode), "Ambient decoded");
        check("-2".equals(ambient.level) && "OBSERVED".equals(ambient.confidence), "Ambient level decoded");
        Protocol.State inferred = Protocol.parseState(reply(0xA4, 0xA7), 9);
        check(inferred != null && "2".equals(inferred.level)
                && "INFERRED".equals(inferred.confidence), "inferred mapping labeled correctly");

        byte[] windFrame = reply(0xA6);
        Protocol.State wind = Protocol.parseState(windFrame, windFrame.length);
        check(wind != null && "WIND".equals(wind.mode), "Wind decoded");
        byte[] offFrame = reply(0xA1);
        Protocol.State off = Protocol.parseState(offFrame, offFrame.length);
        check(off != null && "OFF".equals(off.mode) && "INFERRED".equals(off.confidence), "Off decoded");

        byte[] prefix = {0x00, 0x01, 0x02};
        byte[] mixed = Arrays.copyOf(prefix, prefix.length + windFrame.length);
        System.arraycopy(windFrame, 0, mixed, prefix.length, windFrame.length);
        check(Protocol.parseState(mixed, mixed.length) != null, "frame after noisy prefix");
        check(Protocol.parseState(windFrame, windFrame.length - 1) == null, "incomplete frame ignored");
        windFrame[windFrame.length - 1] ^= 1;
        check(Protocol.parseState(windFrame, windFrame.length) == null, "bad checksum rejected");
        check(Protocol.parseState(new byte[] {1, 2, 3, 4}, 4) == null, "unrelated data ignored");
    }

    public static void main(String[] args) {
        commandChecks();
        replyChecks();
        System.out.println("PASS: " + checks + " protocol checks");
    }
}
