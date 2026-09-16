package com.interfaceai.cuacore.surface;

import com.interfaceai.cuacore.guardrails.AllowlistConfig;
import com.interfaceai.cuacore.schema.Locator;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The real implementation of Surface, using Playwright to drive an actual
 * Chromium browser. Reads the page's roles and names with our own JS
 * (since legacy pages often have no clean accessibility tree), and can
 * optionally expose the browser over CDP so a human operator can later
 * attach to that same session.
 */
public class PlaywrightSurface implements Surface {

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private boolean headless;
    private Integer lastStatus = null;
    private AllowlistConfig allowlist;

    private int cdpPort = 9222;   // fixed port for this demo; one handoff-capable session at a time
    private String cdpEndpoint;   // e.g. "http://localhost:9222" -- how a separate process attaches

    private static final String SNAPSHOT_JS = """
        () => {
          function accessibleName(el) {
            const aria = el.getAttribute('aria-label');
            if (aria) return aria.trim();
            if (el.id) {
              const lab = document.querySelector(`label[for="${el.id}"]`);
              if (lab) return lab.textContent.trim();
            }
            const parentLabel = el.closest('label');
            if (parentLabel) return parentLabel.textContent.trim();
            if (el.placeholder) return el.placeholder.trim();
            if (el.tagName === 'INPUT' && el.type === 'submit') return el.value || 'Submit';
            return (el.innerText || el.textContent || '').trim().slice(0, 120);
          }
          function roleOf(el) {
            const explicit = el.getAttribute('role');
            if (explicit) return explicit;
            const tag = el.tagName.toLowerCase();
            if (tag === 'a' && el.href) return 'link';
            if (tag === 'button') return 'button';
            if (tag === 'select') return 'combobox';
            if (tag === 'textarea') return 'textbox';
            if (tag === 'input') {
              const t = (el.type || 'text').toLowerCase();
              if (t === 'submit' || t === 'button') return 'button';
              if (t === 'checkbox') return 'checkbox';
              if (t === 'radio') return 'radio';
              return 'textbox';
            }
            return tag;
          }

          const selector = 'input, select, textarea, button, a[href]';
          const out = [];
          document.querySelectorAll(selector).forEach(el => {
            const rect = el.getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) return;
            out.push({
              role: roleOf(el),
              name: accessibleName(el),
              tag: el.tagName.toLowerCase(),
              value: (el.value !== undefined ? String(el.value) : ''),
              href: el.tagName === 'A' ? (el.getAttribute('href') || '') : '',
              xpath: ''
            });
          });

          // Table cells: we give each one a readable name (row + column,
          // like "Savings Balance") for the LLM to look at, but that name
          // is invented, not something the browser can actually search by.
          // So we also compute a real xpath here, which is what actually
          // gets used to find the cell later.
          document.querySelectorAll('table').forEach(table => {
            const rows = Array.from(table.querySelectorAll('tr'));
            if (rows.length === 0) return;
            let headers = null;
            if (rows[0].querySelectorAll('th').length > 0) {
              headers = Array.from(rows[0].children).map(c => c.textContent.trim());
            }
            const startIdx = headers ? 1 : 0;
            for (let r = startIdx; r < rows.length; r++) {
              const cells = Array.from(rows[r].children).filter(c => c.tagName === 'TD' || c.tagName === 'TH');
              if (cells.length === 0) continue;
              const rowLabel = cells[0].textContent.trim();
              cells.forEach((cell, idx) => {
                const rect = cell.getBoundingClientRect();
                if (rect.width === 0 && rect.height === 0) return;
                const colHeader = headers && headers[idx] ? headers[idx] : '';
                const name = idx === 0 ? rowLabel : (rowLabel + ' ' + colHeader).trim();
                const xpath = `//tr[td[1][normalize-space()=${JSON.stringify(rowLabel)}]]/td[${idx + 1}]`;
                out.push({
                  role: 'cell',
                  name: name,
                  tag: 'td',
                  value: cell.textContent.trim(),
                  href: '',
                  xpath: xpath
                });
              });
            }
          });

          return out;
        }
        """;

    public PlaywrightSurface(AllowlistConfig allowlist, boolean headless) {
        this.allowlist = allowlist;
        this.headless = headless;
    }

