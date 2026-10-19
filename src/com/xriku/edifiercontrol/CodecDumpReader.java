package com.xriku.edifiercontrol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads codec information from an Android Bluetooth diagnostic report. */
public final class CodecDumpReader {
    private static final Pattern CODEC_NAME =
            Pattern.compile("codecName\\s*:\\s*(LDAC|AAC|SBC)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENT =
            Pattern.compile("Current Codec\\s*:\\s*(LDAC|AAC|SBC)\\b", Pattern.CASE_INSENSITIVE);

    private CodecDumpReader() { }

    public static final class Snapshot {
        public final String current;
        public final String available;
        Snapshot(String current, String available) {
            this.current = current;
            this.available = available;
        }
    }

    public static Snapshot parse(Reader input) throws IOException {
        BufferedReader reader = (input instanceof BufferedReader)
                ? (BufferedReader) input : new BufferedReader(input);
        LinkedHashSet<String> available = new LinkedHashSet<String>();
        String current = "";
        boolean selectableBlock = false;
        boolean codecState = false;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.contains("mCodecsSelectableCapabilities:")) {
                // Use the latest list, not a union across paired devices.
                available.clear();
                selectableBlock = true;
            }
            if (selectableBlock) {
                Matcher c = CODEC_NAME.matcher(line);
                while (c.find()) {
                    available.add(c.group(1).toUpperCase(java.util.Locale.ROOT));
                }
                if (trimmed.contains("}]") || trimmed.equals("]") || trimmed.startsWith("],")) {
                    selectableBlock = false;
                }
            }
            if (trimmed.equals("A2DP Codecs State:")) {
                codecState = true;
            }
            if (codecState) {
                Matcher c = CURRENT.matcher(trimmed);
                if (c.find()) {
                    current = c.group(1).toUpperCase(java.util.Locale.ROOT);
                    codecState = false;
                }
            }
        }
        StringBuilder joined = new StringBuilder();
        for (String name : new String[]{"LDAC", "AAC", "SBC"}) {
            if (available.contains(name)) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(name);
            }
        }
        return new Snapshot(current, joined.toString());
    }
}
