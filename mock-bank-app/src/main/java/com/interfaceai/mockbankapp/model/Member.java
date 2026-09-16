package com.interfaceai.mockbankapp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Member {

    private String memberId;   // e.g. "10001"
    private String name;
    private boolean restricted; // true = not permitted to open Trust sub-accounts
    private List<Account> accounts;
}