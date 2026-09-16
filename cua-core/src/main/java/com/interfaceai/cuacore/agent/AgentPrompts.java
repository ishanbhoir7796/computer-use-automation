package com.interfaceai.cuacore.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AgentPrompts {

    public static final String SYSTEM_PROMPT = """
        You are a back-office operations agent for a credit union. You drive a real
        web application's UI one action at a time to accomplish a stated GOAL, the same way a human
        teller would -- there is no API for this application.

        You will be shown the current page as a compact accessibility snapshot: its URL, a list of
        interactive elements (each with a role and an accessible name, e.g. role="textbox"
        name="Member ID or Number"), and the visible text on the page. You act by calling exactly one
        tool per turn. After each action you will be shown the resulting page state.

        Rules:
        - Identify controls by role + accessible NAME exactly as shown in the snapshot -- do not invent
          names or guess at ones not listed.
        - Use `extract` for any piece of information the goal asks you to read/report, and give it a
          clear machine-readable `output_name` (snake_case, e.g. "savings_balance").
        - If the page shows a validation error, a "not found" message, a permission/access-denied
          message, a session-expired message, or any other outcome that means the goal cannot be
          completed as stated, do NOT try creative workarounds. Call `finish_stuck` and explain what you
          observed. A human will review it.
        - If you reach a state you're not confident how to interpret, or an action fails twice in a row,
          call `finish_stuck` rather than guessing further.
        - When you believe the goal is complete, call `finish_success`. Its `success_condition_text` must
          be a short, EXACT substring of the current page's visible text that would prove -- to a program
          with no understanding, just string matching -- that this state was actually reached. Prefer text
          that would still be true for a DIFFERENT input (e.g. a section heading, a label, a confirmation
          code format) over a value that is specific to this one run (like a dollar amount, a name, or an
          account number) -- this capability will be replayed with different inputs later. Only fall back
          to a run-specific value if no stable structural text is available.
        - Do not extract the same output_name more than once. If a value already looks correct, trust it
          and move on -- don't second-guess a successful extract by trying again.
        - Be economical: don't re-extract, re-read, or click things not needed for the stated goal.
        """;

    public static ArrayNode buildTools(ObjectMapper mapper) {
        ArrayNode tools = mapper.createArrayNode();

        tools.add(tool(mapper, "navigate",
                "Go to an absolute URL within the target application.",
                obj -> {
                    obj.putObject("url").put("type", "string");
                }, "url"));

        tools.add(tool(mapper, "click",
                "Click an interactive element identified by its accessibility role and accessible name.",
                obj -> {
                    obj.putObject("role").put("type", "string");
                    obj.putObject("name").put("type", "string");
                }, "role", "name"));

        tools.add(tool(mapper, "fill",
                "Type a value into a text input identified by role + accessible name.",
                obj -> {
                    obj.putObject("role").put("type", "string");
                    obj.putObject("name").put("type", "string");
                    obj.putObject("value").put("type", "string");
                }, "role", "name", "value"));

        tools.add(tool(mapper, "select",
                "Choose an option in a dropdown identified by role + accessible name.",
                obj -> {
                    obj.putObject("role").put("type", "string");
                    obj.putObject("name").put("type", "string");
                    obj.putObject("value").put("type", "string");
                }, "role", "name", "value"));

        tools.add(tool(mapper, "extract",
                "Read the current text/value of an element identified by role + accessible name, and store it under output_name.",
                obj -> {
                    obj.putObject("role").put("type", "string");
                    obj.putObject("name").put("type", "string");
                    obj.putObject("output_name").put("type", "string");
                }, "role", "name", "output_name"));

        tools.add(tool(mapper, "finish_success",
                "Declare the goal accomplished.",
                obj -> {
                    obj.putObject("summary").put("type", "string");
                    obj.putObject("success_condition_text").put("type", "string");
                }, "summary", "success_condition_text"));

        tools.add(tool(mapper, "finish_stuck",
                "Stop because the goal cannot be safely completed automatically and a human should look at it.",
                obj -> {
                    obj.putObject("reason").put("type", "string");
                }, "reason"));

        return tools;
    }

    // Builds one tool definition in the JSON format Claude's API expects.
    private static ObjectNode tool(ObjectMapper mapper, String name, String description,
                                   java.util.function.Consumer<ObjectNode> propertiesBuilder,
                                   String... required) {
        ObjectNode toolNode = mapper.createObjectNode();
        toolNode.put("name", name);
        toolNode.put("description", description);

        ObjectNode inputSchema = toolNode.putObject("input_schema");
        inputSchema.put("type", "object");
        ObjectNode properties = inputSchema.putObject("properties");
        propertiesBuilder.accept(properties);

        ArrayNode requiredArray = inputSchema.putArray("required");
        for (String r : required) requiredArray.add(r);

        return toolNode;
    }
}