package com.interfaceai.mockbankapp.service;

import com.interfaceai.mockbankapp.model.Account;
import com.interfaceai.mockbankapp.model.Member;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MemberService {

    private final Map<String, Member> members = new HashMap<>();
    private final AtomicInteger nextAccountSeq = new AtomicInteger(9000);

    public MemberService() {
        members.put("10001", Member.builder()
                .memberId("10001")
                .name("Jordan Rivera")
                .restricted(false)
                .accounts(new java.util.ArrayList<>(List.of(
                        Account.builder().type("Checking").number("CHK-88213").balance(4250.10).build(),
                        Account.builder().type("Savings").number("SAV-30044").balance(12003.44).build()
                )))
                .build());

        members.put("10002", Member.builder()
                .memberId("10002")
                .name("Casey Nguyen")
                .restricted(true) // not permitted to open Trust sub-accounts
                .accounts(new java.util.ArrayList<>(List.of(
                        Account.builder().type("Checking").number("CHK-77410").balance(980.00).build()
                )))
                .build());

        members.put("10003", Member.builder()
                .memberId("10003")
                .name("Amina Okafor")
                .restricted(false)
                .accounts(new java.util.ArrayList<>(List.of(
                        Account.builder().type("Savings").number("SAV-51190").balance(320.55).build()
                )))
                .build());
    }

    public Optional<Member> findById(String memberId) {
        return Optional.ofNullable(members.get(memberId));
    }

    /**
     * Opens a new sub-account for the given member and returns it.
     * Caller (the controller) is responsible for validation/business-rule
     * checks (restricted members, deposit validation, large-deposit
     * confirmation) BEFORE calling this -- this method assumes the request
     * has already been approved.
     */
    public Account openAccount(Member member, String accountType, double initialDeposit) {
        String prefix = accountType.length() >= 3
                ? accountType.substring(0, 3).toUpperCase()
                : accountType.toUpperCase();
        Account account = Account.builder()
                .type(accountType)
                .number(prefix + "-" + nextAccountSeq.incrementAndGet())
                .balance(initialDeposit)
                .build();
        member.getAccounts().add(account);
        return account;
    }
}