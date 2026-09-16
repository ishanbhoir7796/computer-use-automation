package com.interfaceai.cuacore.guardrails;

public class AllowlistViolation extends RuntimeException {
    public AllowlistViolation(String message) {
        super(message);
    }
}