    // -- lifecycle -----------------------------------------------------
    public PlaywrightSurface launch() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
        context = browser.newContext();
        page = context.newPage();
        page.onResponse(response -> {
            if (response.url().equals(page.url())) {
                lastStatus = response.status();
            }
        });
        return this;
    }

    /**
     * Starts Chromium with its remote-debugging port open. This lets a
     * separate program (the operator console) connect to this exact same
     * browser later, instead of opening a new one.
     */
    public PlaywrightSurface launchExposedForHandoff() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(List.of("--remote-debugging-port=" + cdpPort)));
        context = browser.newContext();
        page = context.newPage();
        page.onResponse(response -> {
            if (response.url().equals(page.url())) {
                lastStatus = response.status();
            }
        });
        cdpEndpoint = "http://localhost:" + cdpPort;
        return this;
    }

    @Override
    public String getCdpEndpoint() {
        return cdpEndpoint;
    }

    /**
     * Connects to a browser that's already running (started elsewhere with
     * launchExposedForHandoff) and takes over its existing page. This is
     * how the operator console attaches to a paused session.
     */
    public static PlaywrightSurface attach(String cdpEndpoint, AllowlistConfig allowlist) {
        PlaywrightSurface surf = new PlaywrightSurface(allowlist, true);
        surf.playwright = Playwright.create();
        surf.browser = surf.playwright.chromium().connectOverCDP(cdpEndpoint);
        surf.context = surf.browser.contexts().get(0);
        surf.page = surf.context.pages().isEmpty() ? surf.context.newPage() : surf.context.pages().get(0);
        return surf;
    }

    public void close() {
        try {
            if (context != null) context.close();
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
        } catch (Exception ignored) {
        }
    }

    // -- perceive --------------------------------------------------------
    @SuppressWarnings("unchecked")
    @Override
    public Snapshot perceive() {
        Object result = page.evaluate(SNAPSHOT_JS);
        List<Map<String, Object>> raw = (List<Map<String, Object>>) result;

        List<AccessibilityNode> nodes = new ArrayList<>();
        for (Map<String, Object> n : raw) {
            nodes.add(AccessibilityNode.builder()
                    .role(String.valueOf(n.get("role")))
                    .name(String.valueOf(n.get("name")))
                    .tag(String.valueOf(n.get("tag")))
                    .value(String.valueOf(n.get("value")))
                    .href(String.valueOf(n.get("href")))
                    .xpath(n.get("xpath") != null ? String.valueOf(n.get("xpath")) : "")
                    .build());
        }

        String visibleText;
        try {
            visibleText = page.innerText("body");
        } catch (Exception e) {
            visibleText = "";
        }

        return Snapshot.builder()
                .url(page.url())
                .title(page.title())
                .nodes(nodes)
                .visibleText(visibleText)
                .build();
    }

    // -- locator resolution ----------------------------------------------
    private com.microsoft.playwright.Locator buildPwLocator(Locator loc) {
        return switch (loc.getStrategy()) {
            case ROLE_NAME -> page.getByRole(
                    AriaRole.valueOf(loc.getRole().toUpperCase()),
                    new Page.GetByRoleOptions().setName(loc.getName()));
            case LABEL_TEXT -> page.getByLabel(loc.getName());
            case TEXT_EXACT -> page.getByText(loc.getText(), new Page.GetByTextOptions().setExact(true));
            case CSS -> page.locator(loc.getCss());
        };
    }

    private com.microsoft.playwright.Locator resolve(Locator locator) {
        List<Locator> candidates = new ArrayList<>();
        candidates.add(locator);
        candidates.addAll(locator.getFallbacks());

        List<String> errors = new ArrayList<>();
        for (Locator cand : candidates) {
            try {
                com.microsoft.playwright.Locator pwLoc = buildPwLocator(cand);
                int count = pwLoc.count();
                if (count >= 1) {
                    return pwLoc.nth(Math.min(cand.getNth(), count - 1));
                }
            } catch (Exception e) {
                errors.add(cand.describe() + ": " + e.getMessage());
            }
        }
        throw new LocatorResolutionError(
                "could not resolve locator (tried " + candidates.size() + " strategies): "
                        + String.join("; ", errors.isEmpty() ? List.of(locator.describe()) : errors));
    }

    // -- actions -----------------------------------------------------------
    @Override
    public void navigate(String url) {
        allowlist.checkNavigation(url);
        allowlist.checkActionType("navigate");
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
    }

    @Override
    public void click(Locator locator, int timeoutMs) {
        allowlist.checkActionType("click");
        com.microsoft.playwright.Locator pwLoc = resolve(locator);
        pwLoc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(timeoutMs));
        try {
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(timeoutMs));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void fill(Locator locator, String value, int timeoutMs) {
        allowlist.checkActionType("fill");
        com.microsoft.playwright.Locator pwLoc = resolve(locator);
        pwLoc.fill(value, new com.microsoft.playwright.Locator.FillOptions().setTimeout(timeoutMs));
    }

    @Override
    public void select(Locator locator, String value, int timeoutMs) {
        allowlist.checkActionType("select");
        com.microsoft.playwright.Locator pwLoc = resolve(locator);
        pwLoc.selectOption(value, new com.microsoft.playwright.Locator.SelectOptionOptions().setTimeout(timeoutMs));
    }

    @Override
    public String extract(Locator locator, int timeoutMs) {
        allowlist.checkActionType("extract");
        com.microsoft.playwright.Locator pwLoc = resolve(locator);
        try {
            return pwLoc.inputValue(new com.microsoft.playwright.Locator.InputValueOptions().setTimeout(timeoutMs));
        } catch (Exception e) {
            return pwLoc.innerText(new com.microsoft.playwright.Locator.InnerTextOptions().setTimeout(timeoutMs));
        }
    }

    @Override
    public boolean assertText(String text, boolean present) {
        String body;
        try {
            body = page.innerText("body");
        } catch (Exception e) {
            body = "";
        }
        boolean found = Snapshot.normalizeWhitespace(body).contains(Snapshot.normalizeWhitespace(text));
        return present == found;
    }

    @Override
    public boolean waitForText(String text, int timeoutMs) {
        try {
            page.waitForFunction(
                    "t => document.body && document.body.innerText.includes(t)",
                    text,
                    new Page.WaitForFunctionOptions().setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return assertText(text, true);
        }
    }

    @Override
    public void screenshot(String path) {
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get(path)).setFullPage(true));
        } catch (Exception ignored) {
        }
    }

    @Override
    public String currentUrl() {
        return page.url();
    }

    @Override
    public Integer httpStatus() {
        return lastStatus;
    }
}