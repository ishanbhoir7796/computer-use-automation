package com.interfaceai.cuacore.cli;

import java.util.HashMap;
import java.util.Map;

public class ArgParser {

    private final Map<String, String> flags = new HashMap<>();
    private final Map<String, Object> inputs = new HashMap<>();

    public ArgParser(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--input=")) {
                continue; // not used, --input is handled with a space-separated value below
            }
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                flags.put(parts[0], parts[1]);
            }
        }
    }

    public String get(String key, String defaultValue) {
        return flags.getOrDefault(key, defaultValue);
    }

    public String require(String key) {
        String value = flags.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required flag: --" + key + "=...");
        }
        return value;
    }

    /**
     * Parses repeated --input key=value flags into a Map, e.g.
     * --input=member_id=10001 --input=account_type=Savings
     */
    public static Map<String, Object> parseInputs(String[] args) {
        Map<String, Object> inputs = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--input=")) {
                String kv = arg.substring("--input=".length());
                String[] parts = kv.split("=", 2);
                if (parts.length == 2) {
                    inputs.put(parts[0], parts[1]);
                }
            }
        }
        return inputs;
    }
}