package com.interfaceai.cuacore.schema;

public enum RiskLevel {
    SAFE,   // read-only or trivially reversible
    RISKY   // mutates state / hard to reverse
}