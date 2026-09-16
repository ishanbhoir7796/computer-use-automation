package com.interfaceai.cuacore.schema;

public enum ActionType {
    NAVIGATE,
    CLICK,
    FILL,
    SELECT,
    EXTRACT,       // read text from an element into a named output
    ASSERT_TEXT,   // checkpoint: assert page contains/lacks text
    WAIT_FOR_TEXT
}