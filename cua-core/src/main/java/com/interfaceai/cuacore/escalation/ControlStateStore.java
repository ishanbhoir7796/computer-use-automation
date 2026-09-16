package com.interfaceai.cuacore.escalation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;

public class ControlStateStore {

    private final File file;
    private final ObjectMapper mapper;

    public ControlStateStore(String path) {
        this.file = new File(path);
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    public void write(ControlState state) {
        state.setUpdatedAt(java.time.Instant.now());
        try {
            file.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, state);
        } catch (IOException e) {
            throw new RuntimeException("failed to write control state to " + file, e);
        }
    }

    public ControlState read() {
        if (!file.exists()) {
            return ControlState.builder().build();
        }
        try {
            return mapper.readValue(file, ControlState.class);
        } catch (IOException e) {
            throw new RuntimeException("failed to read control state from " + file, e);
        }
    }

    public void handToHuman(InterventionRequest intervention) {
        write(ControlState.builder().owner("human").intervention(intervention).build());
    }

    public void appendHumanAction(HumanAction action) {
        ControlState state = read();
        state.getHumanActions().add(action);
        write(state);
    }

    public void handBackToAutomation(String resolution) {
        ControlState state = read();
        state.setOwner("automation");
        state.setResolution(resolution);
        write(state);
    }

    /**
     * Waits here, checking the shared file every so often, until a
     * separate program (the operator console) writes a resolution back to
     * it. This is how two separate programs coordinate a handoff -- since
     * they don't share memory, a file on disk is how they talk to
     * each other.
     */
    public ControlState waitForHuman(long pollIntervalMs, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            ControlState state = read();
            if ("automation".equals(state.getOwner()) && state.getResolution() != null) {
                return state;
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new RuntimeException("timed out waiting for human operator to hand control back");
    }
}