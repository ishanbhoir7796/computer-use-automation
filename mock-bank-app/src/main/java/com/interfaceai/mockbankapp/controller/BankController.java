package com.interfaceai.mockbankapp.controller;

import com.interfaceai.mockbankapp.model.Account;
import com.interfaceai.mockbankapp.model.Member;
import com.interfaceai.mockbankapp.service.MemberService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
public class BankController {

    private final MemberService memberService;

    public BankController(MemberService memberService) {
        this.memberService = memberService;
    }

    // Handles the "simulate=" query param, which fakes different failure
    // conditions for testing (slow load, timeout, server error). Returns
    // a page name to show instead, or null to just continue normally.
    private String applySimulation(String simulate, Model model, HttpServletResponse response) {
        if (simulate == null) return null;
        switch (simulate) {
            case "slow" -> {
                try {
                    Thread.sleep(4500); // pretend the page is loading slowly
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return null; // after the delay, continue normally
            }
            case "timeout" -> {
                response.setStatus(440);
                return "sessionExpired";
            }
            case "servererror" -> {
                response.setStatus(500);
                return "serverError";
            }
            default -> {
                return null;
            }
        }
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/search";
    }

    @GetMapping("/search")
    public String searchForm(Model model) {
        model.addAttribute("query", "");
        model.addAttribute("results", null);
        return "search";
    }

    @PostMapping("/search")
    public String search(@RequestParam("member_query") String memberQuery,
                         @RequestParam(value = "simulate", required = false) String simulate,
                         Model model, HttpServletResponse response) {
        String sim = applySimulation(simulate, model, response);
        if (sim != null) return sim;

        String query = memberQuery == null ? "" : memberQuery.trim();
        model.addAttribute("query", query);

        Optional<Member> found = memberService.findById(query);
        if (found.isPresent()) {
            model.addAttribute("results", List.of(found.get()));
        } else if (!query.isEmpty()) {
            model.addAttribute("results", List.of()); // legitimate "no records found" business outcome
        } else {
            model.addAttribute("results", null);
        }
        return "search";
    }

    @GetMapping("/member/{memberId}")
    public String memberDetail(@PathVariable String memberId,
                               @RequestParam(value = "simulate", required = false) String simulate,
                               Model model, HttpServletResponse response) {
        String sim = applySimulation(simulate, model, response);
        if (sim != null) return sim;

        Optional<Member> member = memberService.findById(memberId);
        if (member.isEmpty()) {
            model.addAttribute("memberId", memberId);
            response.setStatus(404);
            return "notFound";
        }
        model.addAttribute("member", member.get());
        return "memberDetail";
    }

    @GetMapping("/member/{memberId}/open-account")
    public String openAccountForm(@PathVariable String memberId,
                                  @RequestParam(value = "simulate", required = false) String simulate,
                                  Model model, HttpServletResponse response) {
        String sim = applySimulation(simulate, model, response);
        if (sim != null) return sim;

        Optional<Member> member = memberService.findById(memberId);
        if (member.isEmpty()) {
            model.addAttribute("memberId", memberId);
            response.setStatus(404);
            return "notFound";
        }
        model.addAttribute("member", member.get());
        model.addAttribute("error", null);
        model.addAttribute("accountType", "");
        model.addAttribute("initialDeposit", "");
        return "openAccount";
    }

    @PostMapping("/member/{memberId}/open-account")
    public String openAccountSubmit(@PathVariable String memberId,
                                    @RequestParam("account_type") String accountType,
                                    @RequestParam("initial_deposit") String depositRaw,
                                    @RequestParam(value = "confirm_large_deposit", required = false) String confirmLargeDeposit,
                                    @RequestParam(value = "simulate", required = false) String simulate,
                                    Model model, HttpServletResponse response) {
        String sim = applySimulation(simulate, model, response);
        if (sim != null) return sim;

        Optional<Member> memberOpt = memberService.findById(memberId);
        if (memberOpt.isEmpty()) {
            model.addAttribute("memberId", memberId);
            response.setStatus(404);
            return "notFound";
        }
        Member member = memberOpt.get();
        boolean confirmedLarge = "yes".equals(confirmLargeDeposit);

        // --- validation error: expected business outcome, recoverable by re-entering data ---
        double deposit;
        try {
            deposit = Double.parseDouble(depositRaw.trim());
            if (deposit <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            model.addAttribute("member", member);
            model.addAttribute("error", "Validation error: '" + depositRaw + "' is not a valid positive deposit amount.");
            model.addAttribute("accountType", accountType);
            model.addAttribute("initialDeposit", depositRaw);
            return "openAccount";
        }

        // --- permission denial: hard failure, needs a human decision ---
        if ("Trust".equals(accountType) && member.isRestricted()) {
            model.addAttribute("member", member);
            response.setStatus(403);
            return "accessDenied";
        }

        // --- unexpected interstitial: large deposits need an extra confirmation step ---
        if (deposit > 10000 && !confirmedLarge) {
            model.addAttribute("member", member);
            model.addAttribute("accountType", accountType);
            model.addAttribute("initialDeposit", depositRaw);
            return "confirmLargeDeposit";
        }

        // --- success path ---
        Account newAccount = memberService.openAccount(member, accountType, deposit);
        String confirmationId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        model.addAttribute("member", member);
        model.addAttribute("account", newAccount);
        model.addAttribute("confirmationId", confirmationId);
        return "confirmation";
    }
}