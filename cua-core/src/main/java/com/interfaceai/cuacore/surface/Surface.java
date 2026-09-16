package com.interfaceai.cuacore.surface;

import com.interfaceai.cuacore.schema.Locator;

/**
 * The one interface everything else talks through to perceive and act on
 * a screen. Neither the discovery agent nor the replay engine knows or
 * cares whether this is Playwright driving a browser, or something else
 * entirely (like a desktop app driver) -- they only know perceive/click/
 * fill/extract. This is what lets the same schema and logic work on a
 * different kind of surface later, without changing anything above it.
 */
public interface Surface {

    Snapshot perceive();

    void navigate(String url);

    void click(Locator locator, int timeoutMs);

    void fill(Locator locator, String value, int timeoutMs);

    void select(Locator locator, String value, int timeoutMs);

    String extract(Locator locator, int timeoutMs);

    boolean assertText(String text, boolean present);

    boolean waitForText(String text, int timeoutMs);

    void screenshot(String path);

    String currentUrl();

    Integer httpStatus();

    // Returns null unless this surface was launched in a way that exposes
    // it for handoff (see PlaywrightSurface.launchExposedForHandoff()).
    default String getCdpEndpoint() {
        return null;
    }
}