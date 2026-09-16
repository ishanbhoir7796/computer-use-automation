package com.interfaceai.cuacore.schema;

public enum LocatorStrategy {
    ROLE_NAME,   // accessibility role + accessible name (primary, most robust)
    LABEL_TEXT,  // <label> text bound to a form control
    TEXT_EXACT,  // exact visible text
    CSS          // raw CSS selector
}